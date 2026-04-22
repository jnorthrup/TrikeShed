package borg.trikeshed.dht.id

import borg.trikeshed.dht.id.impl.ByteNUID
import borg.trikeshed.dht.net.NetMask

class WorkerNUID(id: Byte? = null) : borg.trikeshed.dht.id.impl.ByteNUID(id) {
    override val netmask = borg.trikeshed.dht.net.NetMask.Companion.WarmSz
}
