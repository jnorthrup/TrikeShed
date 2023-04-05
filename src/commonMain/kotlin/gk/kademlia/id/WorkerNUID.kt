package gk.kademlia.id

import gk.kademlia.id.impl.ByteNUID
import gk.kademlia.net.NetMask

class WorkerNUID(id: Byte? = null) : ByteNUID(id) {
    override val netmask = NetMask.Companion.WarmSz
}