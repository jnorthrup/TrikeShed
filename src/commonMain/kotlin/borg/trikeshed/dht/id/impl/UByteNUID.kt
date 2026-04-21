package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.UByteBitOps
import borg.trikeshed.dht.id.NUID

abstract class UByteNUID(override var id: UByte? = null) : borg.trikeshed.dht.id.NUID<UByte> {
    override val ops = UByteBitOps
}
