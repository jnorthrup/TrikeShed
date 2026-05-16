package borg.trikeshed.lib.mutable

import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class SeriesBuffer<T>(
    capacity: Int = 8,
) : MutableSeries<T> {
    var buf: Array<Any?> = arrayOfNulls(capacity)
    var count: Int = 0

    override val a: Int get() = count
    override val b: (Int) -> T get() = { index -> buf[index] as T }

    override fun set(index: Int, item: T) {
        require(index in 0 until count) { "Index out of bounds" }
        buf[index] = item
    }

    override fun add(item: T) {
        if (count == buf.size) {
            val nextSize = if (buf.size == 0) 8 else buf.size * 2
            val next = arrayOfNulls<Any?>(nextSize)
            buf.copyInto(next)
            buf = next
        }
        buf[count++] = item
    }

    override fun add(index: Int, item: T) {
        require(index in 0..count) { "Index out of bounds" }
        if (count == buf.size) {
            val nextSize = if (buf.size == 0) 8 else buf.size * 2
            val next = arrayOfNulls<Any?>(nextSize)
            buf.copyInto(next, 0, 0, index)
            next[index] = item
            buf.copyInto(next, index + 1, index, count)
            buf = next
        } else {
            buf.copyInto(buf, index + 1, index, count)
            buf[index] = item
        }
        count++
    }

    fun removeLast(): T {
        require(count > 0) { "removeLast on empty SeriesBuffer" }
        val idx = --count
        return (buf[idx] as T).also { buf[idx] = null }
    }

    override fun removeAt(index: Int): T {
        require(index in 0 until count) { "Index out of bounds" }
        val item = buf[index] as T
        buf.copyInto(buf, index, index + 1, count)
        buf[--count] = null
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until count) {
            if (buf[i] == item) {
                removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        for (i in 0 until count) {
            buf[i] = null
        }
        count = 0
    }

    override fun plus(item: T): MutableSeries<T> {
        add(item)
        return this
    }

    override fun minus(item: T): MutableSeries<T> {
        remove(item)
        return this
    }

    override fun plusAssign(item: T) {
        add(item)
    }

    override fun minusAssign(item: T) {
        remove(item)
    }

    fun snapshot(): Series<T> = count j { index -> buf[index] as T }

    /** Direct toList — avoids Series.toList() extension resolution on MutableSeries. */
    fun toList(): List<T> = List(count) { index -> buf[index] as T }
}
