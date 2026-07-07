package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader

class IsamDataFile(
    val datafileFilename: String,
    val metafileFilename: String = "$datafileFilename.meta",
    val metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename),
    private val operations: IsamOperations = defaultIsamOperations()
) : Usable, Cursor {

    private val reader by lazy {
        operations.createReader(datafileFilename, metafileFilename, metafile)
    }

    override val a: Int
        get() = reader.recordCount

    override val b: (Int) -> RowVec
        get() = reader.readRow

    override fun open() {
        reader.open()
    }

    override fun close() {
        reader.close()
    }

    companion object {
        fun write(
            cursor: Cursor,
            datafilename: String,
            varChars: Map<String, Int> = emptyMap(),
            operations: IsamOperations = defaultIsamOperations(),
            useMonocursorGroupings: Boolean = true
        ) {
            operations.write(cursor, datafilename, varChars, useMonocursorGroupings)
        }

        fun append(
            msf: Iterable<RowVec>,
            datafilename: String,
            varChars: Map<String, Int> = emptyMap(),
            transform: ((RowVec) -> RowVec)? = null,
            operations: IsamOperations = defaultIsamOperations(),
            useMonocursorGroupings: Boolean = true
        ) {
            operations.append(msf, datafilename, varChars, transform, useMonocursorGroupings)
        }
    }
}