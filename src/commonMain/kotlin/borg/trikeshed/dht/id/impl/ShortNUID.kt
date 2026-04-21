package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.ShortBitOps
import borg.trikeshed.dht.id.NUID

abstract class ShortNUID(override var id: Short? = null) : borg.trikeshed.dht.id.NUID<Short> {
    override val ops = ShortBitOps
}
