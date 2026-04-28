@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

import borg.trikeshed.collections.s_

/**
 * A MutableSeries that maintains sort order on insertion.
 *
 * [add] binary-searches for the insertion point then rebuilds the backing
 * Series with the item inserted at the correct position.
 * [contains] is O(log n) via binary search on the sorted backing.
 *
 * The backing is a plain [Series] — each mutation produces a new Series
 * via `size j { ... }` (same pattern as [RecursiveMutableSeries]).
 */
class SortedSeries<T>(
    private var data: Series<T> = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") },
    private val comparator: (T, T) -> Int,
) : MutableSeries<T> {

    override val a: Int get() = data.size
    override val b: (Int) -> T = data::get

    /** Binary search for insertion point: first index where data[i] >= item. */
    private fun insertionIndex(item: T): Int {
        var lo = 0
        var hi = data.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparator(data[mid], item) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Binary search for exact match. */
    private fun indexOf(item: T): Int {
        var lo = 0
        var hi = data.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = comparator(data[mid], item)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    override fun add(item: T) {
        val pos = insertionIndex(item)
        data = (data.size + 1) j { i ->
            when {
                i < pos -> data[i]
                i == pos -> item
                else -> data[i - 1]
            }
        }
    }

    override fun add(index: Int, item: T) {
        // Ignore caller index — maintain sort order
        add(item)
    }

    override fun set(index: Int, item: T) {
        // Remove old, insert new at sorted position
        val newSize = data.size
        data = newSize j { i ->
            when {
                i < index -> data[i]
                i > index -> data[i]
                else -> data[if (i + 1 < newSize) i + 1 else i]  // skip old element
            }
        }
        add(item)
    }

    override fun removeAt(index: Int): T {
        val item = data[index]
        data = (data.size - 1) j { i ->
            if (i < index) data[i] else data[i + 1]
        }
        return item
    }

    override fun remove(item: T): Boolean {
        val idx = indexOf(item)
        if (idx >= 0) {
            data = (data.size - 1) j { i ->
                if (i < idx) data[i] else data[i + 1]
            }
            return true
        }
        return false
    }

    override fun clear() {
        data = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") }
    }

    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }

    companion object {
        /** Create a SortedSeries for naturally comparable types. */
        fun <T : Comparable<T>> natural(): SortedSeries<T> =
            SortedSeries { a, b -> a.compareTo(b) }
    }
}
