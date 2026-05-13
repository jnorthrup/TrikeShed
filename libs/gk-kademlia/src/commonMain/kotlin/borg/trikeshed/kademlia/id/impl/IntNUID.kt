package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.IntBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class IntNUID(override var id: Int? = null) : NUID<Int> {
    override val ops = IntBitOps
}
