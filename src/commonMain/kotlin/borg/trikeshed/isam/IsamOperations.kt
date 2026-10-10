package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.emptySeriesOf

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

    /** Rows of [names] only, in that order, reading just their column groups. */
    fun projection(names: List<CharSequence>): (Int) -> RowVec

    /** Per leaf of [column]'s group: the leaf's rows and decoded (min, max); empty unless that group is stored as leaves. */
    fun fence(column: String): Series<Join<IntRange, Twin<Any?>>> = emptySeriesOf()
}

fun defaultIsamOperations(): IsamOperations = UringIsamOperations()

fun getGroupFilename(datafilename: String, groupName: String): String {
    val lastSlash = datafilename.lastIndexOf('/')
    val lastBackslash = datafilename.lastIndexOf('\\')
    val separatorIdx = maxOf(lastSlash, lastBackslash)
    val dir = if (separatorIdx >= 0) datafilename.substring(0, separatorIdx + 1) else ""
    val name = if (separatorIdx >= 0) datafilename.substring(separatorIdx + 1) else datafilename
    val base = if (name.endsWith(".bin")) name.removeSuffix(".bin") else name
    return "$dir$base.$groupName.bin"
}
