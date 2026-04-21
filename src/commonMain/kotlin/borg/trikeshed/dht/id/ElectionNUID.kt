package borg.trikeshed.dht.id

import borg.trikeshed.dht.id.impl.ByteNUID
import borg.trikeshed.dht.net.NetMask

class ElectionNUID(id: Byte? = null) : borg.trikeshed.dht.id.impl.ByteNUID(id) {
    override val netmask = _root_ide_package_.borg.trikeshed.dht.net.NetMask.Companion.HotSz
}
