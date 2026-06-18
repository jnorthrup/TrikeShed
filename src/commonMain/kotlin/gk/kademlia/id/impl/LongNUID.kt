package gk.kademlia.id.impl

import gk.kademlia.bitops.impl.LongBitOps
import gk.kademlia.id.NUID

abstract class LongNUID(override var id: Long? = null) : NUID<Long> {
    override val ops = LongBitOps
}