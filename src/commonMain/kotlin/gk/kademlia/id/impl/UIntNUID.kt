package gk.kademlia.id.impl

import borg.trikeshed.platform.bitops.impl.UIntBitOps
import gk.kademlia.id.NUID

abstract class UIntNUID(override var id: UInt? = null) : NUID<UInt> {

    override val ops = UIntBitOps
}
