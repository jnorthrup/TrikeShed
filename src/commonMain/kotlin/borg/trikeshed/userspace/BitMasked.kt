package borg.trikeshed.userspace

interface BitMasked {
    val ordinal: Int
    val mask: UInt get() = 1u shl ordinal
}
