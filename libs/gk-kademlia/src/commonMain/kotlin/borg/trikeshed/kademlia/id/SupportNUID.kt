package borg.trikeshed.kademlia.id
import borg.trikeshed.kademlia.id.impl.ULongNUID
import borg.trikeshed.kademlia.net.NetMask
class SupportNUID(id: ULong? = null) : ULongNUID(id) {
    override val netmask = NetMask.Companion.CoolSz
}
