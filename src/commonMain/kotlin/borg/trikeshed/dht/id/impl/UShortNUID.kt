package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.UShortBitOps
import borg.trikeshed.dht.id.NUID

abstract class UShortNUID(override var id: UShort? = null) : borg.trikeshed.dht.id.NUID<UShort> {
    override val ops = UShortBitOps
}
