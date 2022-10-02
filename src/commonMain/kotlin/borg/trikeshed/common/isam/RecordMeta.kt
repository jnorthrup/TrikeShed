package borg.trikeshed.common.isam

import borg.trikeshed.common.isam.meta.IOMemento

data class   RecordMeta(
  val name: String,
  val type: IOMemento,
  val begin: Int,
  val end: Int,
  val decoder: (ByteArray ) -> Any?,
  val encoder: (Any?) -> ByteArray
)

