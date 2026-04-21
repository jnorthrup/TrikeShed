package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.IntBitOps
import borg.trikeshed.dht.id.NUID

abstract class IntNUID(override var id: Int? = null) : borg.trikeshed.dht.id.NUID<Int> {
    override val ops = IntBitOps
}
