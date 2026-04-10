package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps
import kotlin.experimental.and
import kotlin.experimental.xor

object ShortBitOps : BitOps<Short> {
    override val one: Short = 1.toShort()
    override val xor: (Short, Short) -> Short = Short::xor
    override val and: (Short, Short) -> Short = Short::and
    override val shl: (Short, Int) -> Short = { Short: Short, i: Int -> Short.toUInt().shl(i).toShort() }
    override val shr: (Short, Int) -> Short = { Short: Short, i: Int -> Short.toUInt().shr(i).toShort() }
    override val plus: (Short, Short) -> Short = { a, b -> (a + b).toShort() }
    override val minus: (Short, Short) -> Short = { a, b -> (a - b).toShort() }
}