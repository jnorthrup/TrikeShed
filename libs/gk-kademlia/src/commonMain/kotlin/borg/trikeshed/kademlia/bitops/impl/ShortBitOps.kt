package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object ShortBitOps : BitOps<Short> {
    override val one: Short = 1
    override fun nextInt(bits: Int): Int = kotlin.random.Random.nextInt(bits)
    override val xor: (Short, Short) -> Short = { a: Short, b: Short -> (a.toInt() xor b.toInt()).toShort() }
    override val and: (Short, Short) -> Short = { a: Short, b: Short -> (a.toInt() and b.toInt()).toShort() }
    override val shl: (Short, Int) -> Short = { a: Short, n: Int -> (a.toInt().shl(n)).toShort() }
    override val shr: (Short, Int) -> Short = { a: Short, n: Int -> (a.toInt().shr(n)).toShort() }
    override val plus: (Short, Short) -> Short = { a: Short, b: Short -> (a + b).toShort() }
    override val minus: (Short, Short) -> Short = { a: Short, b: Short -> (a - b).toShort() }
}
