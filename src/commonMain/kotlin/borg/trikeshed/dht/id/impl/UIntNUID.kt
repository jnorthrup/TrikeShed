package borg.trikeshed.dht.id.impl

import borg.trikeshed.platform.bitops.impl.UIntBitOps
import borg.trikeshed.dht.id.NUID

abstract class UIntNUID(override var id: UInt? = null) : borg.trikeshed.dht.id.NUID<UInt> {

    override val ops = UIntBitOps
}
