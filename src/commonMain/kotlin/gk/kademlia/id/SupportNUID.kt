package gk.kademlia.id

import gk.kademlia.id.impl.ULongNUID
import gk.kademlia.net.NetMask

class SupportNUID(override var id: ULong? = null) : ULongNUID(id) {
    override val netmask = NetMask.Companion.CoolSz
}