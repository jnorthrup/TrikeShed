package borg.trikeshed.context

import kotlin.enums.EnumEntries

/**
 * Interface for enums that represent bitmasks.
 */
interface BitMasked {
    val mask: UInt get() = 1u shl (this as Enum<*>).ordinal

    fun isAtLeast(other: BitMasked): Boolean = (this as Enum<*>).ordinal >= (other as Enum<*>).ordinal
    fun isAtMost(other: BitMasked): Boolean = (this as Enum<*>).ordinal <= (other as Enum<*>).ordinal
    fun isLessThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal < (other as Enum<*>).ordinal
    fun isGreaterThan(other: BitMasked): Boolean = (this as Enum<*>).ordinal > (other as Enum<*>).ordinal
}
