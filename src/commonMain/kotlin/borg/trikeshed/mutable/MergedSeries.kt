package borg.trikeshed.mutable

import borg.trikeshed.lib.*

/**
 * A [MutableSeries] that buffers additions in an input [MutableSeries] (ring, slab, etc.)
 * and drains into a [SortedSeries] on [flush] or when [mergeThreshold] is reached.
 *
 * This separates the *buffering* concern (any ring/slab) from the *sorting* concern
 * ([SortedSeries]). The input buffer can be a [RingSeries], [RecursiveMutableSeries],
 * [ChunkedMutableSeries], or any [MutableSeries] implementation.
 *
 * @param input       the buffer that accumulates additions (any MutableSeries)
 * @param sorted      the [SortedSeries] that receives drained elements in sorted order
 * @param mergeThreshold  when input.size >= threshold, auto-flush occurs
 */
class MergedSeries<T>(
    private val input: MutableSeries<T>,
    private val sorted: SortedSeries<T>,
    private val mergeThreshold: Int = 1,
) : MutableSeries<T> {

    override val a: Int get() = sorted.size
    override val b: (Int) -> T = sorted.b

    override fun add(item: T) {
        input.add(item)
        if (input.size >= mergeThreshold) flush()
    }

    override fun add(index: Int, item: T) {
        // Index is ignored for the input buffer; sort order wins on flush
        add(item)
    }

    override fun set(index: Int, item: T) {
        // Set operates on the sorted backing directly
        sorted.set(index, item)
    }

    override fun removeAt(index: Int): T {
        return sorted.removeAt(index)
    }

    override fun remove(item: T): Boolean {
        return sorted.remove(item)
    }

    override fun clear() {
        input.clear()
        sorted.clear()
    }

    override fun plus(item: T): MutableSeries<T> { add(item); return this }
    override fun minus(item: T): MutableSeries<T> { remove(item); return this }
    override fun plusAssign(item: T) { add(item) }
    override fun minusAssign(item: T) { remove(item) }

    /** Force-drain the input buffer into the sorted series. */
    fun flush() {
        if (input.size == 0) return
        // Drain input into sorted by adding each element (sorted.add maintains order)
        for (i in 0 until input.size) {
            sorted.add(input[i])
        }
        input.clear()
    }

    /** Current number of buffered (not yet sorted) elements. */
    val pendingSize: Int get() = input.size

    /** Total elements including both sorted and pending. */
    val totalSize: Int get() = sorted.size + input.size
}
