@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j

// ──────────────────────────────────────────────────────────────────────────
//  MutableSeries — the canonical mutable series
// ──────────────────────────────────────────────────────────────────────────

/**
 * MutableSeries — the canonical mutable series type.
 * A MutableSeries IS a Series<T> = Join<Int, (Int) -> T>.
 */
interface MutableSeries<T> : Series<T> {


    fun append(item: T): Unit
    fun insert(index: Int, item: T): Unit
    operator fun set(index: Int, item: T): Unit
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear(): Unit

    // ── COW / freeze ─────────────────────────────────────────────
    fun freeze(): Series<T>
    fun cowSnapshot(): MutableSeries<T>
    fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit
    fun version(): Long
    val isFrozen: Boolean

    /** Iterator over elements. */
    operator fun iterator(): Iterator<T>

    /** Sequence view — lazy. */
    fun sequence(): Sequence<T>

    /** Concatenation — structural sharing if both frozen. */
    operator fun plus(other: MutableSeries<T>): MutableSeries<T>

    // Operator aliases for backward compat
    fun add(item: T) = append(item)
    fun add(index: Int, item: T) = insert(index, item)
    operator fun plus(item: T): MutableSeries<T> { append(item); return this }
    operator fun minus(item: T): MutableSeries<T> { remove(item); return this }
    operator fun plusAssign(item: T) { append(item) }
    operator fun minusAssign(item: T) { remove(item) }

    companion object
}

// ──────────────────────────────────────────────────────────────────────────
//  Backends — different capability tradeoffs
// ──────────────────────────────────────────────────────────────────────────

/**
 * COWArrayBackend — default. All four capabilities via copy-on-write Array<Any?>.
 * Read: O(1). Write: O(n) arraycopy. Freeze: O(1) flag flip.
 */
class COWArrayBackend<T>(
    private var arr: Array<Any?> = emptyArray(),
    private var frozen: Boolean = false,
    private var ver: Long = 0L,
    private var observer: ((Twin<Series<T>>) -> Unit)? = null,
) : MutableSeries<T> {

    override val a: Int get() = arr.size
    override val b: (Int) -> T get() = { i -> arr[i] as T }
    override val isFrozen: Boolean get() = frozen

    @Suppress("UNCHECKED_CAST")
    fun get(index: Int): T = arr[index] as T

    override fun set(index: Int, item: T) {
        check(!frozen) { "Series is frozen" }
        arr[index] = item
        ver++
        bump()
    }

    // ── Appendable ────────────────────────────────────────────────

    override fun append(item: T) {
        check(!frozen) { "Series is frozen" }
        arr = arr.copyOf(arr.size + 1)
        arr[arr.size - 1] = item
        ver++
        bump()
    }

    override fun insert(index: Int, item: T) {
        check(!frozen) { "Series is frozen" }
        val copy = Array<Any?>(arr.size + 1) { i ->
            when {
                i < index -> arr[i]
                i == index -> item
                else -> arr[i - 1]
            }
        }
        arr = copy
        ver++
        bump()
    }

    // ── Removable ─────────────────────────────────────────────────

    override fun removeAt(index: Int): T {
        check(!frozen) { "Series is frozen" }
        val removed = arr[index] as T
        arr = Array<Any?>(arr.size - 1) { i -> if (i < index) arr[i] else arr[i + 1] }
        ver++
        bump()
        return removed
    }

    override fun remove(item: T): Boolean {
        val i = arr.indexOfFirst { it == item }
        if (i < 0) return false
        removeAt(i)
        return true
    }

    override fun clear() {
        arr = emptyArray()
        ver++
        bump()
    }

    // ── Freezable ─────────────────────────────────────────────────

    override fun freeze(): Series<T> {
        frozen = true
        return FrozenArray(arr)
    }

    // ── COWOnly ───────────────────────────────────────────────────

    override fun cowSnapshot(): MutableSeries<T> = COWArrayBackend(arr.copyOf(), false, ver, observer)

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit {
        val prior = this.observer
        this.observer = observer
        return { this.observer = prior }
    }

    override fun version(): Long = ver

    // ── Series/Iterable ───────────────────────────────────────────

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var i = 0
        override fun hasNext() = i < arr.size
        @Suppress("UNCHECKED_CAST") override fun next() = arr[i++] as T
    }

    override fun sequence(): Sequence<T> = arr.asSequence().map { it as T }

    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val combined = Array<Any?>(arr.size + other.a) { i ->
            if (i < arr.size) arr[i] else other.b(i - arr.size)
        }
        return COWArrayBackend(combined, false, ver, observer)
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun bump() {
        observer?.invoke(this j this)
    }
}

/**
 * FrozenArray — immutable snapshot. Can thaw back to mutable.
 */
class FrozenArray<T>(internal val arr: Array<Any?>) : Series<T> {
    override val a: Int get() = arr.size
    override val b: (Int) -> T get() = { i -> arr[i] as T }
    fun thaw(): MutableSeries<T> = COWArrayBackend(arr.copyOf(), false)
    operator fun get(index: Int): T = arr[index] as T
    operator fun iterator(): Iterator<T> = object : Iterator<T> {
        private var i = 0
        override fun hasNext() = i < arr.size
        @Suppress("UNCHECKED_CAST") override fun next() = arr[i++] as T
    }
    fun sequence(): Sequence<T> = arr.asSequence().map { it as T }
}

// ── Factory functions ──────────────────────────────────────────────────────

/** Create a COW-backed MutableSeries (default). */
@Suppress("UNCHECKED_CAST")
fun <T> mutableSeriesOf(vararg items: T): MutableSeries<T> =
    COWArrayBackend(items as Array<Any?>)

/** Create from a sequence. */
fun <T> mutableSeriesFrom(items: Sequence<T>): MutableSeries<T> {
    val list = items.toList()
    val arr = Array<Any?>(list.size) { i -> list[i] }
    return COWArrayBackend(arr)
}

// ── Backward compatibility aliases ────────────────────────────────────────

@Deprecated("Use MutableSeries / COWArrayBackend instead")
typealias CowSeriesHandle<T> = COWArrayBackend<T>

@Deprecated("Use FrozenArray instead")
typealias CowSeriesBody<T> = FrozenArray<T>

@Deprecated("Use FrozenArray instead")
typealias COWSeriesBody<T> = FrozenArray<T>

/** Backward-compat factory. */
@Deprecated("Use mutableSeriesOf instead")
fun <T> cowSeriesHandle(): MutableSeries<T> = COWArrayBackend()
