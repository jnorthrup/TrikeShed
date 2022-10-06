package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.PlatformCodec
import borg.trikeshed.isam.meta.PlatformCodec.createDecoder
import borg.trikeshed.isam.meta.PlatformCodec.createEncoder

data class RecordMeta(
    override val name: String,
    override val type: IOMemento,
    val begin: Int =-1,
    val end: Int = -1,
    val decoder: (ByteArray) -> Any? = createDecoder(type, end - begin),
    val encoder: (Any?) -> ByteArray = createEncoder(type, end - begin)
) : ColMeta
