package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.ByteBitOps
import gk.kademlia.id.NUID

abstract class ByteNUID(override var id: Byte? = null) : NUID<Byte> {
    override val ops = ByteBitOps
}
