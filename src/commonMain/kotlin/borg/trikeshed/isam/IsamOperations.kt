package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader

interface IsamOperations {
    fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader

    fun write(
        cursor: Cursor,
        datafilename: String,
        varChars: Map<String, Int>
    )

    fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?
    )
}

interface IsamDataReader : Usable {
    val recordCount: Int
    val readRow: (Int) -> RowVec
}

expect fun defaultIsamOperations(): IsamOperations
