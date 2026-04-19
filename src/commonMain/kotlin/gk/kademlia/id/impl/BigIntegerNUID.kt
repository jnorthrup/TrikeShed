package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.BigIntOps
import gk.kademlia.id.NUID
import borg.trikeshed.num.BigInt as BigInteger

//import java.math.BigInteger

abstract class BigIntegerNUID(override var id: BigInteger? = null) : NUID<BigInteger> {
    override val ops = BigIntOps
}
