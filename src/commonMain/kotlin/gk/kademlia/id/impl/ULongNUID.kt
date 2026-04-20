package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.ULongBitOps
import gk.kademlia.id.NUID

abstract class ULongNUID(override var id: ULong? = null) : NUID<ULong> {
    override val ops = ULongBitOps
}
