@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Series

/**
 * A [MutableSeries] kept in [comparator] order at all times.
 *
 * An insert finds its place by binary search and moves the tail with one array copy; equal elements keep their
 * arrival order. [insert] ignores its index and [set] re-sorts — sort order always wins.
 *
 * @param comparator  sort order for elements
 */
class SortedSeries<T>(
    val comparator: (T, T) -> Int,
) : MutableSeries<T> {

    val buffer = SeriesBuffer<T>()

    override val a: Int get() = buffer.count
    override val b: (Int) -> T = buffer.b

    /** The first position whose element sorts after [item]. */
    fun upper(item: T): Int {
        val s = buffer.buf
        var lo = 0
        var hi = buffer.count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparator(s[mid] as T, item) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The first position whose element does not sort before [item]. */
    fun lower(item: T): Int {
        val s = buffer.buf
        var lo = 0
        var hi = buffer.count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comparator(s[mid] as T, item) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun append(item: T) = buffer.insert(upper(item), item)

    override fun insert(index: Int, item: T) = append(item)

    override fun set(index: Int, item: T) {
        buffer.removeAt(index)
        append(item)
    }

    override fun removeAt(index: Int): T = buffer.removeAt(index)

    override fun remove(item: T): Boolean {
        val i = lower(item)
        if (i < buffer.count && comparator(buffer.buf[i] as T, item) == 0) {
            buffer.removeAt(i)
            return true
        }
        return false
    }

    override fun clear() = buffer.clear()

    override fun snapshot(): Series<T> = buffer.snapshot()

    override fun iterator(): Iterator<T> = buffer.iterator()

    companion object {
        /** Create a SortedSeries with natural ordering for Comparable elements. */
        fun <T : Comparable<T>> natural(): SortedSeries<T> =
            SortedSeries { a, b -> a.compareTo(b) }
    }
}
