package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.BigIntOps
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.num.BigInt as BigInteger

//import java.math.BigInteger

abstract class BigIntegerNUID(override var id: BigInteger? = null) : borg.trikeshed.dht.id.NUID<BigInteger> {
    override val ops = BigIntOps
}
