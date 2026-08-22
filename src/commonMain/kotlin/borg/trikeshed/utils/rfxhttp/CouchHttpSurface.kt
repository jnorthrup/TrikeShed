package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.ReduceFunction
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * The stored document as a JSON object. `ConfixDoc.reify(0)` on an object yields the
 * token's children, not a Map, so re-parse the source bytes the store already holds.
 */
internal fun ConfixDocStoreEntry.jsonBody(): Map<String, Any?> {
    val src = doc.b
    val text = ByteArray(src.size) { src[it] }.decodeToString()
    val parsed = runCatching { JsonSupport.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyMap()
    return parsed.entries.associate { it.key.toString() to it.value }
}

/** One HTTP reply from [CouchHttpSurface]: status code plus a JSON body. */
data class CouchHttpReply(val status: Int, val json: Map<String, Any?>) {
    val body: String get() = JsonSupport.stringify(json)
}

/**
 * CouchHttpSurface — the CouchDB 1.6.2 HTTP shape over [ConfixDocStore] +
 * [ViewServer] + [CouchRequestFactory]. commonMain; no sockets, no clock.
 * The litebike HTTP worker parses the request line and hands
 * `(method, rawPath, body)` here; the reply is rendered back by the worker.
 *
 * Routes (one database, [dbName]):
 *   GET    /                                   → {couchdb:"Welcome", version:"1.6.2"}
 *   GET    /{db}                               → {db_name, doc_count, update_seq}
 *   POST   /{db}                               → RequestFactory envelope ({operations:[…]} → {ok, receipts}),
 *                                                 or a bare document → implicit put → 201 {ok,id,rev}
 *   PUT    /{db}/{id}[?rev=]                   → 201 {ok,id,rev} | 409 conflict
 *   GET    /{db}/{id}                          → doc with _id/_rev | 404 not_found
 *   DELETE /{db}/{id}?rev=                     → 200 {ok,id,rev} | 409 | 404
 *   GET    /{db}/_design/{d}/_view/{v}?params  → {total_rows, offset, rows:[{id,key,value[,doc]}]}
 *
 * Design docs carry views in the RequestFactory view-spec shape (not JS):
 *   { "views": { "by_type": { "map": { "key": "type", "value": "qty" }, "reduce": "_sum" } } }
 *
 * View params honoured (1.6.2 semantics, JSON-encoded values) — parsed by [ViewQuery.fromQueryString]:
 *   key, startkey, endkey, inclusive_end, descending, skip, limit,
 *   reduce, group, group_level (>0 ⇒ group), include_docs.
 */
class CouchHttpSurface(
    val dbName: String,
    val store: ConfixDocStore,
    val viewServer: ViewServer = ViewServer(),
    val requestFactory: CouchRequestFactory = CouchRequestFactory(store, viewServer),
) {

    suspend fun handle(method: String, rawPath: String, body: String): CouchHttpReply {
        val path = rawPath.substringBefore('?')
        val query = parseQuery(rawPath.substringAfter('?', ""))
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return try {
            route(method.uppercase(), segments, query, body)
        } catch (e: Throwable) {
            error(500, "internal_error", e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    private suspend fun route(
        method: String,
        segments: List<String>,
        query: Map<String, String>,
        body: String,
    ): CouchHttpReply {
        if (segments.isEmpty()) {
            return if (method == "GET") CouchHttpReply(200, welcome()) else error(405, "method_not_allowed", method)
        }
        if (segments[0] != dbName) return error(404, "not_found", "no_db_file")
        val rest = segments.drop(1)
        if (rest.isEmpty()) return when (method) {
            "GET" -> CouchHttpReply(200, dbInfo())
            "POST" -> post(body)
            else -> error(405, "method_not_allowed", method)
        }
        // Normalize the id after {db}: "_design/{d}" is one id spanning two segments; whatever
        // follows it is the sub-path (/_view/{v}), otherwise the id is the remaining path as-is.
        val design = rest[0] == "_design" && rest.size >= 2
        val id = if (design) "_design/${rest[1]}" else rest.joinToString("/")
        val tail = if (design) rest.drop(2) else emptyList()
        return when {
            tail.isEmpty() -> doc(method, id, body, query)
            tail[0] == "_view" ->
                if (method != "GET") error(405, "method_not_allowed", method)
                else if (tail.size == 2) view(id, tail[1], query)
                else error(404, "not_found", "missing_named_view")
            else -> doc(method, rest.joinToString("/"), body, query)
        }
    }

    private fun doc(method: String, id: String, body: String, query: Map<String, String>): CouchHttpReply =
        when (method) {
            "GET" -> getDoc(id)
            "PUT" -> putDoc(id, body, query["rev"])
            "DELETE" -> deleteDoc(id, query["rev"])
            else -> error(405, "method_not_allowed", method)
        }

    // ── documents ─────────────────────────────────────────────────

    private fun welcome(): Map<String, Any?> = mapOf(
        "couchdb" to "Welcome",
        "version" to COUCH_VERSION,
        "vendor" to mapOf("name" to "TrikeShed", "version" to "litebike"),
    )

    private fun dbInfo(): Map<String, Any?> = mapOf(
        "db_name" to dbName,
        "doc_count" to store.size,
        "update_seq" to store.size,
    )

    private fun putDoc(id: String, body: String, queryRev: String?): CouchHttpReply {
        val parsed = if (body.isBlank()) emptyMap<String, Any?>() else JsonSupport.parse(body)
        val fields = parsed as? Map<*, *> ?: return error(400, "bad_request", "Document must be a JSON object")
        val rev = queryRev ?: fields["_rev"] as? String
        if (rev == null && store.contains(id)) return conflict(id)
        val entry = store.put(id, JsonSupport.stringify(stripMeta(fields)), rev) ?: return conflict(id)
        return CouchHttpReply(201, okReceipt(entry))
    }

    private fun getDoc(id: String): CouchHttpReply {
        val entry = store[id] ?: return error(404, "not_found", "missing")
        return CouchHttpReply(200, entry.asCouchDoc())
    }

    private fun deleteDoc(id: String, rev: String?): CouchHttpReply {
        if (!store.contains(id)) return error(404, "not_found", "missing")
        if (rev == null) return conflict(id)
        return if (store.delete(id, rev)) CouchHttpReply(200, mapOf("ok" to true, "id" to id, "rev" to rev))
        else conflict(id)
    }

    private suspend fun post(body: String): CouchHttpReply {
        val parsed = runCatching { JsonSupport.parse(body) }.getOrNull()
        val asMap = parsed as? Map<*, *> ?: return error(400, "bad_request", "invalid UTF-8 JSON")
        if (asMap.containsKey("operations")) {
            val reply = JsonSupport.parse(requestFactory.processRequest(body)) as Map<*, *>
            return CouchHttpReply(200, reply.entries.associate { it.key.toString() to it.value })
        }
        val id = asMap["_id"] as? String ?: ContentId.of(body.encodeToByteArray()).hex
        return putDoc(id, body, asMap["_rev"] as? String)
    }

    // ── views ─────────────────────────────────────────────────────

    private fun view(ddoc: String, viewName: String, query: Map<String, String>): CouchHttpReply {
        val design = store[ddoc]?.jsonBody() ?: return error(404, "not_found", "missing")
        val spec = (design["views"] as? Map<*, *>)?.get(viewName) as? Map<*, *>
            ?: return error(404, "not_found", "missing_named_view")
        val map = spec["map"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val reduceFn = reduceFn(spec["reduce"])
        val q = ViewQuery.fromQueryString(query)
        val wantReduce = q.wantReduce(reduceFn != null)
        if (wantReduce && reduceFn == null) return error(400, "query_parse_error", "Reduce is invalid for map-only views.")

        val def = ViewDefinition(
            ddoc = ddoc,
            viewName = viewName,
            mapFn = MapFunction.Emit(keyExpr(map["key"]), valueExpr(map["value"])),
            reduceFn = if (wantReduce) reduceFn else null,
        )
        val mapped = viewServer.execute(def.copy(reduceFn = null), documents())
        val selected = q.select(mapped)

        if (!wantReduce) {
            return CouchHttpReply(
                200,
                mapOf(
                    "total_rows" to mapped.size,
                    "offset" to q.offset(selected),
                    "rows" to q.page(selected).map { row ->
                        // emit(key, doc) in CouchDB yields the object; ViewServer's DocValue is a string.
                        val value = if (map["value"] == "doc") store[row.docId]?.asCouchDoc() else row.value
                        val base = mapOf("id" to row.docId, "key" to row.key, "value" to value)
                        if (q.include_docs) base + ("doc" to store[row.docId]?.asCouchDoc()) else base
                    },
                ),
            )
        }

        val reduced = viewServer.execute(def, selected.map { row -> store[row.docId]!!.toDocument() }.distinctBy { it.id })
        val rows: List<Map<String, Any?>> = if (q.grouped) {
            reduced.rows.toKList().map { mapOf("key" to it.key, "value" to it.value) }
        } else {
            listOf(mapOf("key" to null, "value" to ViewQuery.rereduce(reduceFn!!, reduced)))
        }
        return CouchHttpReply(200, mapOf("rows" to rows))
    }

    private fun documents(): List<Document> {
        val entries = store.byIdPrefix("")
        return List(entries.size) { i -> entries[i] }
            .filterNot { it.id.startsWith("_design/") }
            .map { it.toDocument() }
    }

    private fun ConfixDocStoreEntry.toDocument(): Document {
        val body = jsonBody()
        return Document(
            id,
            body.entries
                .filter { it.key != "_id" && it.key != "_rev" }
                .map { Field(it.key.toString(), it.value ?: "null") },
        )
    }

    private fun ConfixDocStoreEntry.asCouchDoc(): Map<String, Any?> {
        val body = jsonBody()
        return linkedMapOf<String, Any?>("_id" to id, "_rev" to rev) + stripMeta(body)
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun stripMeta(fields: Map<*, *>): Map<String, Any?> =
        fields.entries.filter { it.key != "_id" && it.key != "_rev" }.associate { it.key.toString() to it.value }

    private fun okReceipt(entry: ConfixDocStoreEntry): Map<String, Any?> =
        mapOf("ok" to true, "id" to entry.id, "rev" to entry.rev)

    private fun conflict(id: String): CouchHttpReply =
        CouchHttpReply(409, mapOf("error" to "conflict", "reason" to "Document update conflict.", "id" to id))

    private fun error(status: Int, error: String, reason: String): CouchHttpReply =
        CouchHttpReply(status, mapOf("error" to error, "reason" to reason))

    private fun keyExpr(spec: Any?): KeyExpr = when (spec) {
        null -> KeyExpr.DocId
        is String -> KeyExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> KeyExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> KeyExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> KeyExpr.Const(spec["const"])
            else -> KeyExpr.DocId
        }
        else -> KeyExpr.Const(spec)
    }

    private fun valueExpr(spec: Any?): ValueExpr = when (spec) {
        null -> ValueExpr.Const(1)
        "doc" -> ValueExpr.DocValue
        is String -> ValueExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> ValueExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> ValueExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> ValueExpr.Const(spec["const"])
            else -> ValueExpr.DocValue
        }
        else -> ValueExpr.Const(spec)
    }

    private fun reduceFn(spec: Any?): ReduceFunction? = when (spec) {
        null -> null
        is String -> ReduceFunction.Builtin(spec)
        is Map<*, *> -> (spec["dsl"] as? String)?.let(ReduceFunction::Custom)
        else -> null
    }

    private fun <T> borg.trikeshed.lib.Series<T>.toKList(): List<T> = List(size) { i -> this[i] }

    companion object {
        const val COUCH_VERSION = "1.6.2"

        /** CouchDB collation order, reduced to what keys here can be: null < bool < number < string < array < object. */
        fun collate(a: Any?, b: Any?): Int {
            fun rank(v: Any?): Int = when (v) {
                null -> 0; is Boolean -> 1; is Number -> 2; is String -> 3; is List<*> -> 4; else -> 5
            }
            val ra = rank(a); val rb = rank(b)
            if (ra != rb) return ra.compareTo(rb)
            return when (a) {
                null -> 0
                is Boolean -> a.compareTo(b as Boolean)
                is Number -> a.toDouble().compareTo((b as Number).toDouble())
                is String -> a.compareTo(b as String)
                is List<*> -> {
                    val bl = b as List<*>
                    for (i in 0 until minOf(a.size, bl.size)) {
                        val c = collate(a[i], bl[i]); if (c != 0) return c
                    }
                    a.size.compareTo(bl.size)
                }
                else -> a.toString().compareTo(b.toString())
            }
        }

        /** `a=1&b=%22x%22` → map; percent-decoded, '+' as space. */
        fun parseQuery(raw: String): Map<String, String> =
            raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                percentDecode(k) to percentDecode(v)
            }

        fun percentDecode(s: String): String {
            val out = ByteArray(s.length * 3)
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' && i + 2 < s.length -> {
                        out[n++] = s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3
                    }
                    c == '+' -> { out[n++] = ' '.code.toByte(); i++ }
                    else -> {
                        val bytes = c.toString().encodeToByteArray()
                        bytes.copyInto(out, n); n += bytes.size; i++
                    }
                }
            }
            return out.decodeToString(0, n)
        }
    }
}
