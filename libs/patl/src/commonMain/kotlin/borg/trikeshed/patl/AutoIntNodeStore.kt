@file:Suppress("FunctionName")

package borg.trikeshed.patl

import borg.trikeshed.lib.*

/**
 * Pluggable compressed freeze of an [IntNodeStore].
 *
 * After building the trie with mutable [IntNodeStore], freeze to a read-only
 * compressed representation. The compressor probes the value range and selects
 * the densest [PackedIntSeries] per field.
 */
object AutoIntNodeStore {

    /** Freeze into a [CompressedNodeStore] with bit-packed fields. */
    fun freeze(store: IntNodeStore): CompressedNodeStore {
        val n = store.size
        if (n == 0) return CompressedNodeStore.empty()

        return CompressedNodeStore(
            parentid   = packField(store, n) { TwInt(store.meta[it]).a },
            leftChild  = packField(store, n) { TwInt(store.links[it]).a },
            rightChild = packField(store, n) { TwInt(store.links[it]).b },
            skip       = packField(store, n) { TwInt(store.meta[it]).b },
        )
    }

    // ── Pack helpers ────────────────────────────────────────────────────

    /** Pack one field: scan for bit-width (unsigned range), then pack. */
    private fun packField(store: IntNodeStore, n: Int, extract: (Int) -> Int): Series<Int> {
        val series: Series<Int> = n j extract
        var max = 0
        var min = 0
        series.view.forEach { v ->
            if (v > max) max = v
            if (v < min) min = v
        }
        // Compute bit-width from unsigned range: need enough bits to hold min..max
        val unsignedMax = if (min < 0) {
            // Convert to unsigned: max(abs(min), max) but both could be large
            val umin = min.toUInt()
            val umax = max.toUInt()
            maxOf(umin, umax).toLong()
        } else {
            max.toLong()
        }
        val bits = if (unsignedMax <= 0L) 1
            else (64 - unsignedMax.countLeadingZeroBits()).toInt().coerceAtLeast(1)
        val buf = PackedIntBuf(bits.coerceAtMost(32))
        series.view.forEach { buf.add(it) }
        return buf.toSeries()
    }
}

/**
 * Read-only compressed node store: four [Series<Int>] fields,
 * each backed by [PackedIntSeries] for minimal bit-width per field.
 *
 * Hot-path accessors are [inline] — the index math compiles to
 * ~4 ALU ops per access.
 */
class CompressedNodeStore(
    val parentid: Series<Int>,
    val leftChild: Series<Int>,
    val rightChild: Series<Int>,
    val skip: Series<Int>,
) {
    val size: Int get() = parentid.a

    fun getParent(index: Int): Int = parentid.b(index) and IntNodeStore.PARENT_MASK
    fun getLeftChild(index: Int): Int = leftChild.b(index)
    fun getRightChild(index: Int): Int = rightChild.b(index)
    fun getChild(index: Int, id: Int): Int =
        if (id == 0) getLeftChild(index) else getRightChild(index)
    fun getSkip(index: Int): Int = skip.b(index)

    companion object {
        fun empty(): CompressedNodeStore {
            val e :Series<Int> =emptySeries()
            return CompressedNodeStore(e, e, e, e)
        }
    }
}
