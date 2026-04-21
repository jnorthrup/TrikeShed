package borg.trikeshed.dht.id

import borg.trikeshed.dht.id.impl.ULongNUID
import borg.trikeshed.dht.net.NetMask

class SupportNUID(override var id: ULong? = null) : borg.trikeshed.dht.id.impl.ULongNUID(id) {
    override val netmask = _root_ide_package_.borg.trikeshed.dht.net.NetMask.Companion.CoolSz
}
