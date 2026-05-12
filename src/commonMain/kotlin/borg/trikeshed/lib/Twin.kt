@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.lib

import kotlin.jvm.JvmInline

/**
 * Compact twin facades for (offset,length) pairs.
 * - TwinPacked packs absolute (start,len) into a Long.
 * - Twin8 packs a small relative offset and a byte length into an Int.
 *
 * These are   value classes that act as zero-cost facades over primitive storage.
 */


inline class TwinPacked(val packed: Long) {
    companion object {
        fun of(start: Int, len: Int): TwinPacked = TwinPacked((start.toLong() shl 32) or (len.toLong() and 0xffffffffL))
    }

    val start: Int get() = (packed ushr 32).toInt()
    val len: Int get() = (packed and 0xffffffffL).toInt()

    fun toPair(): Pair<Int, Int> = start to len
    override fun toString(): String = "TwinPacked(start=$start,len=$len)"
}


inline class Twin8(val packed: Int) {
    companion object {
        fun of(offset: Int, len: Int): Twin8 {
            require(len in 0..0xFF) { "len must fit in 8 bits" }
            return Twin8((offset shl 8) or (len and 0xFF))
        }
    }

    val offset: Int get() = packed ushr 8
    val len: Int get() = packed and 0xFF

    fun toPacked(baseStart: Int): TwinPacked = TwinPacked.of(baseStart + offset, len)
    override fun toString(): String = "Twin8(offset=$offset,len=$len)"
}
