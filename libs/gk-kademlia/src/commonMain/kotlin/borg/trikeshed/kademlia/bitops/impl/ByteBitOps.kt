package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
import kotlin.experimental.and
import kotlin.experimental.xor
object ByteBitOps : BitOps<Byte> {
    override val one: Byte = 1.toByte()
    override fun nextInt(bits: Int): Int = kotlin.random.Random.nextInt(bits)
    override val xor: (Byte, Byte) -> Byte = { a: Byte, b: Byte -> (a.toInt() xor b.toInt()).toByte() }
    override val and: (Byte, Byte) -> Byte = { a: Byte, b: Byte -> (a.toInt() and b.toInt()).toByte() }
    override val shl: (Byte, Int) -> Byte = { Byte: Byte, i: Int -> Byte.toInt().shl(i).toByte() }
    override val shr: (Byte, Int) -> Byte = { Byte: Byte, i: Int -> Byte.toInt().shr(i).toByte() }
    override val plus: (Byte, Byte) -> Byte = { a, b -> (a + b).toByte() }
    override val minus: (Byte, Byte) -> Byte = { a, b -> (a - b).toByte() }
}
