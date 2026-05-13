package borg.trikeshed.kademlia.id.impl
import borg.trikeshed.kademlia.bitops.impl.ShortBitOps
import borg.trikeshed.kademlia.id.NUID
abstract class ShortNUID(override var id: Short? = null) : NUID<Short> {
    override val ops = ShortBitOps
}
