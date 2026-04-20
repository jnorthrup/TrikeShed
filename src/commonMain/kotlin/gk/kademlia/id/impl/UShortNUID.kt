package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.UShortBitOps
import gk.kademlia.id.NUID

abstract class UShortNUID(override var id: UShort? = null) : NUID<UShort> {
    override val ops = UShortBitOps
}
