package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps

object UShortBitOps : BitOps<UShort> {
    override val one: UShort = 1.toUShort()
    override val xor: (UShort, UShort) -> UShort = UShort::xor
    override val and: (UShort, UShort) -> UShort = UShort::and
    override val shl: (UShort, Int) -> UShort = { uShort: UShort, i: Int -> uShort.toUInt().shl(i).toUShort() }
    override val shr: (UShort, Int) -> UShort = { uShort: UShort, i: Int -> uShort.toUInt().shr(i).toUShort() }
    override val plus: (UShort, UShort) -> UShort = { a, b -> (a + b).toUShort() }
    override val minus: (UShort, UShort) -> UShort = { a, b -> (a - b).toUShort() }

}