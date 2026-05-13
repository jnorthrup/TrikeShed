package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.ULongBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class ULongNUID(override var id: ULong? = null) : NUID<ULong> {
    override val ops = ULongBitOps
}
