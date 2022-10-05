package borg.trikeshed.common.isam

import borg.trikeshed.common.isam.meta.IOMemento

data class RecordMeta(
    val name: String,
    val type: IOMemento,
    val begin: Int = TODO(),
    val end: Int = TODO(),
    val decoder: (ByteArray) -> Any? = TODO(),
    val encoder: (Any?) -> ByteArray = TODO()
)
