package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object UIntBitOps : BitOps<UInt> {
    override val one: UInt = 1u
    override val xor: (UInt, UInt) -> UInt = UInt::xor
    override val and: (UInt, UInt) -> UInt = UInt::and
    override val shl: (UInt, Int) -> UInt = UInt::shl
    override val shr: (UInt, Int) -> UInt = UInt::shr
    override val plus: (UInt, UInt) -> UInt = UInt::plus
    override val minus: (UInt, UInt) -> UInt = UInt::minus
}
