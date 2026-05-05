@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

/**
 * A MutableSeries that maintains sort order on insertion.
 */
class SortedSeries<T>(
    private var data: Series<T> = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") },
    private val comparator: (T, T) -> Int,
) : MutableSeries<T> {

    override val a: Int get() = data.size
    override val b: (Int) -> T = { i -> data[i] }

    private fun insertionIndex(item: T): Int {
        var lo = 0; var hi = data.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (comparator(data[mid], item) < 0) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun indexOf(item: T): Int {
        var lo = 0; var hi = data.size - 1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; val cmp = comparator(data[mid], item); when { cmp < 0 -> lo = mid + 1; cmp > 0 -> hi = mid - 1; else -> return mid } }
        return -1
    }

    override fun add(item: T) {
        val pos = insertionIndex(item)
        val old = data
        data = (old.size + 1) j { i -> when { i < pos -> old[i]; i == pos -> item; else -> old[i - 1] } }
    }

    override fun add(index: Int, item: T) { add(item) }

    override fun set(index: Int, item: T) {
        val n = data.size; val old = data
        data = (n - 1) j { i -> if (i < index) old[i] else old[i + 1] }
        add(item)
    }

    override fun removeAt(index: Int): T {
        val item = data[index]; val old = data
        data = (old.size - 1) j { i -> if (i < index) old[i] else old[i + 1] }
        return item
    }

    override fun remove(item: T): Boolean {
        val idx = indexOf(item)
        if (idx >= 0) { val old = data; data = (old.size - 1) j { i -> if (i < idx) old[i] else old[i + 1] }; return true }
        return false
    }

    override fun clear() { data = 0 j { throw IndexOutOfBoundsException("empty SortedSeries") } }
    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }

    companion object {
        fun <T : Comparable<T>> natural(): SortedSeries<T> = SortedSeries { a, b -> a.compareTo(b) }
    }
}
