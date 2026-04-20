package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.UByteBitOps
import gk.kademlia.id.NUID

abstract class UByteNUID(override var id: UByte? = null) : NUID<UByte> {
    override val ops = UByteBitOps
}
