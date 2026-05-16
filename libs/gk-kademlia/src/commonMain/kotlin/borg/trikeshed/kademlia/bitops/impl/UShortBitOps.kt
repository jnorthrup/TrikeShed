package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object UShortBitOps : BitOps<UShort> {
    override val one: UShort = 1u
    override fun nextInt(bits: Int): Int = kotlin.random.Random.nextInt(bits)
    override val xor: (UShort, UShort) -> UShort = { a: UShort, b: UShort -> (a.toInt() xor b.toInt()).toUShort() }
    override val and: (UShort, UShort) -> UShort = { a: UShort, b: UShort -> (a.toInt() and b.toInt()).toUShort() }
    override val shl: (UShort, Int) -> UShort = { a: UShort, n: Int -> (a.toInt().shl(n)).toUShort() }
    override val shr: (UShort, Int) -> UShort = { a: UShort, n: Int -> (a.toInt().shr(n)).toUShort() }
    override val plus: (UShort, UShort) -> UShort = { a, b -> (a + b).toUShort() }
    override val minus: (UShort, UShort) -> UShort = { a, b -> (a - b).toUShort() }
}
