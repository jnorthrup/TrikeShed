package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.LongBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class LongNUID(override var id: Long? = null) : NUID<Long> {
    override val ops = LongBitOps
}
