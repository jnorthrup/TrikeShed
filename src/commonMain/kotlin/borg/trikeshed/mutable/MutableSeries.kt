@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin

// ──────────────────────────────────────────────────────────────────────────
//  Capability Interfaces — compose what you need
// ──────────────────────────────────────────────────────────────────────────

/**
 * Appendable — grow by pushing to tail (amortized O(1)).
 */
interface Appendable<T> {
    fun append(item: T): Appendable<T>
    fun insert(index: Int, item: T): Appendable<T>
}

/**
 * RandomAccess — index-based get/set in O(1).
 */
interface RandomAccess<T> {
    operator fun get(index: Int): T
    fun set(index: Int, item: T): RandomAccess<T>
    val size: Int
}

/**
 * Freezable — seal mutations, enable structural sharing.
 */
interface Freezable<T> {
    fun freeze(): Frozen<T>
    val isFrozen: Boolean
}

/**
 * COWOnly — copy-on-write, functional composition via Twin transitions.
 */
interface COWOnly<T> {
    fun cowUpdate(index: Int, item: T): COWOnly<T>
    fun cowSnapshot(): COWOnly<T>
    fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit
    fun version(): Long
}

/**
 * Removable — delete elements (not all mutable series support removal).
 */
interface Removable<T> {
    fun removeAt(index: Int): Pair<Removable<T>, T>
    fun remove(item: T): Boolean
    fun clear(): Removable<T>
}

/**
 * Frozen — immutable snapshot after freeze.
 */
interface Frozen<T> : Series<T> {
    fun thaw(): MutableSeries<T>
    val size: Int
}

// ──────────────────────────────────────────────────────────────────────────
//  MutableSeries — composes all four capabilities
// ──────────────────────────────────────────────────────────────────────────

/**
 * MutableSeries — the canonical mutable series type.
 *
 * Composes: Appendable + RandomAccess + Freezable + COWOnly + Removable.
 * A MutableSeries IS a Series<T> = Join<Int, (Int) -> T>.
 *
 * Capabilities are real interfaces — mix and match at construction:
 *   val appendOnly: Appendable<String> = mutableSeries.of(...)
 *   val readOnly: RandomAccess<Int> = frozenSeries
 *   val cow: COWOnly<Double> = mutableSeries.cowSnapshot()
 */
interface MutableSeries<T> : Series<T>, Appendable<T>, RandomAccess<T>, Freezable<T>, COWOnly<T>, Removable<T> {

    override fun append(item: T): MutableSeries<T>
    override fun insert(index: Int, item: T): MutableSeries<T>
    override fun set(index: Int, item: T): MutableSeries<T>
    override fun freeze(): Frozen<T>
    override fun cowUpdate(index: Int, item: T): MutableSeries<T>
    override fun cowSnapshot(): MutableSeries<T>
    override fun removeAt(index: Int): Pair<MutableSeries<T>, T>
    override fun remove(item: T): Boolean
    override fun clear(): MutableSeries<T>

    /** Iterator over elements. */
    operator fun iterator(): Iterator<T>

    /** Sequence view — lazy. */
    fun sequence(): Sequence<T>

    /** Concatenation — structural sharing if both frozen. */
    operator fun plus(other: MutableSeries<T>): MutableSeries<T>

    // Operator aliases for backward compat
    fun add(item: T): MutableSeries<T> = append(item)
    fun add(index: Int, item: T): MutableSeries<T> = insert(index, item)
    operator fun plus(item: T): MutableSeries<T> = append(item)
    operator fun minus(item: T): MutableSeries<T> = if (remove(item)) this else this
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

    override val size: Int get() = arr.size
    override val a: Int get() = arr.size
    override val b: (Int) -> T get() = { i -> arr[i] as T }
    override val isFrozen: Boolean get() = frozen

    // ── RandomAccess ──────────────────────────────────────────────

    override fun get(index: Int): T = arr[index] as T

    override fun set(index: Int, item: T): MutableSeries<T> {
        check(!frozen) { "Series is frozen" }
        val copy = arr.copyOf()
        copy[index] = item
        return COWArrayBackend(copy, false, ver + 1, observer).also { it.bump(this) }
    }

