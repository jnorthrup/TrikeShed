package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.ShortBitOps
import gk.kademlia.id.NUID

abstract class ShortNUID(override var id: Short? = null) : NUID<Short> {
    override val ops = ShortBitOps
}
