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
        varChars: Map<String, Int>,
        useMonocursorGroupings: Boolean = true
    )

    fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?,
        useMonocursorGroupings: Boolean = true
    )
}

interface IsamDataReader : Usable {
    val recordCount: Int
    val readRow: (Int) -> RowVec
}

expect fun defaultIsamOperations(): IsamOperations

fun getGroupFilename(datafilename: String, groupName: String): String {
    val lastSlash = datafilename.lastIndexOf('/')
    val lastBackslash = datafilename.lastIndexOf('\\')
    val separatorIdx = maxOf(lastSlash, lastBackslash)
    val dir = if (separatorIdx >= 0) datafilename.substring(0, separatorIdx + 1) else ""
    val name = if (separatorIdx >= 0) datafilename.substring(separatorIdx + 1) else datafilename
    val base = if (name.endsWith(".bin")) name.removeSuffix(".bin") else name
    return "$dir$base.$groupName.bin"
}
