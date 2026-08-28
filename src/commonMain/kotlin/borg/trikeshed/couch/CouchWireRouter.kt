package borg.trikeshed.couch

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.io.ContentTypes
import borg.trikeshed.utils.rfxhttp.CouchHttpSurface

/** A rendered reply: status, content type, raw bytes. Binary-safe so attachments and blocks can flow. */
data class WireReply(val status: Int, val contentType: String, val bytes: ByteArray) {
    companion object {
        fun json(status: Int, value: Any?): WireReply =
            WireReply(status, "application/json; charset=utf-8", JsonSupport.stringify(value).encodeToByteArray())
        fun notFound(reason: String = "missing") = json(404, mapOf("error" to "not_found", "reason" to reason))
        fun badRequest(reason: String) = json(400, mapOf("error" to "bad_request", "reason" to reason))
        fun methodNotAllowed(m: String) = json(405, mapOf("error" to "method_not_allowed", "reason" to m))
    }
}

/**
 * CouchWireRouter — the CouchDB 1.6/1.7 HTTP shape over one [CouchDatabase], plus the two lanes
 * that collapse it onto the CAS: `_cas/{cid}` blocks and the IPFS `/api/v0/block/…` aliases.
 * commonMain: no sockets; the litebike HTTP worker hands (method, path, body) in and writes the
 * [WireReply] back. `feed=continuous|longpoll` is the JVM wire's job (it needs the connection).
 *
 *   GET    /                                      welcome {couchdb, version:"1.6.2"}
 *   GET    /{db}                                  info
 *   POST   /{db}                                  bare document put
 *   GET    /{db}/_all_docs[?startkey&endkey&limit&skip&descending&include_docs]   POST with {keys}
 *   GET    /{db}/_changes[?since&limit&include_docs]
 *   POST   /{db}/_revs_diff                       {id:[rev…]} → {id:{missing:[…]}}
 *   POST   /{db}/_bulk_docs                       {docs:[…], new_edits?}
 *   GET|PUT|DELETE /{db}/_local/{id}              checkpoints (never replicated)
 *   GET    /{db}/_cas/{cid}   POST /{db}/_cas     raw blocks; POST body is the block → {cid}
 *   POST   /{db}/_cas/_bulk {cids:[…]}             many blocks in one exchange (see [CasBulkCodec])
 *   GET    /{db}/_design/{d}/_rewrite/{path}      CouchApp: attachment bytes via the ddoc's rewrites
 *   GET|PUT|DELETE /{db}/{id…}                    document JSON (`_attachments` stubs rendered)
 *   GET    /{db}/{id…}/content                    attachment bytes of a path document
 *   GET    /api/v0/block/get?arg={cid}  POST /api/v0/block/put     IPFS-shaped aliases of _cas
 *   GET    /{anything}                            vhost: rewrite of the root ddoc → attachment bytes
 *
 * [attachmentPrefix] is the logical prefix rewrites resolve under (`projects/trikeshed/`).
 */
