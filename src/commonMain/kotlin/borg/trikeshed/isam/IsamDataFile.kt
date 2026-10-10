package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.j

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

    /** Leaf fence of [column] (see [IsamDataReader.fence]). */
    fun fence(column: String) = reader.fence(column)

    /** `isam["a", "b"]`: the named columns, reading only their groups (shadows the generic Cursor projection). */
    operator fun get(vararg names: CharSequence): Cursor = reader.recordCount j reader.projection(names.toList())

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