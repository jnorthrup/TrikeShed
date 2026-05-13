package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object UByteBitOps : BitOps<UByte> {
    override val one: UByte = 1u
    override val xor: (UByte, UByte) -> UByte = UByte::xor
    override val and: (UByte, UByte) -> UByte = UByte::and
    override val shl: (UByte, Int) -> UByte = { v, i -> (v.toInt() shl i).toUByte() }
    override val shr: (UByte, Int) -> UByte = { v, i -> (v.toInt() shr i).toUByte() }
    override val plus: (UByte, UByte) -> UByte = { a, b -> (a + b).toUByte() }
    override val minus: (UByte, UByte) -> UByte = { a, b -> (a - b).toUByte() }
}
