package borg.trikeshed.kademlia.bitops.impl
import borg.trikeshed.kademlia.bitops.BitOps
object ShortBitOps : BitOps<Short> {
    override val one: Short = 1
    override val xor: (Short, Short) -> Short = Short::xor
    override val and: (Short, Short) -> Short = Short::and
    override val shl: (Short, Int) -> Short = Short::shl
    override val shr: (Short, Int) -> Short = Short::shr
    override val plus: (Short, Short) -> Short = Short::plus
    override val minus: (Short, Short) -> Short = Short::minus
}
