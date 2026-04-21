package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.ByteBitOps

abstract class ByteNUID(override var id: Byte? = null) : borg.trikeshed.dht.id.NUID<Byte> {
    override val ops = ByteBitOps
}
