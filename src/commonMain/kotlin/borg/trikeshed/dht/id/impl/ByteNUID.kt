package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.ByteBitOps
import borg.trikeshed.dht.id.NUID

abstract class ByteNUID(override var id: Byte? = null) : borg.trikeshed.dht.id.NUID<Byte> {
    override val ops = ByteBitOps
}
