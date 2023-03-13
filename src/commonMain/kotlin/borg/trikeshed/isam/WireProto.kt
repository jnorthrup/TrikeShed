package borg.trikeshed.isam

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.left
import borg.trikeshed.lib.size

object WireProto{
    fun writeToBuffer(
        rowVec:RowVec,
        rowBuf: ByteArray,
        meta: Series<RecordMeta>,
    ): ByteArray {
        val rowData = rowVec.left

        for (x in 0 until  meta.size) {
            val colMeta: RecordMeta = meta[x]
            val colData  = rowData[x]

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