package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps

object ULongBitOps : BitOps<ULong> {
    override val one: ULong = 1.toULong()
    override val xor: (ULong, ULong) -> ULong = ULong::xor
    override val and: (ULong, ULong) -> ULong = ULong::and
    override val shl: (ULong, Int) -> ULong = ULong::shl
    override val shr: (ULong, Int) -> ULong = ULong::shr
    override val plus: (ULong, ULong) -> ULong = ULong::plus
    override val minus: (ULong, ULong) -> ULong = ULong::minus
}