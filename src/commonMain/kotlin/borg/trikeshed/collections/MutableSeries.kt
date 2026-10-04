@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import kotlin.concurrent.Volatile

// ──────────────────────────────────────────────────────────────────────────
//  MutableSeries — the canonical mutable series
// ──────────────────────────────────────────────────────────────────────────

/**
 * MutableSeries — the canonical mutable series type.
 * A MutableSeries IS a Series<T> = Join<Int, (Int) -> T> that also takes writes. Only the mutation core is
 * required; [add], `+=` and `-=` are spellings of it, and [snapshot], [iterator] and [sequence] have defaults
 * a backend overrides when it can do better. Freezing and observation belong to [COWArrayBackend], the one
 * backend that implements them.
 */
interface MutableSeries<T> : Series<T>, Snapshotable<T> {

    fun append(item: T)
    fun insert(index: Int, item: T)
    operator fun set(index: Int, item: T)
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear()

    fun add(item: T) = append(item)
    fun add(index: Int, item: T) = insert(index, item)
    operator fun plusAssign(item: T) = append(item)
    operator fun minusAssign(item: T) { remove(item) }

    /** An immutable copy as of now: later writes to this series do not reach it. */
    override fun snapshot(): Series<T> = FrozenArray(Array<Any?>(a) { b(it) })

    /** Iterator over elements. */
    operator fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < a
        override fun next(): T = b(i++)
    }

    /** Sequence view — lazy. */
    fun sequence(): Sequence<T> = Sequence { iterator() }

    companion object
}

// ──────────────────────────────────────────────────────────────────────────
//  COWArrayBackend — the default backend
// ──────────────────────────────────────────────────────────────────────────

/**
 * COWArrayBackend — a growable array shared copy-on-write with its snapshots, its frozen view and its observers.
 *
 * Read: O(1). Append: amortized O(1), the array doubling when full, never a whole copy per append. An append
 * writes past every shared prefix, so it never copies for sharing; any other write copies first while the array
 * is shared with a [snapshot] or a [freeze] view, or observed, so none of those sees it change. [snapshot] and
 * [freeze] hand the array out without a copy.
 *
 * One writer. [count] is published after the element it covers: a reader on another thread that reads the size
 * first reads only elements already written.
 */
