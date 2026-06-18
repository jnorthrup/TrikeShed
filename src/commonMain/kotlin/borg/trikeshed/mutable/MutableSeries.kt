@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.mutable

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j

/** 
 * MutableSeries — the canonical series type.
 * 
 * Four fundamental capabilities, composed, not inherited:
 *   1. Appendable   — grow by pushing to tail (amortized O(1))
 *   2. RandomAccess — index-based get/set in O(1)
 *   3. Freezable    — seal mutations, enable structural sharing
 *   4. COWOnly      — copy-on-write, functional composition via Twin transitions
 *
 * A MutableSeries IS a Series<T> = Join<Int, (Int) -> T>.
 * Capabilities are mixins, not subclasses — compose what you need.
 */
@JvmInline
value class MutableSeries<T>(
    private val impl: MutableSeriesImpl<T>,
) : Series<T> by impl {

    /** Append element at tail — O(1) amortized. */
    fun append(item: T): MutableSeries<T> = impl.append(item)

    /** Get by index — O(1). */
    operator fun get(index: Int): T = impl.get(index)

    /** Set by index — O(1) for random access backends. */
    operator fun set(index: Int, item: T): MutableSeries<T> = impl.set(index, item)

    /** Freeze mutations — returns an immutable view. */
    fun freeze(): FrozenSeries<T> = FrozenSeries(impl.freeze())

    /** COW snapshot — functional update via path copying. */
    fun cowUpdate(index: Int, item: T): MutableSeries<T> = impl.cowUpdate(index, item)

    /** COW snapshot of entire series. */
    fun cowSnapshot(): MutableSeries<T> = impl.cowSnapshot()

    /** Insert at index — shifts tail right. */
    fun insert(index: Int, item: T): MutableSeries<T> = impl.insert(index, item)

    /** Remove at index — returns removed element. */
    fun removeAt(index: Int): Pair<MutableSeries<T>, T> = impl.removeAt(index)

    /** Remove first occurrence. */
    fun remove(item: T): MutableSeries<T> = impl.remove(item)

    /** Clear all elements. */
    fun clear(): MutableSeries<T> = impl.clear()

    /** Size — O(1). */
    val size: Int get() = impl.size

    /** Iterator over elements. */
    operator fun iterator(): Iterator<T> = impl.iterator()

    /** Concatenation — structural sharing if both frozen. */
    operator fun plus(other: MutableSeries<T>): MutableSeries<T> = impl.concat(other)

    /** Sequence view — lazy. */
    fun sequence(): Sequence<T> = impl.sequence()
}

/** Frozen/immutable series view — structural sharing enabled. */
@JvmInline
value class FrozenSeries<T>(
    private val impl: FrozenSeriesImpl<T>,
) : Series<T> by impl {

    val size: Int get() = impl.size
    operator fun get(index: Int): T = impl.get(index)
    operator fun iterator(): Iterator<T> = impl.iterator()
    fun sequence(): Sequence<T> = impl.sequence()
    fun thaw(): MutableSeries<T> = impl.thaw()
}

/** Implementation interface — backend strategy. */
interface MutableSeriesImpl<T> : Series<T> {
    fun append(item: T): MutableSeries<T>
    fun get(index: Int): T
    fun set(index: Int, item: T): MutableSeries<T>
    fun freeze(): FrozenSeriesImpl<T>
    fun cowUpdate(index: Int, item: T): MutableSeries<T>
    fun cowSnapshot(): MutableSeries<T>
    fun insert(index: Int, item: T): MutableSeries<T>
    fun removeAt(index: Int): Pair<MutableSeries<T>, T>
    fun remove(item: T): MutableSeries<T>
    fun clear(): MutableSeries<T>
    val size: Int
    fun iterator(): Iterator<T>
    fun concat(other: MutableSeries<T>): MutableSeries<T>
    fun sequence(): Sequence<T>
}

interface FrozenSeriesImpl<T> : Series<T> {
    fun thaw(): MutableSeriesImpl<T>
}

/** ──────────────────────────────────────────────────────────────────────────
 *  COW Array Backend — the default implementation
 *  ────────────────────────────────────────────────────────────────────────── */

