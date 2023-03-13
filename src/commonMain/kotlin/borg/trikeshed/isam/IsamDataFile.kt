package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename)
) : Usable,Cursor{
    val datafileFilename: String
    val metafile: IsamMetaFileReader

    override fun open()
    override fun close()
    companion object {
        fun write(cursor: Cursor, datafilename: String,varChars:Map<String,Int> = emptyMap())
        fun append(cseq: Iterator<RowVec>, meta:Series<ColumnMeta>, datafilename: String, varChars:Map<String,Int> = emptyMap())
    }
}