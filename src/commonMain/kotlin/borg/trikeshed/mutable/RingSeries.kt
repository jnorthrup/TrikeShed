package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin

/** Eviction listener invoked when elements are displaced by [RingSeries]. */
fun interface EvictionListener<T> {
    fun onEvict(item: T)
}

/**
 * Fixed-capacity [MutableSeries] backed by a ring buffer.
 *
 * Capacity MUST be a power of 2 — mask-based indexing avoids modulo.
 * When full, [add] overwrites the oldest element and advances head, firing
 * [evict] for the displaced element. [removeAt] slides the window left.
 *
 * O(1) append, O(1) get, O(1) set, O(n) removeAt/contains.
 *
 * This is the canonical ring: the single-arg constructor (`RingSeries(cap)`)
 * resolves here with a `null` eviction listener, satisfying both the simple
 * cursor-style call sites and the richer eviction-aware ones.
 */
class RingSeries<T>(
    capacity: Int,
    val evict: EvictionListener<T>? = null,
) : MutableSeries<T> {

    init {
        require(capacity > 0) { "capacity must be positive" }
        require((capacity and (capacity - 1)) == 0) { "capacity must be power of 2, got $capacity" }
    }

    private val mask = capacity - 1
    private val buf: Array<Any?> = arrayOfNulls(capacity)
    private var head = 0
    private var count = 0

    override val a: Int get() = count
    @Suppress("UNCHECKED_CAST")
    override val b: (Int) -> T = { i ->
        require(i in 0 until count) { "index $i out of bounds [0, $count)" }
        buf[(head + i) and mask] as T
    }

    override fun set(index: Int, item: T): Unit {
        require(index in 0 until count)
        buf[(head + index) and mask] = item
    }

    override fun append(item: T): Unit {
        if (count < mask + 1) {
            buf[(head + count) and mask] = item
            count++
        } else {
            evict?.onEvict(buf[head and mask] as T)
            buf[head and mask] = item
            head = (head + 1) and mask
        }
    }

    override fun insert(index: Int, item: T): Unit {
        require(index in 0..count)
        if (count < mask + 1) {
            for (i in count downTo index + 1) {
                buf[(head + i) and mask] = buf[(head + i - 1) and mask]
            }
            buf[(head + index) and mask] = item
            count++
        } else {
            for (i in 0 until index) {
                buf[(head + i) and mask] = buf[(head + i + 1) and mask]
            }
            buf[(head + index) and mask] = item
            head = (head + 1) and mask
        }
    }

    override fun removeAt(index: Int): T {
        require(index in 0 until count)
        @Suppress("UNCHECKED_CAST")
        val item = buf[(head + index) and mask] as T
        for (i in index until count - 1) {
            buf[(head + i) and mask] = buf[(head + i + 1) and mask]
        }
        buf[(head + count - 1) and mask] = null
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
        for (i in 0 until count) buf[(head + i) and mask] = null
        head = 0
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

    override fun plusAssign(item: T) { append(item) }
    override fun minusAssign(item: T) { remove(item) }

    override fun freeze(): Series<T> {
        val flat = Array<Any?>(count) { i -> buf[(head + i) and mask] }
        return FrozenArray(flat)
    }
    override fun cowSnapshot(): MutableSeries<T> {
        val snap = RingSeries<T>(mask + 1, evict)
        for (i in 0 until count) snap.append(buf[(head + i) and mask] as T)
        return snap
    }
    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit = {}
    override fun version(): Long = 0L
    override val isFrozen: Boolean get() = false
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < count
        @Suppress("UNCHECKED_CAST")
        override fun next() = buf[(head + i++) and mask] as T
    }
    override fun sequence(): Sequence<T> = Sequence { iterator() }
    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val result = RingSeries<T>((count + other.a).let { n ->
            var p = 1; while (p < n) p = p shl 1; p
        }, evict)
        for (i in 0 until count) result.append(buf[(head + i) and mask] as T)
        for (i in 0 until other.a) result.append(other.b(i))
        return result
    }
}
