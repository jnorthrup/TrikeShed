package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.meta
import borg.trikeshed.cursor.name
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α

private const val COLUMNAR_MANIFEST = "# trikeshed-columnar-isam-v1"
private const val NO_COLUMN_GROUPS = "-"

/** Single fixed-width column file plus its own metadata. */
data class ColumnarIsamColumn(
    val fileName: String,
    val meta: RecordMeta,
    val width: Int,
    val bytes: ByteArray,
)

/**
 * Common columnar ISAM: a row cursor assembled from a Join/Series of single-column files.
 *
 * Creation writes one data file and one `.meta` file per column. The manifest at
 * [datafileFilename] only records row count and column file names. This keeps the
 * hot IO shape column-native: consumers can scan one descriptor per requested
 * column instead of dragging a row descriptor through every read.
 */
class ColumnarIsam internal constructor(
    private val rowCount: Int,
    val columns: Series<ColumnarIsamColumn>,
    val datafileFilename: String,
    val indexModifier: IndexModifier,
    val columnGroups: String = "",
) : Usable, Cursor {
    override val a: Int get() = rowCount

    override val b: (Int) -> RowVec = { rowIndex ->
        require(rowIndex in 0 until rowCount) { "Row index out of bounds: $rowIndex" }
        columns.size j { columnIndex: Int ->
            val column = columns[columnIndex]
            val offset = rowIndex * column.width
            val slice = column.bytes.copyOfRange(offset, offset + column.width)
            column.meta.decoder(slice) j { column.meta }
        }
    }

    override fun open() {
        require(Files.exists(datafileFilename)) { "Columnar manifest missing: $datafileFilename" }
    }

    override fun close() = Unit

    companion object {
        fun write(
            cursor: Cursor,
            datafilename: String,
            indexModifier: IndexModifier = IndexModifier.None,
            varChars: Map<String, Int> = emptyMap(),
            transform: ((RowVec) -> RowVec)? = null,
        ) {
            val rowCount = cursor.size
            val constraints = buildColumnMeta(cursor, transform, varChars)
            val columnFiles = MutableList(constraints.size) { columnIndex: Int ->
                columnFileName(datafilename, columnIndex, constraints[columnIndex].name)
            }

            for (columnIndex in 0 until constraints.size) {
                val meta = constraints[columnIndex]
                val width = meta.end - meta.begin
                val columnBytes = ByteArray(rowCount * width)
                for (rowIndex in 0 until rowCount) {
                    val row = transform?.invoke(cursor.at(rowIndex)) ?: cursor.at(rowIndex)
                    val encoded = meta.encoder(row[columnIndex].a)
                    require(encoded.size == width) {
                        "Encoded column ${meta.name} width ${encoded.size} != declared width $width"
                    }
                    encoded.copyInto(columnBytes, rowIndex * width)
                }
                val columnFile = columnFiles[columnIndex]
                Files.write(columnFile, columnBytes)
                IsamMetaFileReader.write("$columnFile.meta", 1 j { _: Int -> meta }, varChars)
            }

            val manifest = buildList {
                add(COLUMNAR_MANIFEST)
                add(rowCount.toString())
                add(constraints.size.toString())
                add(NO_COLUMN_GROUPS)
                columnFiles.forEach(::add)
            }
            Files.write(datafilename, manifest)

            if (indexModifier != IndexModifier.None) {
                val summary = createIndexBlocks((0 until rowCount).map { cursor.at(it) }, constraints, indexModifier).second
                Files.write("$datafilename.idx", summary)
            }
        }

        fun append(
            rows: Iterable<RowVec>,
            datafilename: String,
            indexModifier: IndexModifier = IndexModifier.None,
            transform: ((RowVec) -> RowVec)? = null,
        ) {
            val existing = openColumnarIsam(datafilename)
            val mergedRows = (0 until existing.size).map { existing.at(it) } +
                rows.map { transform?.invoke(it) ?: it }
            write(mergedRows.toSeries(), datafilename, indexModifier)
        }
    }
}

class ColumnarCursor(
    val isam: ColumnarIsam,
) : Cursor {
    override val a: Int get() = isam.size
    override val b: (Int) -> RowVec get() = isam.b
}

fun openColumnarIsam(datafile: String): ColumnarIsam {
    val lines = Files.readAllLines(datafile)
    require(lines.isNotEmpty() && lines[0] == COLUMNAR_MANIFEST) { "Invalid Columnar ISAM manifest: $datafile" }
    val rowCount = lines.getOrNull(1)?.toIntOrNull() ?: error("Missing row count in $datafile")
    val columnCount = lines.getOrNull(2)?.toIntOrNull() ?: error("Missing column count in $datafile")
    val columnGroups = lines.getOrNull(3)?.takeUnless { it == NO_COLUMN_GROUPS }.orEmpty()
    val columnFiles = lines.drop(4)
    require(columnFiles.size == columnCount) { "Column manifest count mismatch" }

    val columns = columnCount j { columnIndex: Int ->
        val columnFile = columnFiles[columnIndex]
        val metaReader = IsamMetaFileReader("$columnFile.meta")
        val meta = metaReader.constraints[0]
        val bytes = Files.readAllBytes(columnFile)
        val width = meta.end - meta.begin
        require(width > 0) { "Invalid column width for ${meta.name}: $width" }
        require(bytes.size == rowCount * width) { "Column ${meta.name} byte count mismatch" }
        ColumnarIsamColumn(columnFile, meta, width, bytes)
    }

    return ColumnarIsam(rowCount, columns, datafile, IndexModifier.None, columnGroups)
}

fun openColumnarIsam(
    datafile: String,
    metafile: String,
): ColumnarIsam = openColumnarIsam(datafile)

fun columnarFrom(cursor: Cursor, datafile: String): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile)
    return openColumnarIsam(datafile)
}

fun columnarFrom(cursor: Cursor, datafile: String, modifier: IndexModifier): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile, indexModifier = modifier)
    return openColumnarIsam(datafile)
}

enum class IndexModifier { None }

fun buildColumnMeta(
    cursor: Cursor,
    transform: ((RowVec) -> RowVec)? = null,
    varChars: Map<String, Int> = emptyMap(),
): Series<RecordMeta> {
    val meta: Series<ColumnMeta> = if (transform != null && cursor.size > 0) {
        val row = transform(cursor.at(0))
        row.size j { index: Int -> row[index].b() }
    } else {
        cursor.meta
    }
    return IsamMetaFileReader.sanitize(meta, varChars)
}

fun createIndexBlocks(
    rows: List<RowVec>,
    constraints: Series<RecordMeta>,
    indexModifier: IndexModifier,
): Pair<ByteArray, ByteArray> = Pair(ByteArray(0), ByteArray(0))

private fun columnFileName(prefix: String, index: Int, name: String): String =
    "$prefix.${index.toString().padStart(4, '0')}.${safeFileSegment(name)}.col"

private fun safeFileSegment(value: String): String = buildString(value.length) {
    for (char in value) {
        append(if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_')
    }
}