    // ── Appendable ────────────────────────────────────────────────

    override fun append(item: T): MutableSeries<T> {
        check(!frozen) { "Series is frozen" }
        val copy = arr.copyOf(arr.size + 1)
        copy[arr.size] = item
        return COWArrayBackend(copy, false, ver + 1, observer).also { it.bump(this) }
    }

    override fun insert(index: Int, item: T): MutableSeries<T> {
        check(!frozen) { "Series is frozen" }
        val copy = Array<Any?>(arr.size + 1) { i ->
            when {
                i < index -> arr[i]
                i == index -> item
                else -> arr[i - 1]
            }
        }
        return COWArrayBackend(copy, false, ver + 1, observer).also { it.bump(this) }
    }

    // ── Removable ─────────────────────────────────────────────────

    override fun removeAt(index: Int): Pair<MutableSeries<T>, T> {
        check(!frozen) { "Series is frozen" }
        val removed = arr[index] as T
        val copy = Array<Any?>(arr.size - 1) { i -> if (i < index) arr[i] else arr[i + 1] }
        return COWArrayBackend(copy, false, ver + 1, observer).also { it.bump(this) } to removed
    }

    override fun remove(item: T): Boolean {
        val i = arr.indexOfFirst { it == item }
        return i >= 0
    }

    override fun clear(): MutableSeries<T> =
        COWArrayBackend(emptyArray(), false, ver + 1, observer).also { it.bump(this) }

    // ── Freezable ─────────────────────────────────────────────────

    override fun freeze(): Frozen<T> {
        frozen = true
        return FrozenArray(arr)
    }

    // ── COWOnly ───────────────────────────────────────────────────

    override fun cowUpdate(index: Int, item: T): MutableSeries<T> = set(index, item)

    override fun cowSnapshot(): MutableSeries<T> = COWArrayBackend(arr.copyOf(), false, ver, observer)

    override fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit {
        val prior = this.observer
        this.observer = observer
        return { this.observer = prior }
    }

    override fun version(): Long = ver

    // ── Series/Iterable ───────────────────────────────────────────

    override fun iterator(): Iterator<T> = arr.iterator().map { it as T }

    override fun sequence(): Sequence<T> = arr.asSequence().map { it as T }

