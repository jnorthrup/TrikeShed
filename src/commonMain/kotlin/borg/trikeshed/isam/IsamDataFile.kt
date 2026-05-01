package borg.trikeshed.isam

import borg.trikeshed.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.CursorTensorSnapshot
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename),
) : Usable, Cursor {
    override val a: Int
    override val b: (Int) -> RowVec
    val datafileFilename: String
    val metafile: IsamMetaFileReader

    override fun open()
    override fun close()

    companion object {
        fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int> = emptyMap())

           fun append(
               msf: Iterable<RowVec>,
               datafilename: String,
               varChars: Map<String, Int> = emptyMap(),
               transform: ((RowVec) -> RowVec)? = null,
           )

    }
}
