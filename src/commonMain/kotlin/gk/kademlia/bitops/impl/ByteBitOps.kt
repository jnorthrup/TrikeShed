package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps
import kotlin.experimental.and
import kotlin.experimental.xor

object ByteBitOps : BitOps<Byte> {
    override val one: Byte = 1.toByte()
    override val xor: (Byte, Byte) -> Byte = Byte::xor
    override val and: (Byte, Byte) -> Byte = Byte::and
    override val shl: (Byte, Int) -> Byte = { Byte: Byte, i: Int -> Byte.toInt().shl(i).toByte() }
    override val shr: (Byte, Int) -> Byte = { Byte: Byte, i: Int -> Byte.toInt().shr(i).toByte() }
    override val plus: (Byte, Byte) -> Byte = { a, b -> (a + b).toByte() }
    override val minus: (Byte, Byte) -> Byte = { a, b -> (a - b).toByte() }
}