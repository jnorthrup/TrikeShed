package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps

object IntBitOps : BitOps<Int> {
    override val one: Int = 1.toInt()
    override val xor: (Int, Int) -> Int = Int::xor
    override val and: (Int, Int) -> Int = Int::and
    override val shl: (Int, Int) -> Int = Int::shl
    override val shr: (Int, Int) -> Int = Int::shr
    override val plus: (Int, Int) -> Int = Int::plus
    override val minus: (Int, Int) -> Int = Int::minus
}