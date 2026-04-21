package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.LongBitOps
import borg.trikeshed.dht.id.NUID

abstract class LongNUID(override var id: Long? = null) : borg.trikeshed.dht.id.NUID<Long> {
    override val ops = LongBitOps
}
