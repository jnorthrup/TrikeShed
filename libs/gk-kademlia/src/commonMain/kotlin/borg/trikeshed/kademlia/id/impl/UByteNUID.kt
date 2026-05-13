package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.UByteBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class UByteNUID(override var id: UByte? = null) : NUID<UByte> {
    override val ops = UByteBitOps
}
