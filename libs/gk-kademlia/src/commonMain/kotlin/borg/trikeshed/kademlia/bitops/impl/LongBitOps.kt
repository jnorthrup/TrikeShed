package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object LongBitOps : BitOps<Long> {
    override val one: Long = 1
    override fun nextInt(bits: Int): Int = kotlin.random.Random.nextInt(bits)
    override val xor: (Long, Long) -> Long = Long::xor
    override val and: (Long, Long) -> Long = Long::and
    override val shl: (Long, Int) -> Long = Long::shl
    override val shr: (Long, Int) -> Long = Long::shr
    override val plus: (Long, Long) -> Long = Long::plus
    override val minus: (Long, Long) -> Long = Long::minus
}
