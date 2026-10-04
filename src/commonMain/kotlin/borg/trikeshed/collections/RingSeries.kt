@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Series

/** Eviction listener invoked when elements are displaced by [RingSeries]. */
fun interface EvictionListener<T> {
    fun onEvict(item: T)
}

/**
 * Fixed-capacity [MutableSeries] backed by a ring buffer.
 *
 * Capacity MUST be a power of 2 — mask-based indexing avoids modulo.
 * When full, [append] overwrites the oldest element and advances head, firing [evict] for the displaced element;
 * [insert] into a full ring displaces the oldest the same way, and an insert at the front of a full ring is itself
 * the oldest, so it is the one displaced.
 *
 * O(1) append, get, set and removal at either end; a removal or insert in the middle moves the shorter side.
 * [copyInto] and [clear] work in at most two contiguous runs, so draining a slab is two array copies and two
 * fills, not a removal per element.
 *
 * This is the canonical ring: the single-arg constructor (`RingSeries(cap)`) resolves here with a `null`
 * eviction listener, satisfying both the simple cursor-style call sites and the richer eviction-aware ones.
 */
class RingSeries<T>(
    capacity: Int,
    val evict: EvictionListener<T>? = null,
) : MutableSeries<T> {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require((capacity and (capacity - 1)) == 0) { "capacity must be power of 2, got $capacity" }
    }

    val mask = capacity - 1
    val buf: Array<Any?> = arrayOfNulls(capacity)
    var head = 0
    var count = 0

    override val a: Int get() = count
    override val b: (Int) -> T = { i ->
        require(i in 0 until count) { "index $i out of bounds [0, $count)" }
        buf[(head + i) and mask] as T
    }

    override fun set(index: Int, item: T) {
        require(index in 0 until count) { "index $index out of bounds [0, $count)" }
        buf[(head + index) and mask] = item
    }

    override fun append(item: T) {
        if (count <= mask) {
            buf[(head + count) and mask] = item
            count++
        } else {
            evict?.onEvict(buf[head] as T)
            buf[head] = item
            head = (head + 1) and mask
        }
    }

    override fun insert(index: Int, item: T) {
        require(index in 0..count) { "index $index out of bounds [0, $count]" }
        if (index == count) { append(item); return }
        if (count > mask) {
            if (index == 0) { evict?.onEvict(item); return }
            evict?.onEvict(buf[head] as T)
            for (i in 0 until index - 1) buf[(head + i) and mask] = buf[(head + i + 1) and mask]
            buf[(head + index - 1) and mask] = item
            return
        }
        if (index < count - index) {
            head = (head - 1) and mask
            for (i in 0 until index) buf[(head + i) and mask] = buf[(head + i + 1) and mask]
        } else {
            for (i in count downTo index + 1) buf[(head + i) and mask] = buf[(head + i - 1) and mask]
        }
        buf[(head + index) and mask] = item
        count++
    }

    override fun removeAt(index: Int): T {
        require(index in 0 until count) { "index $index out of bounds [0, $count)" }
        val item = buf[(head + index) and mask] as T
        if (index < count - 1 - index) {
            for (i in index downTo 1) buf[(head + i) and mask] = buf[(head + i - 1) and mask]
            buf[head] = null
            head = (head + 1) and mask
        } else {
            for (i in index until count - 1) buf[(head + i) and mask] = buf[(head + i + 1) and mask]
            buf[(head + count - 1) and mask] = null
        }
        count--
        return item
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until count) {
            if (buf[(head + i) and mask] == item) {
                removeAt(i)
                return true
            }
        }
        return false
    }

    override fun clear() {
        val first = minOf(count, buf.size - head)
        buf.fill(null, head, head + first)
        if (count > first) buf.fill(null, 0, count - first)
        head = 0
        count = 0
    }

    /** The elements oldest first into [dst] from [offset], in at most two array copies; returns how many. */
    fun copyInto(dst: Array<in T>, offset: Int = 0): Int {
        val out = dst as Array<Any?>
        val first = minOf(count, buf.size - head)
        buf.copyInto(out, offset, head, head + first)
        if (count > first) buf.copyInto(out, offset + first, 0, count - first)
        return count
    }

    override fun snapshot(): Series<T> = FrozenArray(arrayOfNulls<Any?>(count).also { copyInto(it) })

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < count
        override fun next(): T = buf[(head + i++) and mask] as T
    }
}