class CowArrayImpl<T>(
    private var arr: Array<Any?> = emptyArray(),
    private var isFrozen: Boolean = false,
) : MutableSeriesImpl<T> {

    override val size: Int get() = arr.size

    override fun append(item: T): MutableSeries<T> {
        check(!isFrozen) { "Series is frozen" }
        val copy = arr.copyOf()
        copy[arr.size] = item
        return CowArrayImpl(copy, false)
    }

    override fun get(index: Int): T = @Suppress("UNCHECKED_CAST") arr[index] as T

    override fun set(index: Int, item: T): MutableSeries<T> {
        check(!isFrozen) { "Series is frozen" }
        val copy = arr.copyOf()
        copy[index] = item
        return CowArrayImpl(copy, false)
    }

    override fun freeze(): FrozenSeriesImpl<T> = FrozenArrayImpl(arr)

    override fun cowUpdate(index: Int, item: T): MutableSeries<T> = set(index, item)

    override fun cowSnapshot(): MutableSeries<T> = CowArrayImpl(arr.copyOf(), false)

    override fun insert(index: Int, item: T): MutableSeries<T> {
        check(!isFrozen) { "Series is frozen" }
        val copy = Array<Any?>(arr.size + 1) { i ->
            when {
                i < index -> arr[i]
                i == index -> item
                else -> arr[i - 1]
            }
        }
        return CowArrayImpl(copy, false)
    }

    override fun removeAt(index: Int): Pair<MutableSeries<T>, T> {
        check(!isFrozen) { "Series is frozen" }
        val removed = @Suppress("UNCHECKED_CAST") arr[index] as T
        val copy = Array<Any?>(arr.size - 1) { i -> if (i < index) arr[i] else arr[i + 1] }
        return CowArrayImpl(copy, false) to removed
    }

    override fun remove(item: T): MutableSeries<T> {
        check(!isFrozen) { "Series is frozen" }
        val i = arr.indexOfFirst { it == item }
        return if (i >= 0) removeAt(i).first else this
    }

    override fun clear(): MutableSeries<T> = CowArrayImpl(emptyArray(), false)

    override fun iterator(): Iterator<T> = arr.iterator().map { @Suppress("UNCHECKED_CAST") it as T }

    override fun concat(other: MutableSeries<T>): MutableSeries<T> {
        val otherArray = other.sequence().toList().toTypedArray()
        return CowArrayImpl(arr + otherArray, isFrozen)
    }

    override fun sequence(): Sequence<T> = arr.asSequence().map { @Suppress("UNCHECKED_CAST") it as T }
}

/** Frozen (immutable) array backend — enables structural sharing. */
class FrozenArrayImpl<T>(private val arr: Array<Any?>) : FrozenSeriesImpl<T>, Series<T> by this {

    override val size: Int get() = arr.size
    override fun get(index: Int): T = @Suppress("UNCHECKED_CAST") arr[index] as T
    override fun iterator(): Iterator<T> = arr.iterator().map { @Suppress("UNCHECKED_CAST") it as T }
    override fun sequence(): Sequence<T> = arr.asSequence().map { @Suppress("UNCHECKED_CAST") it as T }
    override fun thaw(): MutableSeriesImpl<T> = CowArrayImpl(arr.copyOf(), false)
}

/** ──────────────────────────────────────────────────────────────────────────
 *  Extension functions for capability queries
 *  ────────────────────────────────────────────────────────────────────────── */

inline fun <T> MutableSeries<T>.isFrozen(): Boolean = impl.isFrozen()

inline fun <T> MutableSeries<T>.isAppendable(): Boolean = true

inline fun <T> MutableSeries<T>.asRandomAccess(): RandomAccessSeries<T> = RandomAccessSeries(impl)

inline fun <T> MutableSeries<T>.asAppendable(): AppendableSeries<T> = AppendableSeries(impl)

/** Capability views — zero-cost wrappers. */
@JvmInline
value class RandomAccessSeries<T>(private val impl: MutableSeriesImpl<T>) {
    operator fun get(index: Int): T = impl.get(index)
    operator fun set(index: Int, item: T): MutableSeries<T> = impl.set(index, item)
    val size: Int get() = impl.size
}

@JvmInline
value class AppendableSeries<T>(private val impl: MutableSeriesImpl<T>) {
    fun append(item: T): MutableSeries<T> = impl.append(item)
    fun insert(index: Int, item: T): MutableSeries<T> = impl.insert(index, item)
    fun remove(item: T): MutableSeries<T> = impl.remove(item)
    fun clear(): MutableSeries<T> = impl.clear()
}

/** Factory functions. */
fun <T> mutableSeriesOf(vararg items: T): MutableSeries<T> =
    MutableSeries(CowArrayImpl(arrayOf(*items)))

fun <T> mutableSeriesOf(items: Sequence<T>): MutableSeries<T> =
    MutableSeries(CowArrayImpl(items.toList().toTypedArray()))

inline fun <T> mutableSeriesOf(block: MutableSeriesImpl<T>.() -> T): MutableSeries<T> = TODO()

/** Chunked backend for large series (future: replace with B+tree). */
class ChunkedImpl<T>(
    private var chunks: List<Array<Any?>> = emptyList(),
    private var isFrozen: Boolean = false,
    private val chunkSize: Int = 1024,
) : MutableSeriesImpl<T> {
    // TODO: implement chunked storage for large series
    override val size: Int get() = chunks.sumOf { it.size }
    override fun append(item: T): MutableSeries<T> = TODO()
    override fun get(index: Int): T = TODO()
    override fun set(index: Int, item: T): MutableSeries<T> = TODO()
    override fun freeze(): FrozenSeriesImpl<T> = TODO()
    override fun cowUpdate(index: Int, item: T): MutableSeries<T> = TODO()
    override fun cowSnapshot(): MutableSeries<T> = TODO()
    override fun insert(index: Int, item: T): MutableSeries<T> = TODO()
    override fun removeAt(index: Int): Pair<MutableSeries<T>, T> = TODO()
    override fun remove(item: T): MutableSeries<T> = TODO()
    override fun clear(): MutableSeries<T> = TODO()
    override fun iterator(): Iterator<T> = TODO()
    override fun concat(other: MutableSeries<T>): MutableSeries<T> = TODO()
    override fun sequence(): Sequence<T> = TODO()
}