class COWArrayBackend<T>(
    var arr: Array<Any?> = emptyArray(),
    filled: Int = arr.size,
) : MutableSeries<T> {

    @Volatile var count: Int = filled
    /** True while [arr] is also read through a snapshot or the frozen view. */
    @Volatile var shared: Boolean = false
    var isFrozen: Boolean = false
    var ver: Long = 0L
    /** Subscriptions in order; replaced whole on every change, so a notification walks a fixed list. */
    @Volatile var observers: List<Observer<T>> = emptyList()

    /** One subscription, the identity its disposer removes: one lambda subscribed twice is two subscriptions. */
    class Observer<T>(val notify: (Twin<Series<T>>) -> Unit)

    override val a: Int get() = count
    override val b: (Int) -> T = { i -> get(i) }

    operator fun get(index: Int): T {
        val n = count
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index $index, size $n")
        return arr[index] as T
    }

    override fun set(index: Int, item: T) {
        check(!isFrozen) { FROZEN }
        val n = count
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index $index, size $n")
        val before = arr
        val observed = observers.isNotEmpty()
        if (shared || observed) { arr = arr.copyOf(); shared = false }
        arr[index] = item
        ver++
        if (observed) notify(before, n)
    }

    override fun append(item: T) {
        check(!isFrozen) { FROZEN }
        val n = count
        val before = arr
        if (n == arr.size) { arr = arr.copyOf(if (n < MIN) MIN else n shl 1); shared = false }
        arr[n] = item
        count = n + 1
        ver++
        notify(before, n)
    }

    override fun insert(index: Int, item: T) {
        check(!isFrozen) { FROZEN }
        val n = count
        if (index < 0 || index > n) throw IndexOutOfBoundsException("index $index, size $n")
        if (index == n) { append(item); return }
        val before = arr
        val observed = observers.isNotEmpty()
        val target = if (shared || observed || n == arr.size)
            arrayOfNulls<Any?>(if (n == arr.size) n shl 1 else arr.size).also { arr.copyInto(it, 0, 0, index) }
        else arr
        arr.copyInto(target, index + 1, index, n)
        target[index] = item
        if (target !== arr) { arr = target; shared = false }
        count = n + 1
        ver++
        if (observed) notify(before, n)
    }

    override fun removeAt(index: Int): T {
        check(!isFrozen) { FROZEN }
        val n = count
        if (index < 0 || index >= n) throw IndexOutOfBoundsException("index $index, size $n")
        val before = arr
        val removed = arr[index] as T
        val observed = observers.isNotEmpty()
        if (shared || observed) {
            val target = arrayOfNulls<Any?>(arr.size)
            arr.copyInto(target, 0, 0, index)
            arr.copyInto(target, index, index + 1, n)
            arr = target; shared = false
        } else {
            arr.copyInto(arr, index, index + 1, n)
            arr[n - 1] = null
        }
        count = n - 1
        ver++
        if (observed) notify(before, n)
        return removed
    }

    override fun remove(item: T): Boolean {
        for (i in 0 until count) if (arr[i] == item) { removeAt(i); return true }
        return false
    }

    override fun clear() {
        check(!isFrozen) { FROZEN }
        val n = count
        if (n == 0) return
        val before = arr
        val observed = observers.isNotEmpty()
        if (shared || observed) { arr = emptyArray(); shared = false } else arr.fill(null, 0, n)
        count = 0
        ver++
        if (observed) notify(before, n)
    }

    /** Seals this series and returns its array as an immutable view, without a copy. */
    fun freeze(): Series<T> {
        val n = count
        isFrozen = true
        shared = true
        return FrozenArray(arr, n)
    }

    /**
     * The elements as of now, without a copy: the next write that is not an append copies the array instead.
     * The size is read before the array, so a reader on another thread never pairs a newer size with an older,
     * shorter array.
     */
    override fun snapshot(): Series<T> {
        val n = count
        shared = true
        return FrozenArray(arr, n)
    }

    /**
     * Calls [observer] after every write with (before, after): before is the prior state, after is this series.
     * Before is a view of the prior array, valid for the call; [snapshot] it to keep it.
     */
    fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit {
        val o = Observer(observer)
        synchronizedLock(this) { observers = observers + o }
        return { synchronizedLock(this) { observers = observers.filter { it !== o } } }
    }

    fun version(): Long = ver

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < count
        override fun next(): T = get(i++)
    }

    fun notify(before: Array<Any?>, n: Int) {
        val obs = observers
        if (obs.isEmpty()) return
        val twin: Twin<Series<T>> = FrozenArray<T>(before, n) j this
        for (o in obs) o.notify(twin)
    }

    companion object {
        const val MIN = 8
        const val FROZEN = "Series is frozen"
    }
}

/**
 * FrozenArray — an immutable view over the first [count] slots of an array that is not written again. Can thaw
 * back to mutable.
 */
class FrozenArray<T>(val arr: Array<Any?>, val count: Int = arr.size) : Series<T> {
    override val a: Int get() = count
    override val b: (Int) -> T = { i -> get(i) }
    fun thaw(): MutableSeries<T> = COWArrayBackend(arr.copyOf(count))
    operator fun get(index: Int): T {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, size $count")
        return arr[index] as T
    }
    operator fun iterator(): Iterator<T> = object : Iterator<T> {
        var i = 0
        override fun hasNext() = i < count
        override fun next(): T = arr[i++] as T
    }
    fun sequence(): Sequence<T> = Sequence { iterator() }
}

// ── Factory functions ──────────────────────────────────────────────────────

/** Create a COW-backed MutableSeries (default). */
fun <T> mutableSeriesOf(vararg items: T): COWArrayBackend<T> = COWArrayBackend(arrayOf<Any?>(*items))

/** Create from a sequence. */
fun <T> mutableSeriesFrom(items: Sequence<T>): COWArrayBackend<T> = COWArrayBackend<T>().apply { for (x in items) append(x) }
