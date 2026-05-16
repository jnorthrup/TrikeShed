package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
import borg.trikeshed.num.BigInt
object BigIntOps : BitOps<borg.trikeshed.num.BigInt> {
    override val one: borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt.ONE
    override fun nextInt(bits: Int): Int = kotlin.random.Random.nextInt(bits)
    override val xor: (borg.trikeshed.num.BigInt, borg.trikeshed.num.BigInt) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::xor
    override val and: (borg.trikeshed.num.BigInt, borg.trikeshed.num.BigInt) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::and
    override val shl: (borg.trikeshed.num.BigInt, Int) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::shl
    override val shr: (borg.trikeshed.num.BigInt, Int) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::shr
    override val plus: (borg.trikeshed.num.BigInt, borg.trikeshed.num.BigInt) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::plus
    override val minus: (borg.trikeshed.num.BigInt, borg.trikeshed.num.BigInt) -> borg.trikeshed.num.BigInt = borg.trikeshed.num.BigInt::minus
}
