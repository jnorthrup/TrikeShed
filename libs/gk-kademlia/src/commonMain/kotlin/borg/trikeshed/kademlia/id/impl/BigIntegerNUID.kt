package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.BigIntOps
import borg.trikeshed.kademlia.id.NUID
import borg.trikeshed.num.BigInt as BigInteger
abstract class BigIntegerNUID(override var id: BigInteger? = null) : NUID<BigInteger> {
    override val ops = BigIntOps
}
