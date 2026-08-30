package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.CouchCascade
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.ReduceFunction
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * The documents a [ViewRoute] serves over: live (non-tombstone, non-design) documents as
 * JSON bodies, plus single-doc lookup for `include_docs` and `value: "doc"` rendering.
 * Both [CouchHttpSurface]'s ConfixDocStore and the daemon's CouchDatabase mount through this.
 */
interface ViewDocs {
    /** Every live non-design document as `(id, body)`, body without `_id`/`_rev`. */
    fun all(): List<Pair<String, Map<String, Any?>>>

    /** The live document body (without `_id`/`_rev`), or null when absent/deleted. */
    fun body(id: String): Map<String, Any?>?

    /** The live document as its full Couch shape (`_id`/`_rev` included) for `include_docs` / `value:"doc"`. */
    fun couchDoc(id: String): Map<String, Any?>?

    companion object {
        /** Adapter over a `ConfixDocStore` — what [CouchHttpSurface] serves. */
        fun of(store: borg.trikeshed.couch.ConfixDocStore): ViewDocs = object : ViewDocs {
            override fun all(): List<Pair<String, Map<String, Any?>>> {
                val entries = store.byIdPrefix("")
                return List(entries.size) { i: Int ->
                    val e = entries[i]
                    if (e.id.startsWith("_design/")) null else e.id to e.jsonBody()
                }.filterNotNull()
            }

            override fun body(id: String): Map<String, Any?>? = store[id]?.jsonBody()

            override fun couchDoc(id: String): Map<String, Any?>? {
                val e = store[id] ?: return null
                return linkedMapOf<String, Any?>("_id" to e.id, "_rev" to e.rev) + e.jsonBody()
            }
        }

        /**
         * Adapter over the daemon's [borg.trikeshed.couch.CouchDatabase] — the same projection
         * `CouchWireRouter` mounts `_view` on, so a view answers identically whether it is asked
         * for over `GET _design/…/_view/…` or inside a RequestFactory `query` operation.
         */
        fun of(db: borg.trikeshed.couch.CouchDatabase): ViewDocs = object : ViewDocs {
            override fun all(): List<Pair<String, Map<String, Any?>>> =
                db.store.all().filter { !db.isTombstone(it) && !it.id.startsWith("_design/") }
                    .map { it.id to db.render(it, db.store.head.getRev(it.id)).filterKeys { k -> k != "_id" && k != "_rev" } }

            override fun body(id: String): Map<String, Any?>? = db.docJson(id)?.filterKeys { k -> k != "_id" && k != "_rev" }

            override fun couchDoc(id: String): Map<String, Any?>? = db.docJson(id)
        }
    }
}

/**
 * The design-doc `_view` route core shared by [CouchHttpSurface] (`GET /{db}/_design/{d}/_view/{v}`)
 * and [borg.trikeshed.couch.CouchWireRouter] (the daemon's mount of the same route). Extracted so
 * the finished view semantics — RequestFactory view-spec shape, 1.6.2 params, Couch collation,
 * `_count`/`_sum`/`_stats`, group/group_level, rereduce — exist exactly once.
 */
