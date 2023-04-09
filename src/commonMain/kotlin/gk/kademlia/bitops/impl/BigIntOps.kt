package gk.kademlia.bitops.impl

import gk.kademlia.bitops.BitOps
import borg.trikeshed.num.BigInt as BigInteger

object BigIntOps : BitOps<BigInteger> {
    override val one: BigInteger = BigInteger.ONE
    override val xor: (BigInteger, BigInteger) -> BigInteger = BigInteger::xor
    override val and: (BigInteger, BigInteger) -> BigInteger = BigInteger::and
    override val shl: (BigInteger, Int) -> BigInteger = BigInteger::shl
    override val shr: (BigInteger, Int) -> BigInteger = BigInteger::shr
    override val plus: (BigInteger, BigInteger) -> BigInteger = BigInteger::plus
    override val minus: (BigInteger, BigInteger) -> BigInteger = BigInteger::minus
}