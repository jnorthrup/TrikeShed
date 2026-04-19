package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.IntBitOps
import gk.kademlia.id.NUID

abstract class IntNUID(override var id: Int? = null) : NUID<Int> {
    override val ops = IntBitOps
}
