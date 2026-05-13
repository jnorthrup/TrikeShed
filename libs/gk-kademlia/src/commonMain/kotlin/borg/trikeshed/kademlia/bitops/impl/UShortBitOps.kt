package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object UShortBitOps : BitOps<UShort> {
    override val one: UShort = 1u
    override val xor: (UShort, UShort) -> UShort = UShort::xor
    override val and: (UShort, UShort) -> UShort = UShort::and
    override val shl: (UShort, Int) -> UShort = UShort::shl
    override val shr: (UShort, Int) -> UShort = UShort::shr
    override val plus: (UShort, UShort) -> UShort = { a, b -> (a + b).toUShort() }
    override val minus: (UShort, UShort) -> UShort = { a, b -> (a - b).toUShort() }
}
