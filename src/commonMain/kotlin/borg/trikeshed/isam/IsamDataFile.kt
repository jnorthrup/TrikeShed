package borg.trikeshed.isam

import borg.trikeshed.lib.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec

import borg.trikeshed.userspace.nio.file.spi.FileOperations

expect class IsamDataFile(
    datafileFilename: CharSequence,
    metafileFilename: CharSequence = "$datafileFilename.meta",
    metafile: IsamMetaFileReader,
    fileOps: FileOperations,
) : Usable, Cursor {
    override val a: Int
    override val b: (Int) -> RowVec
    val datafileFilename: CharSequence
    val metafile: IsamMetaFileReader

    override fun open()
    override fun close()

    companion object {
        fun write(cursor: Cursor, datafilename: CharSequence, varChars: Map<CharSequence, Int> = emptyMap(), fileOps: FileOperations)

           fun append(
               msf: Iterable<RowVec>,
               datafilename: CharSequence,
               varChars: Map<CharSequence, Int> = emptyMap(),
               transform: ((RowVec) -> RowVec)? = null,
               fileOps: FileOperations,
           )

    }
}
