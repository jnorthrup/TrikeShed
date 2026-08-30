package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.CouchCascade
import borg.trikeshed.couch.ReduceFunction
import borg.trikeshed.couch.ViewResult
import borg.trikeshed.couch.ViewRow
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * ViewQuery — the one CouchDB 1.6.2 view-parameter dialect, shared by the HTTP surface
 * (`GET /{db}/_design/{d}/_view/{v}?…`) and the RequestFactory envelope (`{"op":"query","view":{…}}`).
 *
 * Absent keys are [Unset] (a JSON `null` is a legal key), so `key == Unset` ≠ `key == null`.
 * `reduce == null` means "not specified": reduce iff the view declares a reducer.
 */
data class ViewQuery(
    val key: Any? = Unset,
    val startkey: Any? = Unset,
    val endkey: Any? = Unset,
    val inclusive_end: Boolean = true,
    val descending: Boolean = false,
    val skip: Int = 0,
    val limit: Int = Int.MAX_VALUE,
    val reduce: Boolean? = null,
    val group: Boolean = false,
    val group_level: Int = 0,
    val include_docs: Boolean = false,
) {
    /** Sentinel for "parameter not given". */
    object Unset { override fun toString() = "Unset" }

    /** group=true or group_level>0 ⇒ grouped reduce rows; otherwise one rereduced row. */
    val grouped: Boolean get() = group || group_level > 0

    /** reduce requested, given whether the view has a reducer at all. */
    fun wantReduce(hasReducer: Boolean): Boolean = reduce ?: hasReducer

    /** key / startkey / endkey / inclusive_end / descending over mapped rows, sorted by Couch collation. */
    fun select(mapped: ViewResult): List<ViewRow> {
        val rows = mapped.rows.toKList().sortedWith { a, b -> CouchHttpSurface.collate(a.key, b.key) }
        val filtered = rows.filter { row ->
            if (key !== Unset) CouchHttpSurface.collate(row.key, key) == 0
            else {
                // With descending=true CouchDB swaps the roles of startkey/endkey.
                val lo = if (descending) endkey else startkey
                val hi = if (descending) startkey else endkey
                val loOk = lo === Unset || CouchHttpSurface.collate(row.key, lo) >= 0
                val hiOk = hi === Unset ||
                    (if (inclusive_end) CouchHttpSurface.collate(row.key, hi) <= 0 else CouchHttpSurface.collate(row.key, hi) < 0)
                loOk && hiOk
            }
        }
        return if (descending) filtered.asReversed() else filtered
    }

    /** skip / limit over selected rows. */
    fun page(selected: List<ViewRow>): List<ViewRow> = selected.drop(skip).take(limit)

    /** 1.6.2 `offset`: rows skipped, clamped to what was there. */
    fun offset(selected: List<ViewRow>): Int = minOf(skip, selected.size)

    companion object {
        /** `?key=%22x%22&startkey=4&…` — key/startkey/endkey are JSON-encoded; booleans/ints as text. */
        fun fromQueryString(q: Map<String, String>): ViewQuery = ViewQuery(
            key = q["key"]?.let(JsonSupport::parse) ?: if (q.containsKey("key")) null else Unset,
            startkey = q["startkey"]?.let(JsonSupport::parse) ?: if (q.containsKey("startkey")) null else Unset,
            endkey = q["endkey"]?.let(JsonSupport::parse) ?: if (q.containsKey("endkey")) null else Unset,
            inclusive_end = q["inclusive_end"]?.toBoolean() ?: true,
            descending = q["descending"]?.toBoolean() == true,
            skip = q["skip"]?.toIntOrNull() ?: 0,
            limit = q["limit"]?.toIntOrNull() ?: Int.MAX_VALUE,
            reduce = q["reduce"]?.toBoolean(),
            group = q["group"]?.toBoolean() == true,
            group_level = q["group_level"]?.toIntOrNull() ?: 0,
            include_docs = q["include_docs"]?.toBoolean() == true,
        )

        /**
         * The envelope `view` map. Its top-level `key`/`value`/`reduce` are the map/reduce *spec*
         * (field names, reducer), so query parameters live beside them already-decoded:
         * `startkey`, `endkey`, `inclusive_end`, `descending`, `skip`, `limit`, `group`, `group_level`,
         * `include_docs`, and a boolean `reduce`. An exact-key match, which would collide with the map
         * key spec, goes under `params` — `"params": {"key": …}` — which may also carry any of the above.
         * Unlike the query string, `group` defaults to true here (the envelope's historical row shape).
         */
        fun fromEnvelope(view: Map<*, *>): ViewQuery {
            val params = view["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            fun has(n: String) = params.containsKey(n) || (n != "key" && view.containsKey(n))
            fun at(n: String): Any? = if (params.containsKey(n)) params[n] else view[n]
            fun bool(n: String): Boolean? = at(n).let { it as? Boolean ?: (it as? String)?.toBooleanStrictOrNull() }
            fun int(n: String): Int? = at(n).let { (it as? Number)?.toInt() ?: (it as? String)?.toIntOrNull() }
            return ViewQuery(
                key = if (params.containsKey("key")) params["key"] else Unset,
                startkey = if (has("startkey")) at("startkey") else Unset,
                endkey = if (has("endkey")) at("endkey") else Unset,
                inclusive_end = bool("inclusive_end") ?: true,
                descending = bool("descending") == true,
                skip = int("skip") ?: 0,
                limit = int("limit") ?: Int.MAX_VALUE,
                reduce = bool("reduce"),
                // Envelope receipts have always been the per-key reduction; group=false folds to one row.
                group = bool("group") ?: true,
                group_level = int("group_level") ?: 0,
                include_docs = bool("include_docs") == true,
            )
        }

        /**
         * group=false: fold the per-key reduction into one value, the way CouchDB rereduces.
         *
         * A reducer with no case here falls through to the raw partial list, which is not a
         * rereduction — it is the ungrouped rows wearing a reduced view's clothes. That was the
         * behaviour for the two cascade-shaped reducers, so `group=false` on them reported a list
         * of partials instead of a total; both now fold properly through [CouchCascade.combine]
         * and the `rollup-count` case below.
         */
        fun rereduce(fn: ReduceFunction, grouped: ViewResult): Any? {
            val values = grouped.rows.toKList().map { it.value }
            if (fn is ReduceFunction.Cascade) return CouchCascade.combine(values, fn.metrics)
            return when ((fn as? ReduceFunction.Builtin)?.name) {
                "_count", "_sum" -> values.sumOf { (it as? Number)?.toDouble() ?: 0.0 }.let { if (it == it.toLong().toDouble()) it.toLong() else it }
                "_stats" -> {
                    val stats = values.mapNotNull { it as? Map<*, *> }
                    mapOf(
                        "sum" to stats.sumOf { (it["sum"] as? Number)?.toDouble() ?: 0.0 },
                        "count" to stats.sumOf { (it["count"] as? Number)?.toLong() ?: 0L },
                        "min" to stats.mapNotNull { (it["min"] as? Number)?.toDouble() }.minOrNull(),
                        "max" to stats.mapNotNull { (it["max"] as? Number)?.toDouble() }.maxOrNull(),
                        "sumsqr" to stats.sumOf { (it["sumsqr"] as? Number ?: it["sumSqr"] as? Number)?.toDouble() ?: 0.0 },
                    )
                }
                // The bounded `[sum, count]` cascade shape: fold both columns, keep the shape.
                "rollup-count" -> {
                    val parts = values.mapNotNull { it as? List<*> }
                    listOf(
                        parts.sumOf { (it.getOrNull(0) as? Number)?.toDouble() ?: 0.0 },
                        parts.sumOf { (it.getOrNull(1) as? Number)?.toLong() ?: 0L },
                    )
                }
                else -> values
            }
        }

        private fun <T> borg.trikeshed.lib.Series<T>.toKList(): List<T> = List(size) { i -> this[i] }
    }
}