class ViewRoute(
    private val docs: ViewDocs,
    private val viewServer: ViewServer = ViewServer(),
) {

    /** One view answer in wire-neutral shape: status plus the JSON body map. */
    data class ViewReply(val status: Int, val json: Map<String, Any?>)

    /** `GET /{db}/_design/{d}/_view/{v}?…` — the query-string dialect. */
    fun handle(ddoc: String, viewName: String, query: Map<String, String>): ViewReply =
        handle(ddoc, viewName, ViewQuery.fromQueryString(query))

    /**
     * The same stored view, asked for with parameters that are already decoded — the RequestFactory
     * envelope's `view` operation. Both entry points run this one body, so a stored view answers
     * identically whether it was reached over the route or inside a batch; only the parameter
     * dialect differs, which is what [ViewQuery] exists to absorb.
     */
    fun handle(ddoc: String, viewName: String, q: ViewQuery): ViewReply {
        val design = docs.body(ddoc) ?: return ViewReply(404, err("not_found", "missing"))
        val spec = (design["views"] as? Map<*, *>)?.get(viewName) as? Map<*, *>
            ?: return ViewReply(404, err("not_found", "missing_named_view"))
        val map = spec["map"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val reduceFn = reduceFn(spec["reduce"])
        val wantReduce = q.wantReduce(reduceFn != null)
        if (wantReduce && reduceFn == null) return ViewReply(400, err("query_parse_error", "Reduce is invalid for map-only views."))

        val def = ViewDefinition(
            ddoc = ddoc,
            viewName = viewName,
            mapFn = MapFunction.Emit(keyExpr(map["key"]), valueExpr(map["value"])),
            reduceFn = if (wantReduce) reduceFn else null,
        )
        val mapped = viewServer.execute(def.copy(reduceFn = null), documents())
        val selected = q.select(mapped)

        if (!wantReduce) {
            return ViewReply(
                200,
                mapOf(
                    "total_rows" to mapped.size,
                    "offset" to q.offset(selected),
                    "rows" to q.page(selected).map { row ->
                        // emit(key, doc) in CouchDB yields the object; ViewServer's DocValue is a string.
                        val value = if (map["value"] == "doc") docs.couchDoc(row.docId) else row.value
                        val base = mapOf("id" to row.docId, "key" to row.key, "value" to value)
                        if (q.include_docs) base + ("doc" to docs.couchDoc(row.docId)) else base
                    },
                ),
            )
        }

        val reduced = viewServer.execute(def, selected.mapNotNull { row -> docs.body(row.docId)?.let { toDocument(row.docId, it) } }.distinctBy { it.id })
        val rows: List<Map<String, Any?>> = if (q.grouped) {
            reduced.rows.toKList().map { mapOf("key" to it.key, "value" to it.value) }
        } else {
            listOf(mapOf("key" to null, "value" to ViewQuery.rereduce(reduceFn!!, reduced)))
        }
        return ViewReply(200, mapOf("rows" to rows))
    }

    // ── store projection ──────────────────────────────────────────

    private fun documents(): List<Document> =
        docs.all().map { (id, body) -> toDocument(id, body) }

    private fun toDocument(id: String, body: Map<String, Any?>): Document =
        Document(
            id,
            body.entries
                .filter { it.key != "_id" && it.key != "_rev" }
                .map { Field(it.key.toString(), it.value ?: "null") },
        )

    // ── spec helpers ──────────────────────────────────────────────

    private fun err(error: String, reason: String): Map<String, Any?> =
        mapOf("error" to error, "reason" to reason)

    private fun <T> borg.trikeshed.lib.Series<T>.toKList(): List<T> = List(size) { i -> this[i] }
}

// ── the view-spec dialect ─────────────────────────────────────────
// Shared by [ViewRoute] (spec read from a design doc) and [CouchRequestFactory]'s `query`
// operation (spec inline in the envelope). One dialect, so the same view answers the same way
// whichever of the two asks for it.

internal fun keyExpr(spec: Any?): KeyExpr = when (spec) {
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

internal fun valueExpr(spec: Any?): ValueExpr = when (spec) {
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

/**
 * The reducer options a view may name:
 *   "_count" | "_sum" | "_stats" | "rollup-count"   the builtins
 *   "_cascade"                                       the confix cascade over [CouchCascade.METRICS]
 *   {"cascade": {"metrics": ["cpu_mhz", …]}}         the cascade narrowed to named fields
 *   {"cascade": true}                                the cascade at its defaults
 *   {"dsl": "<confix reducer>"}                      a custom Confix DSL reducer
 */
internal fun reduceFn(spec: Any?): ReduceFunction? = when (spec) {
    null -> null
    "_cascade" -> ReduceFunction.Cascade()
    is String -> ReduceFunction.Builtin(spec)
    is Map<*, *> -> when {
        spec.containsKey("cascade") -> {
            val cfg = spec["cascade"]
            val metrics = (cfg as? Map<*, *>)?.get("metrics")
                ?.let { borg.trikeshed.couch.CouchDatabase.asList(it) }
                ?.map { it.toString() }
                ?.takeIf { it.isNotEmpty() }
            ReduceFunction.Cascade(metrics ?: CouchCascade.METRICS)
        }
        else -> (spec["dsl"] as? String)?.let(ReduceFunction::Custom)
    }
    else -> null
}
