package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.UShortBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class UShortNUID(override var id: UShort? = null) : NUID<UShort> {
    override val ops = UShortBitOps
}
