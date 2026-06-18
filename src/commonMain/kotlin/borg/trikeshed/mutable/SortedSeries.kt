@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.*

/**
 * A [MutableSeries] that maintains elements in sorted order at all times.
 *
 * Every [add] inserts the element at the correct sorted position (O(n)).
 * No buffering, no thresholds, no flush — the series is always sorted.
 *
 * @param comparator  sort order for elements
 */
class SortedSeries<T>(
    private val comparator: (T, T) -> Int,
) : Appendable<T>, RandomAccess<T>, Insertable<T>, Removable<T> {

    private var data: Series<T> = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") }

    override val a: Int get() = data.size
    override val b: (Int) -> T = data.b

    override fun add(item: T) {
        val idx = findInsertionIndex(item)
        data = insertAt(data, idx, item)
    }

    override fun add(index: Int, item: T) {
        // Index is ignored — sort order always wins
        add(item)
    }

    override fun set(index: Int, item: T) {
        // Remove old element at index, then add new item (maintains sort)
        val old = data[index]
        data = removeAt(data, index)
        add(item)
    }

    override fun removeAt(index: Int): T {
        val item = data[index]
        data = removeAt(data, index)
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until data.size) {
            if (comparator(data[i], item) == 0) {
                data = removeAt(data, i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        data = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") }
    }

    fun plus(item: T): SortedSeries<T> { add(item); return this }
    fun minus(item: T): SortedSeries<T> { remove(item); return this }
    fun plusAssign(item: T) { add(item) }
    fun minusAssign(item: T) { remove(item) }

    /** Binary search to find insertion index for [item] in sorted [data]. */
    private fun findInsertionIndex(item: T): Int {
        var lo = 0
        var hi = data.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparator(data[mid], item) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Insert [item] at [index] in [src], returning new series. */
    private fun insertAt(src: Series<T>, index: Int, item: T): Series<T> {
        val n = src.size
        return (n + 1) j { i ->
            when {
                i < index -> src[i]
                i == index -> item
                else -> src[i - 1]
            }
        }
    }

    /** Remove element at [index] from [src], returning new series. */
    private fun removeAt(src: Series<T>, index: Int): Series<T> {
        val n = src.size
        return (n - 1) j { i ->
            if (i < index) src[i] else src[i + 1]
        }
    }

    companion object {
        /** Create a SortedSeries with natural ordering for Comparable elements. */
        fun <T : Comparable<T>> natural(): SortedSeries<T> =
            SortedSeries { a, b -> a.compareTo(b) }
    }
}
