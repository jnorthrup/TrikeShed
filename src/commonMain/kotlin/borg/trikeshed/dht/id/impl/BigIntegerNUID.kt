package borg.trikeshed.dht.id.impl

import borg.trikeshed.num.BigInt as BigInteger
import borg.trikeshed.platform.bitops.impl.BigIntOps

//import java.math.BigInteger

abstract class BigIntegerNUID(override var id: BigInteger? = null) : borg.trikeshed.dht.id.NUID<BigInteger> {
    override val ops = BigIntOps
}
