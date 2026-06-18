@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.*

/**
 * A [MutableSeries] that maintains elements in sorted order at all times.
 *
 * Every [append] inserts the element at the correct sorted position (O(n)).
 * No buffering, no thresholds, no flush — the series is always sorted.
 *
 * @param comparator  sort order for elements
 */
class SortedSeries<T>(
    private val comparator: (T, T) -> Int,
) : MutableSeries<T> {

    private var data: Series<T> = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") }

    override val a: Int get() = data.size
    override val b: (Int) -> T get() = data::get

    override fun append(item: T) {
        val idx = findInsertionIndex(item)
        data = insertAt(data, idx, item)
    }

    override fun insert(index: Int, item: T) {
        // Index is ignored — sort order always wins
        append(item)
    }

    override fun set(index: Int, item: T) {
        // Remove old element at index, then add new item (maintains sort)
        val old = data[index]
        data = removeAt(data, index)
        append(item)
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

    // ── COW / freeze ─────────────────────────────────────────────

    override val isFrozen: Boolean get() = false

    override fun freeze(): Series<T> = FrozenArray(Array<Any?>(data.a) { i -> data[i] })

    override fun cowSnapshot(): MutableSeries<T> {
        val snap = SortedSeries(comparator)
        for (i in 0 until data.a) snap.append(data[i])
        return snap
    }

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit = {}

    override fun version(): Long = 0L

    // ── Iteration ────────────────────────────────────────────────

    override fun iterator(): Iterator<T> = sequence().iterator()

    override fun sequence(): Sequence<T> = (0 until a).asSequence().map { b(it) }

    // ── Concatenation ────────────────────────────────────────────

    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val result = SortedSeries(comparator)
        for (i in 0 until data.a) result.append(data[i])
        for (i in 0 until other.a) result.append(other.b(i))
        return result
    }

    override fun plus(item: T): MutableSeries<T> { append(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { append(item) }
    override fun minusAssign(item: T) { remove(item) }

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
