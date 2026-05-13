package borg.trikeshed.kademlia.id
import borg.trikeshed.kademlia.id.impl.ByteNUID
import borg.trikeshed.kademlia.net.NetMask
class ElectionNUID(id: Byte? = null) : ByteNUID(id) {
    override val netmask = NetMask.Companion.WarmSz
}