class CouchWireRouter(
    val db: CouchDatabase,
    val attachmentPrefix: String,
    /** P2 registry seam: null means eager route, preserving every existing caller. */
    val incrementalView: (ddoc: String, view: String) -> IncrementalViewElement? = { _, _ -> null },
) {
    suspend fun handle(method: String, rawPath: String, body: ByteArray): WireReply? {
        val m = method.uppercase()
        val path = rawPath.substringBefore('?')
        val query = CouchHttpSurface.parseQuery(rawPath.substringAfter('?', ""))
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }.map { CouchHttpSurface.percentDecode(it) }

        if (segments.isEmpty()) return if (m == "GET") vhost("/") ?: welcome() else WireReply.methodNotAllowed(m)
        if (segments[0] == "api" && segments.getOrNull(1) == "v0") return ipfs(m, segments.drop(2), query, body)
        if (segments[0] != db.name) return if (m == "GET") vhost(path) else null

        val rest = segments.drop(1)
        if (rest.isEmpty()) return when (m) {
            "GET" -> WireReply.json(200, db.info())
            "POST" -> {
                val doc = parseMap(body) ?: return WireReply.badRequest("invalid JSON body")
                val id = doc["_id"] as? String ?: borg.trikeshed.job.ContentId.of(body).hex
                val r = db.put(id, doc, doc["_rev"] as? String)
                WireReply.json(if (r["ok"] == true) 201 else 409, r)
            }
            else -> WireReply.methodNotAllowed(m)
        }

        return when (rest[0]) {
            "_all_docs" -> allDocs(m, query, body)
            "_changes" -> if (m != "GET") WireReply.methodNotAllowed(m) else WireReply.json(
                200,
                db.changes(
                    since = query["since"]?.toLongOrNull() ?: 0L,
                    limit = query["limit"]?.toIntOrNull() ?: Int.MAX_VALUE,
                    includeDocs = query["include_docs"] == "true",
                ),
            )
            "_revs_diff" -> {
                if (m != "POST") return WireReply.methodNotAllowed(m)
                val offered = parseMap(body) ?: return WireReply.badRequest("invalid JSON body")
                WireReply.json(200, db.revsDiff(offered.mapValues { (_, v) -> CouchDatabase.asList(v)?.map { it.toString() } ?: emptyList() }))
            }
            "_bulk_docs" -> {
                if (m != "POST") return WireReply.methodNotAllowed(m)
                val req = parseMap(body) ?: return WireReply.badRequest("invalid JSON body")
                @Suppress("UNCHECKED_CAST")
                val docs = CouchDatabase.asList(req["docs"])?.mapNotNull { it as? Map<String, Any?> } ?: return WireReply.badRequest("docs required")
                WireReply.json(201, db.bulkDocs(docs, newEdits = req["new_edits"] != false))
            }
            "_local" -> local(m, rest.drop(1).joinToString("/"), body)
            "_cas" -> cas(m, rest.getOrNull(1), body)
            "_design" -> design(m, rest, query, body)
            else -> document(m, rest, query, body)
        }
    }

    // ── pieces ────────────────────────────────────────────────────

    private fun welcome() = WireReply.json(200, mapOf("couchdb" to "Welcome", "version" to CouchHttpSurface.COUCH_VERSION, "vendor" to mapOf("name" to "TrikeShed", "version" to "oroboros")))

    private fun allDocs(m: String, query: Map<String, String>, body: ByteArray): WireReply {
        val keys = if (m == "POST") CouchDatabase.asList(parseMap(body)?.get("keys"))?.map { it.toString() } else null
        if (m != "GET" && m != "POST") return WireReply.methodNotAllowed(m)
        return WireReply.json(
            200,
            db.allDocs(
                startkey = query["startkey"]?.let(::unquote),
                endkey = query["endkey"]?.let(::unquote),
                limit = query["limit"]?.toIntOrNull() ?: Int.MAX_VALUE,
                skip = query["skip"]?.toIntOrNull() ?: 0,
                descending = query["descending"] == "true",
                includeDocs = query["include_docs"] == "true",
                keys = keys,
            ),
        )
    }

    private fun local(m: String, id: String, body: ByteArray): WireReply {
        if (id.isEmpty()) return WireReply.badRequest("_local id required")
        return when (m) {
            "GET" -> db.localGet(id)?.let { WireReply.json(200, it) } ?: WireReply.notFound()
            "PUT" -> WireReply.json(201, db.localPut(id, parseMap(body) ?: emptyMap()))
            "DELETE" -> db.localDelete(id).let { WireReply.json(if (it["ok"] == true) 200 else 404, it) }
            else -> WireReply.methodNotAllowed(m)
        }
    }

    private fun cas(m: String, cid: String?, body: ByteArray): WireReply = when {
        m == "POST" && cid == "_bulk" -> {
            val want = CouchDatabase.asList(parseMap(body)?.get("cids"))?.map { it.toString() } ?: return WireReply.badRequest("cids required")
            // Cap one reply by BYTES, not block count: jar-sized blobs make a 64-block reply tens of
            // megabytes. Omitted blocks are re-requested (bulk again or singles) by the reader.
            var budget = 8 * 1024 * 1024
            val blocks = mutableListOf<Pair<String, ByteArray>>()
            for (c in want) {
                val b = db.blockGet(c) ?: continue
                if (blocks.isNotEmpty() && b.size > budget) break
                blocks += c to b
                budget -= b.size
                if (budget <= 0) break
            }
            WireReply(200, CasBulkCodec.CONTENT_TYPE, CasBulkCodec.encode(blocks))
        }
        m == "GET" && cid != null -> db.blockGet(cid)?.let { WireReply(200, "application/octet-stream", it) } ?: WireReply.notFound("no such block")
        m == "POST" && cid == null -> db.blockPut(body).let { WireReply.json(201, mapOf("ok" to true, "cid" to it.value, "size" to body.size)) }
        else -> WireReply.methodNotAllowed(m)
    }

    /** `/api/v0/block/get?arg=` and `/api/v0/block/put` — the shape HtxIpfsAdapter already speaks. */
    private fun ipfs(m: String, rest: List<String>, query: Map<String, String>, body: ByteArray): WireReply? {
        if (rest.getOrNull(0) != "block") return null
        return when (rest.getOrNull(1)) {
            "get" -> query["arg"]?.let { db.blockGet(it) }?.let { WireReply(200, "application/octet-stream", it) } ?: WireReply.notFound("no such block")
            "put" -> if (m != "POST") WireReply.methodNotAllowed(m) else db.blockPut(body).let { WireReply.json(200, mapOf("Key" to it.value, "Size" to body.size)) }
            else -> null
        }
    }

    private fun design(m: String, rest: List<String>, query: Map<String, String>, body: ByteArray): WireReply {
        if (rest.size < 2) return WireReply.notFound()
        val id = "_design/${rest[1]}"
        val tail = rest.drop(2)
        if (tail.isEmpty()) return document(m, listOf("_design", rest[1]), query, body)
        if (tail[0] == "_rewrite") {
            if (m != "GET") return WireReply.methodNotAllowed(m)
            return vhost("/" + tail.drop(1).joinToString("/")) ?: WireReply.notFound("no rewrite matched")
        }
        if (tail[0] == "_view") {
            if (m != "GET") return WireReply.methodNotAllowed(m)
            if (tail.size != 2) return WireReply.notFound("missing_named_view")
            val viewName = tail[1]
            val incremental = incrementalView(id, viewName)
            if (incremental != null && designMarksIncremental(id, viewName)) {
                // Registry element owns map/reduce/checkpoint state. Delivery-time windowing
                // remains route work; no eager docs.all() scan occurs here.
                val result = incremental.answer()
                val skip = query["skip"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = query["limit"]?.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
                val rows = ArrayList<Map<String, Any?>>()
                var seen = 0
                for (row in result.rows) {
                    if (seen++ < skip) continue
                    if (rows.size >= limit) break
                    rows.add(mapOf("id" to row.docId, "key" to row.key, "value" to row.value))
                }
                return WireReply.json(200, mapOf(
                    "total_rows" to result.size, "offset" to skip.coerceAtMost(result.size), "rows" to rows,
                    "update_seq" to db.updateSeq, "incremental" to true,
                ))
            }
            val r = viewRoute.handle(id, viewName, query)
            return WireReply.json(r.status, r.json)
        }
        return WireReply.notFound("unsupported design handler ${tail[0]} on $id")
    }

    /** A design doc opts in explicitly: views.<name>.incremental == true. */
    private fun designMarksIncremental(ddoc: String, view: String): Boolean {
        val doc = db.docJson(ddoc) ?: return false
        val views = doc["views"] as? Map<*, *> ?: return false
        val spec = views[view] as? Map<*, *> ?: return false
        return spec["incremental"] == true || spec["incremental"]?.toString() == "true"
    }

    /** The `_view` route core mounted over this database — the same engine `CouchHttpSurface` serves. */
    private val viewRoute: borg.trikeshed.utils.rfxhttp.ViewRoute by lazy {
        val docs = object : borg.trikeshed.utils.rfxhttp.ViewDocs {
            override fun all(): List<Pair<String, Map<String, Any?>>> =
                db.store.all().filter { !db.isTombstone(it) && !it.id.startsWith("_design/") }
                    .map { it.id to db.render(it, db.store.head.getRev(it.id)).filterKeys { k -> k != "_id" && k != "_rev" } }

            override fun body(id: String): Map<String, Any?>? = db.docJson(id)?.filterKeys { k -> k != "_id" && k != "_rev" }

            override fun couchDoc(id: String): Map<String, Any?>? = db.docJson(id)
        }
        borg.trikeshed.utils.rfxhttp.ViewRoute(docs)
    }

    private fun document(m: String, rest: List<String>, query: Map<String, String>, body: ByteArray): WireReply {
        val id = rest.joinToString("/")
        return when (m) {
            "GET" -> db.docJson(id)?.let { WireReply.json(200, it) }
                ?: attachmentReply(id.removeSuffix("/content").takeIf { rest.size >= 2 && rest.last() == "content" })
                ?: WireReply.notFound()
            "PUT" -> {
                val doc = parseMap(body) ?: return WireReply.badRequest("Document must be a JSON object")
                val r = db.put(id, doc, query["rev"] ?: doc["_rev"] as? String)
                WireReply.json(if (r["ok"] == true) 201 else 409, r)
            }
            "DELETE" -> db.delete(id, query["rev"]).let { WireReply.json(if (it["ok"] == true) 200 else if (it["error"] == "not_found") 404 else 409, it) }
            else -> WireReply.methodNotAllowed(m)
        }
    }

    private fun attachmentReply(id: String?): WireReply? {
        val (ct, bytes) = db.attachment(id ?: return null) ?: return null
        return WireReply(200, ct.ifEmpty { ContentTypes.forPath(id) }, bytes)
    }

    /** Root vhost: the ddoc's rewrites map a request path to an attachment under [attachmentPrefix]. */
    fun vhost(path: String): WireReply? {
        val target = db.rewrite(path, attachmentPrefix) ?: return null
        return attachmentReply(target)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMap(body: ByteArray): Map<String, Any?>? =
        runCatching { JsonSupport.parse(body.decodeToString()) as? Map<String, Any?> }.getOrNull()

    private fun unquote(s: String): String = if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
}

/**
 * Wire framing for `_cas/_bulk`: a sequence of `<cid>\n<length>\n<bytes>` frames. Blocks the node
 * does not hold are simply absent; the reader verifies every block against its cid anyway.
 */
object CasBulkCodec {
    const val CONTENT_TYPE = "application/x-trikeshed-cas-bulk"

    fun encode(blocks: List<Pair<String, ByteArray>>): ByteArray {
        var total = 0
        val heads = blocks.map { (cid, bytes) -> "$cid\n${bytes.size}\n".encodeToByteArray().also { total += it.size + bytes.size } }
        val out = ByteArray(total)
        var pos = 0
        for (i in blocks.indices) {
            heads[i].copyInto(out, pos); pos += heads[i].size
            blocks[i].second.copyInto(out, pos); pos += blocks[i].second.size
        }
        return out
    }

    fun decode(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var pos = 0
        fun line(): String? {
            val start = pos
            while (pos < bytes.size && bytes[pos] != '\n'.code.toByte()) pos++
            if (pos >= bytes.size) return null
            return bytes.decodeToString(start, pos).also { pos++ }
        }
        while (pos < bytes.size) {
            val cid = line() ?: break
            val len = line()?.toIntOrNull() ?: break
            if (pos + len > bytes.size) break
            out += cid to bytes.copyOfRange(pos, pos + len)
            pos += len
        }
        return out
    }
}
