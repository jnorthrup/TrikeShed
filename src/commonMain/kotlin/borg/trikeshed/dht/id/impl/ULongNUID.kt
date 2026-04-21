package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.ULongBitOps
import borg.trikeshed.dht.id.NUID

abstract class ULongNUID(override var id: ULong? = null) : borg.trikeshed.dht.id.NUID<ULong> {
    override val ops = ULongBitOps
}
