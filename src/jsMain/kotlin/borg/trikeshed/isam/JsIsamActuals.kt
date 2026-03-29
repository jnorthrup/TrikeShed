package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join

actual class IsamDataFile actual constructor(
    datafileFilename: String,
    metafileFilename: String,
    metafile: IsamMetaFileReader,
) : Usable, Cursor {
    actual val datafileFilename: String = datafileFilename
    actual val metafile: IsamMetaFileReader = metafile

    private val recordlen: Int get() = metafile.recordlen

    actual override val a: Int
        get() = if (Files.exists(datafileFilename) && recordlen > 0) {
            Files.readAllBytes(datafileFilename).size / recordlen
        } else {
            0
        }

    actual override val b: (Int) -> Join<Int, (Int) -> Join<Any?, () -> ColumnMeta>> = { _ ->
        TODO("ISAM row access is not implemented for JS yet")
    }

    actual override fun open() {
        metafile.open()
    }

    actual override fun close() {
        metafile.close()
    }

    actual companion object {
        actual fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
            TODO("ISAM write is not implemented for JS yet")
        }

        actual fun append(
            msf: Iterable<RowVec>,
            datafilename: String,
            varChars: Map<String, Int>,
            transform: ((RowVec) -> RowVec)?,
        ): Unit {
            TODO("ISAM append is not implemented for JS yet")
        }
    }
}
