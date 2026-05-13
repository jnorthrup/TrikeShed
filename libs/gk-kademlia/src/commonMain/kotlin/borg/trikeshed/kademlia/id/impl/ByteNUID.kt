package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.ByteBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class ByteNUID(override var id: Byte? = null) : NUID<Byte> {
    override val ops = ByteBitOps
}
