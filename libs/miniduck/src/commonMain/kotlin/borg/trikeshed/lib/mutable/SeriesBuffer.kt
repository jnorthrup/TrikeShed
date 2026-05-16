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

/**
 * LinkedList-backed [MutableSeries] that IS-A [List].
 * Penalized by RLM like all mutables.
 *
 * Dual-implements MutableSeries + List so both `series[i]` and `list[i]` work
 * at IO edge boundaries. Since this IS-A List, toList() returns List with
 * zero type friction.
 */
class SeriesArrayList<T>(
    private val backing: LinkedList<T> = LinkedList(),
) : MutableSeries<T>, List<T> {
    // MutableSeries: a is the size
    override val a: Int get() = backing.size
    override val b: (Int) -> T get() = { index -> backing[index] }

    // List requires size property
    override val size: Int get() = backing.size

    // MutableSeries mutation ops — delegate to backing
    override fun set(index: Int, item: T) { backing.set(index, item) }
    override fun add(item: T) { backing.add(item) }
    override fun add(index: Int, item: T) { backing.add(index, item) }
    override fun removeAt(index: Int): T = backing.removeAt(index)
    override fun remove(item: T): Boolean = backing.remove(item)
    override fun clear() { backing.clear() }
    override fun plusAssign(item: T) { backing.add(item) }
    override fun minusAssign(item: T) { backing.remove(item) }
    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }

    // List delegation to backing
    override fun get(index: Int): T = backing[index]
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun contains(element: T): Boolean = backing.contains(element)
    override fun containsAll(elements: Collection<out T>): Boolean = backing.containsAll(elements)
    override fun indexOf(element: T): Int = backing.indexOf(element)
    override fun lastIndexOf(element: T): Int = backing.lastIndexOf(element)
    override fun iterator(): Iterator<T> = backing.iterator()
    override fun listIterator(): ListIterator<T> = backing.listIterator()
    override fun listIterator(index: Int): ListIterator<T> = backing.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<T> = backing.subList(fromIndex, toIndex)
    override fun hashCode(): Int = backing.hashCode()
    override fun equals(other: Any?): Boolean = backing.equals(other)
    override fun toString(): String = backing.toString()

    /** Snapshot to pure [Series]. */
    fun snapshot(): Series<T> = a j { index -> backing[index] }

    /** toList — LinkedList IS-A List. */
    fun toList(): List<T> = backing.toList()
}
