package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps

object UByteBitOps : BitOps<UByte> {
    override val one: UByte = 1.toUByte()
    override val xor: (UByte, UByte) -> UByte = UByte::xor
    override val and: (UByte, UByte) -> UByte = UByte::and
    override val shl: (UByte, Int) -> UByte = { uByte: UByte, i: Int -> uByte.toUInt().shl(i).toUByte() }
    override val shr: (UByte, Int) -> UByte = { uByte: UByte, i: Int -> uByte.toUInt().shr(i).toUByte() }
    override val plus: (UByte, UByte) -> UByte = { a, b -> (a + b).toUByte() }
    override val minus: (UByte, UByte) -> UByte = { a, b -> (a - b).toUByte() }
}