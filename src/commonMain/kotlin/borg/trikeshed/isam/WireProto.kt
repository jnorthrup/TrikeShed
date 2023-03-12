package borg.trikeshed.isam

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.get
import borg.trikeshed.lib.left

object WireProto{
    fun writeToWire(
        rowVec:RowVec,
        rowBuf: ByteArray,
        meta: Array<RecordMeta>,
    ): ByteArray {
        val rowData = rowVec.left

        for (x in 0 until  meta.size) {
            val colMeta: RecordMeta = meta[x]
            val colData: Any = rowData[x]

            val pos=(colMeta.begin)

            // val debugMe = colMeta::encoder
            val colBytes = colMeta.encoder(colData)

            colBytes.copyInto(rowBuf, pos, 0, colBytes.size)


            if (meta[x].type.networkSize == null && pos + colBytes.size < meta[x].end)
                rowBuf[pos + colBytes.size] = 0
        }
      return   rowBuf
    }
}