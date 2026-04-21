package borg.trikeshed.dht.id

import borg.trikeshed.dht.id.impl.ByteNUID
import borg.trikeshed.dht.net.NetMask as NetMask1

class ElectionNUID(id: Byte? = null) : ByteNUID(id) {
    override val netmask = NetMask1.Companion.HotSz
}
