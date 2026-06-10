@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

// ─────────────────────────────────────────────────────────────────────────────
// VIEWSTORE — CouchDB-style map/reduce views
// ─────────────────────────────────────────────────────────────────────────────

data class ViewEmit<K, V>(val key: K, val value: V)

fun interface MapFun<Doc, K, V> {
    fun map(doc: Doc, emit: (K, V) -> Unit)
}

fun interface ReduceFun<K, V, R> {
    fun reduce(key: K, values: Series<V>): R
}

data class ViewDef<Doc, K, V, R>(
    val name: String,
    val map: MapFun<Doc, K, V>,
    val reduce: ReduceFun<K, V, R>? = null,
)

data class ViewRow<K, V>(val key: K, val value: V, val id: String, val rev: String)

data class ViewResult<K, V, R>(
    val rows: Series<ViewRow<K, V>>,
    val total: R? = null,
    val count: Int = rows.size,
)

class ViewIndex<K, V, R>(val view: ViewDef<*, K, V, R>) {
    private val byKey = mutableMapOf<K, MutableList<ViewRow<K, V>>>()
    private val allRows = mutableListOf<ViewRow<K, V>>()
    private var reduced: R? = null
    private var isReducedDirty = true

    fun index(doc: ConfixDocStoreEntry) {
        isReducedDirty = true
        val emits = mutableListOf<ViewEmit<K, V>>()
        @Suppress("UNCHECKED_CAST")
        (view.map as MapFun<ConfixDocStoreEntry, K, V>).map(doc) { k, v ->
            emits.add(ViewEmit(k, v))
        }
        for (emit in emits) {
            val row = ViewRow(emit.key, emit.value, doc.id, doc.rev)
            allRows.add(row)
            byKey.getOrPut(emit.key) { mutableListOf() }.add(row)
        }
    }

    fun reindex(docs: Series<ConfixDocStoreEntry>) {
        clear()
        for (i in 0 until docs.size) index(docs[i])
    }

    fun clear() {
        byKey.clear()
        allRows.clear()
        reduced = null
        isReducedDirty = true
    }

    fun all(): ViewResult<K, V, R> = ViewResult(
        allRows.size j { allRows[it] },
        computeReduced(),
    )

    fun forKey(key: K): ViewResult<K, V, R> {
        val rows = byKey[key] ?: emptyList()
        return ViewResult(rows.size j { rows[it] }, computeReduced())
    }

    @Suppress("UNCHECKED_CAST")
    fun forKeyPrefix(prefix: String): ViewResult<K, V, R> {
        val matches = byKey.entries.filter { (k, _) ->
            k is String && k.startsWith(prefix)
        }.flatMap { it.value }
        return ViewResult(matches.size j { matches[it] }, computeReduced())
    }

    fun startsWith(prefix: String): ViewResult<K, V, R> = forKeyPrefix(prefix)

    val size: Int get() = allRows.size
    val keyCount: Int get() = byKey.size

    private fun computeReduced(): R? {
        if (isReducedDirty) {
            val red = view.reduce
            if (red != null && byKey.isNotEmpty()) {
                val aggByKey = mutableMapOf<K, MutableList<V>>()
                for ((k, rows) in byKey) {
                    aggByKey.getOrPut(k) { mutableListOf() }.apply {
                        for (row in rows) add(row.value)
                    }
                }
                @Suppress("UNCHECKED_CAST")
                val redFn = red as ReduceFun<K, V, R>
                val results = aggByKey.entries.map { (k, vs) ->
                    redFn.reduce(k, vs.size j { vs[it] })
                }
                reduced = if (results.size == 1) results[0] else null
            }
            isReducedDirty = false
        }
        return reduced
    }
}

class ViewStore(val store: ConfixDocStore) {
    val views = mutableMapOf<String, ViewIndex<*, *, *>>()

    @Suppress("UNCHECKED_CAST")
    fun define(
        name: String,
        map: (ConfixDocStoreEntry, (String, String) -> Unit) -> Unit,
        reduce: ((String, Series<String>) -> Int)? = null,
    ): ViewIndex<String, String, Int> {
        val def = ViewDef(name, MapFun { doc, emit -> map(doc, emit) },
            reduce?.let { r -> ReduceFun { k, v -> r(k, v) } })
        val index = ViewIndex(def as ViewDef<*, String, String, Int>)
        views[name] = index
        return index
    }

    operator fun get(name: String): ViewIndex<*, *, *>? = views[name]
    fun contains(name: String): Boolean = views.containsKey(name)
    val size: Int get() = views.size

    fun reindex() {
        val docs = store.entries
        for ((_, index) in views) {
            @Suppress("UNCHECKED_CAST")
            (index as ViewIndex<*, *, *>).reindex(docs)
        }
    }

    fun index(entry: ConfixDocStoreEntry) {
        for ((_, index) in views) {
            @Suppress("UNCHECKED_CAST")
            (index as ViewIndex<*, *, *>).index(entry)
        }
    }

    fun defineTaxonomyViews(): ViewStore {
        define(
            "_design/taxonomy/_view/by_owner",
            map = { doc, emit ->
                val owner = doc.doc.value("ownerType") as? String ?: return@define
                emit(owner, doc.id)
            },
            reduce = { _, values -> values.size },
        )

        define(
            "_design/taxonomy/_view/by_kind",
            map = { doc, emit ->
                val kind = doc.doc.value("pointcutKind") as? Int ?: return@define
                emit(kind.toString(), doc.id)
            },
            reduce = { _, values -> values.size },
        )

        define(
            "_design/taxonomy/_view/by_pool",
            map = { doc, emit ->
                val poolId = doc.doc.value("poolId") as? Int ?: return@define
                emit(poolId.toString(), doc.id)
            },
            reduce = { _, values -> values.size },
        )

        define(
            "_design/taxonomy/_view/all",
            map = { doc, emit -> emit(doc.id, doc.rev) },
            reduce = { _, values -> values.size },
        )

        return this
    }

    override fun toString(): String = "ViewStore(views=${views.keys.joinToString()})"
}

object ViewStoreFactory {
    fun create(store: ConfixDocStore): ViewStore = ViewStore(store)
    fun createTaxonomy(store: ConfixDocStore): ViewStore =
        ViewStore(store).defineTaxonomyViews().also { it.reindex() }
}
