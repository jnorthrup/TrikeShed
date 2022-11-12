package borg.trikeshed.parse

import borg.trikeshed.lib.Twin
import kotlin.jvm.JvmInline

/**
 * a versatile range of two unsigned shorts stored as a 32 bit Int value as Inline class
 */
@JvmInline
value class DelimitRange(val value: Int) : Twin<UShort>,ClosedRange<UShort> {
    //emulates a pair of UShorts using 16 bits for two UShorts
    override val a: UShort get() = (value ushr 16).toUShort()
    override val b: UShort get() = (value and 0xFFFF).toUShort()
    companion object {
        fun of(a: UShort, b: UShort): DelimitRange = DelimitRange((a.toInt() shl 16) or b.toInt())
    }

    override val start: UShort
        get() = a
    override val endInclusive: UShort
        get() = b.dec()

    val asIntRange: IntRange get()= (a.toInt() until b.toInt())
}


