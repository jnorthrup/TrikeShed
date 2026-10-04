@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Series

/**
 * A growable array a producer fills and a consumer drains. Appends double the array, so a fill is amortized O(1)
 * per element; [drain] hands the filled array over as an immutable [Series] without a copy, and the next fill
 * starts at the size the drained one reached instead of doubling up from [MIN] again. The buffer is itself the
 * live view; [snapshot] copies.
 */
class SeriesBuffer<T>(capacity: Int = 8) : MutableSeries<T> {
    var buf: Array<Any?> = arrayOfNulls(capacity)
    var count: Int = 0
    /** What the next fill allocates once a [drain] has handed the array over. */
    var next: Int = maxOf(capacity, MIN)

    override val a: Int get() = count
    override val b: (Int) -> T = { i ->
        if (i < 0 || i >= count) throw IndexOutOfBoundsException("index $i, size $count")
        buf[i] as T
    }

    /** Room for [n] elements: the array doubles, or grows to [next] after a drain. */
    fun room(n: Int) {
        if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size shl 1, next))
    }

    override fun append(item: T) {
        if (count == buf.size) room(count + 1)
        buf[count++] = item
    }

    override fun insert(index: Int, item: T) {
        if (index < 0 || index > count) throw IndexOutOfBoundsException("index $index, size $count")
        room(count + 1)
        buf.copyInto(buf, index + 1, index, count)
        buf[index] = item
        count++
    }

    override fun set(index: Int, item: T) {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, size $count")
        buf[index] = item
    }

    fun removeLast(): T {
        require(count > 0) { "removeLast on empty SeriesBuffer" }
        val idx = --count
        return (buf[idx] as T).also { buf[idx] = null }
    }

    override fun removeAt(index: Int): T {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, size $count")
        val item = buf[index] as T
        buf.copyInto(buf, index, index + 1, count)
        buf[--count] = null
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until count) if (buf[i] == item) { removeAt(i); return true }
        return false
    }

    override fun clear() {
        buf.fill(null, 0, count)
        count = 0
    }

    /** An immutable copy of the elements as of now. */
    override fun snapshot(): Series<T> = FrozenArray(buf.copyOf(count))

    fun sortWith(comparator: Comparator<in T>) {
        buf.sortWith(Comparator { left, right -> comparator.compare(left as T, right as T) }, 0, count)
    }

    /** Transfer the filled buffer to a Series without a copy; the next fill allocates storage of its own. */
    fun drain(): Series<T> {
        val out = FrozenArray<T>(buf, count)
        next = maxOf(count, MIN)
        buf = EMPTY
        count = 0
        return out
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < count
        override fun next(): T = buf[i++] as T
    }

    companion object {
        const val MIN = 8
        val EMPTY: Array<Any?> = arrayOfNulls(0)
    }
}
