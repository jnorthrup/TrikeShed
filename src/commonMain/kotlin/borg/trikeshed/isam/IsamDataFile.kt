package borg.trikeshed.isam

import borg.trikeshed.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec

import borg.trikeshed.userspace.nio.file.spi.FileOperations

expect class IsamDataFile(
    datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    metafile: IsamMetaFileReader,
    fileOps: FileOperations,
) : Usable, Cursor {
    override val a: Int
    override val b: (Int) -> RowVec
    val datafileFilename: String
    val metafile: IsamMetaFileReader

    override fun open()
    override fun close()

    companion object {
        fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int> = emptyMap(), fileOps: FileOperations)

           fun append(
               msf: Iterable<RowVec>,
               datafilename: String,
               varChars: Map<String, Int> = emptyMap(),
               transform: ((RowVec) -> RowVec)? = null,
               fileOps: FileOperations,
           )

    }
}
