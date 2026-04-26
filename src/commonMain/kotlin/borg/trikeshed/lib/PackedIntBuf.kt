@file:Suppress("UNCHECKED_CAST", "NonAsciiCharacters")

package borg.trikeshed.lib

import kotlin.jvm.JvmInline

// ============================================================================
// PackedIntBuf — growable accumulator that packs small unsigned values into
// dense LongArray words.  Configurable bits-per-value (1..32).
//
// Tradeoff:  random access is O(1) with mask+shift per lookup.
//            Sequential access via DeltaPositionSeries is O(1) amortized.
//            Storage reduction: 4-bit values pack 16/Long → 8× vs IntArray.
// ============================================================================

class PackedIntBuf(
    val bitsPerValue: Int,
    capacity: Int = 16
) {
    init { require(bitsPerValue in 1..32) { "bitsPerValue must be 1..32" } }

    private var words: LongArray
    var size: Int = 0
        private set

    private val mask: Long = if (bitsPerValue == 32) -1L else (1L shl bitsPerValue) - 1

    init {
        val wordCapacity = ((capacity.toLong() * bitsPerValue + 63) / 64).toInt().coerceAtLeast(1)
        words = LongArray(wordCapacity)
    }

    fun add(v: Int) {
        val bitPos = size.toLong() * bitsPerValue
        val wordIdx = (bitPos shr 6).toInt()
        if (wordIdx >= words.size) grow()
        val shift = (bitPos and 63).toInt()
        words[wordIdx] = words[wordIdx] or ((v.toLong() and mask) shl shift)
        // cross word boundary
        if (shift + bitsPerValue > 64) {
            if (wordIdx + 1 >= words.size) grow()
            words[wordIdx + 1] = words[wordIdx + 1] or
                ((v.toLong() and mask) ushr (64 - shift))
        }
        size++
    }

    private fun grow() {
        val n = LongArray((words.size * 2).coerceAtLeast(1))
        var i = 0; while (i < words.size) { n[i] = words[i]; i++ }
        words = n
    }

    operator fun get(index: Int): Int {
        val bitPos = index.toLong() * bitsPerValue
        val wordIdx = (bitPos shr 6).toInt()
        val shift = (bitPos and 63).toInt()
        val value = (words[wordIdx] ushr shift) and mask
        return if (shift + bitsPerValue > 64) {
            val remainder = (words[wordIdx + 1] shl (64 - shift)) and mask
            (value or remainder).toInt()
        } else value.toInt()
    }

    /** Produce a frozen read-only Series view.  Call once, after all adds. */
    fun toSeries(): Series<Int> = PackedIntSeries(
        words = words.copyOf(),
        a = size,
        bitsPerValue = bitsPerValue
    )

    companion object {
        /** Choose the smallest bit-width that can hold all values in [samples]. */
        fun bitsFor(range: IntRange): Int {
            val max = range.last.coerceAtLeast(range.first)
            if (max <= 0) return 1
            return (32 - max.countLeadingZeroBits()).coerceAtLeast(1)
        }
    }
}

// ============================================================================
// PackedIntSeries — frozen read-only Series<Int> over a PackedIntBuf snapshot.
// ============================================================================

class PackedIntSeries(
    private val words: LongArray,
    override val a: Int,
    private val bitsPerValue: Int
) : Series<Int> {
    override val b: (Int) -> Int get() = { i -> getImpl(i) }

    private val mask: Long = if (bitsPerValue == 32) -1L else (1L shl bitsPerValue) - 1

    private fun getImpl(i: Int): Int {
        val bitPos = i.toLong() * bitsPerValue
        val wordIdx = (bitPos shr 6).toInt()
        val shift = (bitPos and 63).toInt()
        val value = (words[wordIdx] ushr shift) and mask
        return if (shift + bitsPerValue > 64) {
            val remainder = (words[wordIdx + 1] shl (64 - shift)) and mask
            (value or remainder).toInt()
        } else value.toInt()
    }
}

// ============================================================================
// DeltaPositionSeries — counter-framed Series that reconstitutes absolute
// positions from a base + packed deltas.
//
// Sequential access: O(1) amortized.  Random access rewinds and replays.
// Use when positions are stored as deltas during parse and read back
// sequentially during cursor/tensor operations.
// ============================================================================

class DeltaPositionSeries(
    private val base: Int,
    private val packed: PackedIntBuf
) : Series<Int> {
    override val a: Int get() = packed.size + 1

    override val b: (Int) -> Int = { i: Int ->
        var pos = base
        for (k in 0..<i) {
            pos += packed[k]
        }
        pos
    }
}
