@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections.multiindex

import borg.trikeshed.collections.LinearHashMap
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * MultiIndexContainer — a single element store simultaneously accessible via
 * multiple index projections expressed as MultiIndexK GADT keys.
 *
 * Backing:
 *   elements  — ArrayList (positional, insertion-order)
 *   hashIndex — LinearHashMap<K, Int> per ByHash key (position lookup)
 *   sortIndex — sorted IntArray per ByOrder/ByRange key (rebuilt on mutation)
 *
 * Access via [facet]:
 *
 *   val c = MultiIndexContainer<Person>()
 *   c.add(Person("Alice", 30))
 *   c.add(Person("Bob",   25))
 *
 *   val byName  = c.facet(MultiIndexK.ByHash  { (it as Person).name })
 *   val byAge   = c.facet(MultiIndexK.ByOrder { (it as Person).age  })
 *   val inOrder = c.facet(MultiIndexK.BySequence)
 *
 *   val pos: Int?        = byName("Alice")
 *   val ageSorted: Series<Int> = byAge
 *   val range: Series<Int>     = c.facet(MultiIndexK.ByRange { (it as Person).age })(25, 30)
 *
 * Complexity:
 *   add      — O(1) amortised hash insert; O(n log n) per sort-index present
 *   ByHash   — O(1) amortised
 *   ByOrder  — O(1) (pre-sorted Series view)
 *   ByRange  — O(log n) binary-search endpoints + O(k) slice
 *   BySequence — O(1)
 *   Elements — O(1)
 */
class MultiIndexContainer<E : Any> {

    // ── element store ────────────────────────────────────────────────────────
    private val store = ArrayList<E>()

    // ── live hash indexes: extractor identity → LinearHashMap<K, Int> ───────
    // Key: the extractor lambda instance (identity equality, not equals).
    // We use its hashCode/identity as a proxy key in a plain array list —
    // number of hash indexes is tiny in practice so linear scan is fine.
    private data class HashEntry(
        val extractor: (Any?) -> Any?,
        val map: LinearHashMap<Any, Int>,
    )
    private val hashIndexes = ArrayList<HashEntry>()

    // ── live sort indexes ────────────────────────────────────────────────────
    private data class SortEntry(
        val extractor: (Any?) -> Comparable<Any?>,
        val positions: IntArray,      // positions into store[], sorted by key
    )
    private val sortIndexes = ArrayList<SortEntry>()

    // ── public API ───────────────────────────────────────────────────────────

    fun add(element: E): Int {
        val pos = store.size
        store += element
        // update hash indexes
        for (he in hashIndexes) {
            val k = he.extractor(element) ?: continue
            he.map[k] = pos
        }
        // rebuild sort indexes (small cost, typically few sort indexes)
        rebuildSortIndexes()
        return pos
    }

    /** Returns the element at backing-store position [pos]. */
    operator fun get(pos: Int): E = store[pos]

    val size: Int get() = store.size

    /**
     * Facet dispatch: obtain the accessor for a given MultiIndexK key.
     * Lazily initialises the underlying index on first call for a given key.
     */
    @Suppress("UNCHECKED_CAST")
    fun <R> facet(key: MultiIndexK<R>): R = when (key) {

        is MultiIndexK.ByHash<*> -> {
            val he = hashIndexes.firstOrNull { it.extractor === key.extractor }
                ?: buildHashIndex(key.extractor).also { hashIndexes += it }
            val map = he.map
            val fn: (Any?) -> Int? = { k -> if (k == null) null else map.get(k) }
            fn as R
        }

        is MultiIndexK.ByOrder<*> -> {
            val ext = key.extractor as (Any?) -> Comparable<Any?>
            val se  = sortIndexes.firstOrNull { it.extractor === ext }
                ?: buildSortIndex(ext).also { sortIndexes += it }
            val arr = se.positions
            (arr.size j { i: Int -> arr[i] }) as R
        }

        is MultiIndexK.ByRange<*> -> {
            val ext = key.extractor as (Any?) -> Comparable<Any?>
            // ensure sort index exists
            sortIndexes.firstOrNull { it.extractor === ext }
                ?: buildSortIndex(ext).also { sortIndexes += it }

            val fn: (Any?, Any?) -> Series<Int> = { lo, hi ->
                val se2 = sortIndexes.first { it.extractor === ext }
                val arr = se2.positions
                // binary search bounds
                val from = lowerBound(arr, lo, ext)
                val to   = upperBound(arr, hi, ext)
                val len  = maxOf(0, to - from)
                len j { i: Int -> arr[from + i] }
            }
            fn as R
        }

        MultiIndexK.BySequence -> {
            (store.size j { i: Int -> i }) as R
        }

        MultiIndexK.Elements -> {
            (store.size j { i: Int -> store[i] as Any? }) as R
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun buildHashIndex(extractor: (Any?) -> Any?): HashEntry {
        val map = LinearHashMap<Any, Int>(store.size.coerceAtLeast(16))
        for (pos in store.indices) {
            val k = extractor(store[pos]) ?: continue
            map[k] = pos
        }
        return HashEntry(extractor, map)
    }

    private fun buildSortIndex(extractor: (Any?) -> Comparable<Any?>): SortEntry {
        val positions = store.indices.sortedWith(Comparator { a, b ->
            val ka = extractor(store[a])
            val kb = extractor(store[b])
            compareValues(ka, kb)
        }).toIntArray()
        return SortEntry(extractor, positions)
    }

    private fun rebuildSortIndexes() {
        for (i in sortIndexes.indices) {
            val se = sortIndexes[i]
            sortIndexes[i] = buildSortIndex(se.extractor)
        }
    }

    // ── overloaded add with explicit hash-index registration ─────────────────

    /**
     * Register a ByHash key so its index is maintained incrementally on add().
     * Call before adding elements for best performance; calling after triggers
     * a full index build.
     */
    fun <K : Any> registerHash(key: MultiIndexK.ByHash<K>) {
        if (hashIndexes.none { it.extractor === key.extractor })
            hashIndexes += buildHashIndex(key.extractor)
    }

    /**
     * Register a ByOrder key so its sort index is maintained on add().
     */
    fun <K : Comparable<K>> registerOrder(key: MultiIndexK.ByOrder<K>) {
        val ext = key.extractor as (Any?) -> Comparable<Any?>
        if (sortIndexes.none { it.extractor === ext })
            sortIndexes += buildSortIndex(ext)
    }

    /** Register a ByRange key so its sorted projection is maintained on add(). */
    fun <K : Comparable<K>> registerOrder(key: MultiIndexK.ByRange<K>) {
        val ext = key.extractor as (Any?) -> Comparable<Any?>
        if (sortIndexes.none { it.extractor === ext })
            sortIndexes += buildSortIndex(ext)
    }

    // ── binary search helpers ────────────────────────────────────────────────

    private fun lowerBound(arr: IntArray, lo: Any?, ext: (Any?) -> Comparable<Any?>): Int {
        if (lo == null) return 0
        var l = 0; var r = arr.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (compareValues(ext(store[arr[m]]), lo as Comparable<Any?>) < 0) l = m + 1 else r = m
        }
        return l
    }

    private fun upperBound(arr: IntArray, hi: Any?, ext: (Any?) -> Comparable<Any?>): Int {
        if (hi == null) return arr.size
        var l = 0; var r = arr.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (compareValues(ext(store[arr[m]]), hi as Comparable<Any?>) <= 0) l = m + 1 else r = m
        }
        return l
    }
}
