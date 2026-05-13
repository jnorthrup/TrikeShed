package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.UIntBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class UIntNUID(override var id: UInt? = null) : NUID<UInt> {
    override val ops = UIntBitOps
}
