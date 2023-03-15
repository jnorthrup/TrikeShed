package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import kotlinx.coroutines.flow.MutableSharedFlow

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename),
) : Usable, Cursor {
    val datafileFilename: String
    val metafile: IsamMetaFileReader

    override fun open()
    override fun close()

    companion object {
        fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int> = emptyMap())

        suspend  fun append(
            msf: MutableSharedFlow<RowVec>,
            datafilename: String,
            varChars: Map<String, Int> = emptyMap(),            transform: ((RowVec) -> RowVec)?
        )

    }
}