package borg.trikeshed.dht.id

import borg.trikeshed.dht.id.impl.ULongNUID
import borg.trikeshed.dht.net.NetMask.*

class SupportNUID(override var id: ULong? = null) : ULongNUID(id) {
    override val netmask = Companion.CoolSz
}