    override fun plus(other: MutableSeries<T>): MutableSeries<T> {
        val otherList = other.sequence().toList().toTypedArray()
        return COWArrayBackend(arr + otherList, frozen, ver, observer)
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun bump(old: COWArrayBackend<T>) {
        observer?.invoke(old as Series<T> to this as Series<T>)
    }
}

/**
 * FrozenArray — immutable snapshot. Implements Frozen<T>, can thaw back to mutable.
 */
class FrozenArray<T>(private val arr: Array<Any?>) : Frozen<T> {
    override val size: Int get() = arr.size
    override val a: Int get() = arr.size
    override val b: (Int) -> T get() = { i -> arr[i] as T }
    override fun thaw(): MutableSeries<T> = COWArrayBackend(arr.copyOf(), false)
    operator fun get(index: Int): T = arr[index] as T
    operator fun iterator(): Iterator<T> = arr.iterator().map { it as T }
    fun sequence(): Sequence<T> = arr.asSequence().map { it as T }
}

// ──────────────────────────────────────────────────────────────────────────
//  ChunkedBackend — amortized O(1) append, O(1) freeze. No COW.
//  Good for: append-only journals, WAL segments, event logs.
//  Bad for: frequent random set/remove (O(chunkSize) per mutation).
// ──────────────────────────────────────────────────────────────────────────

/**
 * ChunkedBackend — amortized O(1) append for large series.
 * Implements: Appendable + RandomAccess + Freezable + Removable.
 * Does NOT implement COWOnly (use COWArrayBackend for that).
 */
class ChunkedBackend<T>(
    private val chunkSize: Int = 4096,
    private var chunks: MutableList<Array<Any?>> = mutableListOf(),
    private var totalSize: Int = 0,
    private var frozen: Boolean = false,
) : Appendable<T>, RandomAccess<T>, Freezable<T>, Removable<T>, Series<T> {

    override val size: Int get() = totalSize
    override val a: Int get() = totalSize
    override val b: (Int) -> T get() = { i ->
        val (ci, offset) = locate(i); chunks[ci][offset] as T
    }
    override val isFrozen: Boolean get() = frozen

    init { require(chunkSize > 0) }

    private fun locate(index: Int): Pair<Int, Int> {
        var acc = 0
        for (ci in chunks.indices) {
            val next = acc + chunks[ci].size
            if (index < next) return ci to (index - acc)
            acc = next
        }
        throw IndexOutOfBoundsException("index $index, total $totalSize")
    }

    // ── Appendable ────────────────────────────────────────────────

    override fun append(item: T): Appendable<T> {
        check(!frozen) { "Series is frozen" }
        if (chunks.isEmpty() || chunks.last().size >= chunkSize) {
            chunks.add(Array(chunkSize) { null })
        }
        val lastChunk = chunks.last()
        lastChunk[totalSize % chunkSize.coerceAtLeast(1)] = item  // simplified
        totalSize++
        return this
    }

    override fun insert(index: Int, item: T): Appendable<T> {
        check(!frozen) { "Series is frozen" }
        // For simplicity, only tail insert is efficient
        if (index == totalSize) return append(item)
        TODO("ChunkedBackend.insert at arbitrary index requires chunk split")
    }

    // ── RandomAccess ──────────────────────────────────────────────

    override fun get(index: Int): T {
        val (ci, offset) = locate(index)
        return chunks[ci][offset] as T
    }

    override fun set(index: Int, item: T): RandomAccess<T> {
        check(!frozen) { "Series is frozen" }
        val (ci, offset) = locate(index)
        chunks[ci][offset] = item
        return this
    }

    // ── Freezable ─────────────────────────────────────────────────

    override fun freeze(): Frozen<T> {
        frozen = true
        // Flatten to single array for frozen view
        val flat = Array<Any?>(totalSize) { i -> this[i] }
        return FrozenArray(flat)
    }

    // ── Removable ─────────────────────────────────────────────────

    override fun removeAt(index: Int): Pair<Removable<T>, T> {
        check(!frozen) { "Series is frozen" }
        val (ci, offset) = locate(index)
        val removed = chunks[ci][offset] as T
        // Simplified: rebuild without the element
        val remaining = sequence().toMutableList()
        remaining.removeAt(index)
        chunks = mutableListOf(Array(chunkSize) { null })
        totalSize = 0
        remaining.forEach { append(it) }
        return this to removed
    }

    override fun remove(item: T): Boolean {
        val i = (0 until totalSize).firstOrNull { b(it) == item } ?: return false
        removeAt(i)
        return true
    }

    override fun clear(): Removable<T> {
        check(!frozen) { "Series is frozen" }
        chunks.clear()
        totalSize = 0
        return this
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Factory functions
// ──────────────────────────────────────────────────────────────────────────

/** Create a COW-backed MutableSeries (default). */
fun <T> mutableSeriesOf(vararg items: T): MutableSeries<T> =
    COWArrayBackend(arrayOf(*items))

/** Create from a sequence. */
fun <T> MutableSeries<T>.from(items: Sequence<T>): MutableSeries<T> =
    COWArrayBackend(items.toList().toTypedArray())

/** Create a chunked backend (append-optimized). */
fun <T> chunkedSeriesOf(chunkSize: Int = 4096): ChunkedBackend<T> =
    ChunkedBackend(chunkSize)

// ──────────────────────────────────────────────────────────────────────────
//  Backward compatibility: CowSeriesHandle, CowSeriesBody
//  (deprecated aliases for COWArrayBackend)
// ──────────────────────────────────────────────────────────────────────────

@Deprecated("Use MutableSeries / COWArrayBackend instead")
typealias CowSeriesHandle<T> = COWArrayBackend<T>

@Deprecated("Use FrozenArray instead")
typealias CowSeriesBody<T> = FrozenArray<T>

@Deprecated("Use FrozenArray instead")
typealias COWSeriesBody<T> = FrozenArray<T>

/** Backward-compat factory. */
@Deprecated("Use mutableSeriesOf instead")
fun <T> cowSeriesHandle(): MutableSeries<T> = COWArrayBackend()