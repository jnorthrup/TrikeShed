@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections.multiindex

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class MultiIndexContainer<E : Any> {
    private val store = ArrayList<E?>()
    private val freeIds = ArrayList<Int>()
    private var activeSize = 0

    private data class HashEntry(
        val extractor: (Any?) -> Any?,
        val map: borg.trikeshed.collections.associative.LinearHashMap<Any, Int>,
    )
    private val hashIndexes = ArrayList<HashEntry>()

    private data class NonUniqueHashEntry(
        val extractor: (Any?) -> Any?,
        val map: borg.trikeshed.collections.associative.LinearHashMap<Any, IntArray>,
    )
    private val nonUniqueHashIndexes = ArrayList<NonUniqueHashEntry>()

    private data class SortEntry(
        val extractor: (Any?) -> Comparable<Any?>,
        val positions: IntArray,
    )
    private val sortIndexes = ArrayList<SortEntry>()

    fun add(element: E): IndexSpecId {
        val pos = if (freeIds.isNotEmpty()) freeIds.removeAt(freeIds.size - 1) else store.size.also { store.add(null) }
        store[pos] = element
        activeSize++

        for (he in hashIndexes) {
            val k = he.extractor(element) ?: continue
            he.map[k] = pos
        }
        for (nhe in nonUniqueHashIndexes) {
            val k = nhe.extractor(element) ?: continue
            val existing = nhe.map[k]
            nhe.map[k] = if (existing == null) intArrayOf(pos) else existing + pos
        }
        rebuildSortIndexes()
        return pos
    }

    fun modify(id: IndexSpecId, element: E) {
        val pos = id
        val old = store[pos]
        if (old != null) {
            for (he in hashIndexes) {
                val kOld = he.extractor(old)
                if (kOld != null) he.map.remove(kOld)
            }
            for (nhe in nonUniqueHashIndexes) {
                val kOld = nhe.extractor(old)
                if (kOld != null) {
                    val arr = nhe.map[kOld]
                    if (arr != null) {
                        val newArr = arr.filter { it != pos }.toIntArray() // stdlib-boundary:
                        if (newArr.isEmpty()) nhe.map.remove(kOld) else nhe.map[kOld] = newArr
                    }
                }
            }
        }
        store[pos] = element
        for (he in hashIndexes) {
            val k = he.extractor(element) ?: continue
            he.map[k] = pos
        }
        for (nhe in nonUniqueHashIndexes) {
            val k = nhe.extractor(element) ?: continue
            val existing = nhe.map[k]
            nhe.map[k] = if (existing == null) intArrayOf(pos) else existing + pos
        }
        rebuildSortIndexes()
    }

    fun retract(id: IndexSpecId) {
        val pos = id
        val old = store[pos] ?: return
        store[pos] = null
        freeIds.add(pos)
        activeSize--

        for (he in hashIndexes) {
            val kOld = he.extractor(old)
            if (kOld != null) he.map.remove(kOld)
        }
        for (nhe in nonUniqueHashIndexes) {
            val kOld = nhe.extractor(old)
            if (kOld != null) {
                val arr = nhe.map[kOld]
                if (arr != null) {
                    val newArr = arr.filter { it != pos }.toIntArray() // stdlib-boundary:
                    if (newArr.isEmpty()) nhe.map.remove(kOld) else nhe.map[kOld] = newArr
                }
            }
        }
        rebuildSortIndexes()
    }

    fun snapshot(): MultiIndexContainer<E> {
        val snap = MultiIndexContainer<E>()
        snap.store.addAll(store)
        snap.freeIds.addAll(freeIds)
        snap.activeSize = activeSize

        for (he in hashIndexes) snap.registerHash(MultiIndexK.ByHash(he.extractor as (Any?) -> Any))
        for (nhe in nonUniqueHashIndexes) snap.registerNonUniqueHash(MultiIndexK.ByNonUniqueHash(nhe.extractor as (Any?) -> Any))
        for (se in sortIndexes) snap.registerOrder(MultiIndexK.ByOrder(se.extractor as (Any?) -> Comparable<Any?>))
        return snap
    }

    operator fun get(id: IndexSpecId): E = store[id]!!

    val size: Int get() = activeSize

    @Suppress("UNCHECKED_CAST")
    fun <R> facet(key: MultiIndexK<R>): R = when (key) {
        is MultiIndexK.ByHash<*> -> {
            val he = hashIndexes.firstOrNull { it.extractor === key.extractor }
                ?: buildHashIndex(key.extractor).also { hashIndexes += it }
            val map = he.map
            val fn: (Any?) -> IndexSpecId? = { k -> val v = if (k == null) null else map.get(k); if (v == null) null else v }
            fn as R
        }
        is MultiIndexK.ByNonUniqueHash<*> -> {
            val nhe = nonUniqueHashIndexes.firstOrNull { it.extractor === key.extractor }
                ?: buildNonUniqueHashIndex(key.extractor).also { nonUniqueHashIndexes += it }
            val map = nhe.map
            val fn: (Any?) -> Series<IndexSpecId> = { k -> 
                val arr = if (k == null) null else map.get(k)
                if (arr == null) (0 j { i: Int -> -1 }) else (arr.size j { i: Int -> arr[i] })
            }
            fn as R
        }
        is MultiIndexK.ByOrder<*> -> {
            val ext = key.extractor as (Any?) -> Comparable<Any?>
            val se = sortIndexes.firstOrNull { it.extractor === ext }
                ?: buildSortIndex(ext).also { sortIndexes += it }
            val arr = se.positions
            (arr.size j { i: Int -> arr[i] }) as R
        }
        is MultiIndexK.ByRange<*> -> {
            val ext = key.extractor as (Any?) -> Comparable<Any?>
            sortIndexes.firstOrNull { it.extractor === ext }
                ?: buildSortIndex(ext).also { sortIndexes += it }
            val fn: (Any?, Any?) -> Series<IndexSpecId> = { lo, hi ->
                val se2 = sortIndexes.first { it.extractor === ext }
                val arr = se2.positions
                val from = lowerBound(arr, lo, ext)
                val to = upperBound(arr, hi, ext)
                val len = maxOf(0, to - from)
                len j { i: Int -> arr[from + i] }
            }
            fn as R
        }
        MultiIndexK.BySequence -> {
            val valid = store.indices.filter { store[it] != null }.toIntArray() // stdlib-boundary:
            (valid.size j { i: Int -> valid[i] }) as R
        }
        MultiIndexK.Elements -> {
            val valid = store.indices.filter { store[it] != null }.toIntArray() // stdlib-boundary:
            (valid.size j { i: Int -> store[valid[i]] as Any? }) as R
        }
    }

    private fun buildHashIndex(extractor: (Any?) -> Any?): HashEntry {
        val map = LinearHashMap<Any, Int>(store.size.coerceAtLeast(16))
        for (pos in store.indices) {
            val elem = store[pos] ?: continue
            val k = extractor(elem) ?: continue
            map[k] = pos
        }
        return HashEntry(extractor, map)
    }

    private fun buildNonUniqueHashIndex(extractor: (Any?) -> Any?): NonUniqueHashEntry {
        val map = LinearHashMap<Any, IntArray>(store.size.coerceAtLeast(16))
        for (pos in store.indices) {
            val elem = store[pos] ?: continue
            val k = extractor(elem) ?: continue
            val existing = map[k]
            map[k] = if (existing == null) intArrayOf(pos) else existing + pos
        }
        return NonUniqueHashEntry(extractor, map)
    }

    private fun buildSortIndex(extractor: (Any?) -> Comparable<Any?>): SortEntry {
        val valid = store.indices.filter { store[it] != null }.toIntArray() // stdlib-boundary:
        val positions = valid.sortedWith(Comparator { a, b -> compareValues(extractor(store[a]), extractor(store[b])) }).toIntArray()
        return SortEntry(extractor, positions)
    }

    private fun rebuildSortIndexes() {
        for (i in sortIndexes.indices) sortIndexes[i] = buildSortIndex(sortIndexes[i].extractor)
    }

    fun <K : Any> registerHash(key: MultiIndexK.ByHash<K>) {
        if (hashIndexes.none { it.extractor === key.extractor }) hashIndexes += buildHashIndex(key.extractor)
    }

    fun <K : Any> registerNonUniqueHash(key: MultiIndexK.ByNonUniqueHash<K>) {
        if (nonUniqueHashIndexes.none { it.extractor === key.extractor }) nonUniqueHashIndexes += buildNonUniqueHashIndex(key.extractor)
    }

    fun <K : Comparable<K>> registerOrder(key: MultiIndexK.ByOrder<K>) {
        val ext = key.extractor as (Any?) -> Comparable<Any?>
        if (sortIndexes.none { it.extractor === ext }) sortIndexes += buildSortIndex(ext)
    }

    fun <K : Comparable<K>> registerOrder(key: MultiIndexK.ByRange<K>) {
        val ext = key.extractor as (Any?) -> Comparable<Any?>
        if (sortIndexes.none { it.extractor === ext }) sortIndexes += buildSortIndex(ext)
    }

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
