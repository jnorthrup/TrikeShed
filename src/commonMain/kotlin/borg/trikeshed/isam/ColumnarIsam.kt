package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.name
import borg.trikeshed.cursor.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.getOrNull
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

private const val ISAM3_LAYOUT_SUFFIX = ".isam3.yaml"

class ColumnarIsam internal constructor(
    private val layout: Isam3Layout,
    private val stores: Series<Isam3StoreHandle>,
    val datafileFilename: String,
    private val viewName: String,
) : Usable, Cursor {
    private val resolvedColumns: Series<Isam3ResolvedColumn> = layout.resolvedColumns(viewName)
    private val storeIndex: Map<String, Isam3StoreHandle> = stores.view.associateBy { it.file.name }

    override val a: Int get() = stores.getOrNull(0)?.rowCount ?: 0

    override val b: (Int) -> RowVec = { rowIndex ->
        require(rowIndex in 0 until a) { "Row index out of bounds: $rowIndex" }
        val values: Series<Any?> = resolvedColumns.size j { columnIndex: Int ->
            val resolved = resolvedColumns[columnIndex]
            val store = storeIndex[resolved.file] ?: error("Missing store ${resolved.file}")
            val begin = rowIndex * store.rowWidth + resolved.begin
            val slice = store.bytes.copyOfRange(begin, begin + resolved.meta.end - resolved.meta.begin)
            resolved.type.createDecoder(resolved.meta.end - resolved.meta.begin)(slice)
        }
        val metas: Series<() -> ColumnMeta> = resolvedColumns.size j { columnIndex: Int ->
            { resolvedColumns[columnIndex].meta as ColumnMeta }
        }
        values.j(metas)
    }

    override fun open() {
        require(Files.exists(datafileFilename)) { "ISAM3 layout missing: $datafileFilename" }
        stores.view.forEach { store ->
            require(Files.exists(store.path)) { "ISAM3 store missing: ${store.path}" }
        }
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
            require(cursor.size > 0) { "ColumnarIsam.write requires at least one row" }
            val baseName = baseNameFor(datafilename)
            val layoutPath = resolveLayoutPath(datafilename)
            val sampleRow = transform?.invoke(cursor.at(0)) ?: cursor.at(0)
            val schemaMeta = sampleRow.size j { index: Int -> sampleRow[index].b() }
            val constraints = IsamMetaFileReader.sanitize(schemaMeta, varChars)
            val layout = Isam3Layout.fromConstraints(baseName, constraints)

            Files.write(layoutPath, layout.render())

            val constraintByName = constraints.toList().associateBy { it.name }
            val indexByName = schemaMeta.toList().withIndex().associate { indexed -> indexed.value.name to indexed.index }
            val rowCount = cursor.size
            val viewRows = (0 until rowCount).map { rowIndex ->
                transform?.invoke(cursor.at(rowIndex)) ?: cursor.at(rowIndex)
            }

            layout.partitions.view.forEach { partition ->
                partition.files.view.forEach { file ->
                    val bytes = ByteArray(rowCount * file.rowWidth)
                    for (rowIndex in 0 until rowCount) {
                        val row = viewRows[rowIndex]
                        for (group in file.groups.view) {
                            for (placement in group.placements.view) {
                                val meta = constraintByName[placement.name] ?: error("Missing constraint for ${placement.name}")
                                val columnIndex = indexByName[placement.name] ?: -1
                                require(columnIndex >= 0) { "Missing column ${placement.name} in cursor" }
                                val encoded = meta.encoder(row[columnIndex].a)
                                require(encoded.size >= placement.width) {
                                    "Encoded column ${meta.name} width ${encoded.size} < declared width ${placement.width}"
                                }
                                val offset = rowIndex * file.rowWidth + placement.begin
                                encoded.copyInto(bytes, offset, 0, placement.width)
                            }
                        }
                    }
                    Files.write(filePath(baseName, file.name), bytes)
                }
            }

            if (indexModifier != IndexModifier.None) {
                val summary = createIndexBlocks(
                    rows = (0 until rowCount).map { cursor.at(it) },
                    constraints = constraints,
                    indexModifier = indexModifier,
                ).second
                Files.write("$layoutPath.idx", summary)
            }
        }

        fun append(
            rows: Iterable<RowVec>,
            datafilename: String,
            indexModifier: IndexModifier = IndexModifier.None,
            transform: ((RowVec) -> RowVec)? = null,
        ) {
            val existing = openColumnarIsam(datafilename)
            val mergedRows = (0 until existing.size).map { existing.at(it) } + rows.map { transform?.invoke(it) ?: it }
            write(mergedRows.toList().toSeries(), datafilename, indexModifier)
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
    val layoutPath = resolveLayoutPath(datafile)
    val layout = readLayout(layoutPath)
    val baseName = baseNameFor(layoutPath)
    val storeHandles = layout.partitions.view.flatMap { partition ->
        partition.files.view.map { file ->
            val path = filePath(baseName, file.name)
            val bytes = Files.readAllBytes(path)
            val rowWidth = file.rowWidth
            require(rowWidth > 0 || bytes.isEmpty()) { "Invalid row width for ${file.name}: $rowWidth" }
            val rowCount = if (rowWidth == 0) 0 else {
                require(bytes.size % rowWidth == 0) { "File ${file.name} byte count mismatch" }
                bytes.size / rowWidth
            }
            Isam3StoreHandle(file, path, bytes, rowCount)
        }
    }.toList()
    val rowCount = storeHandles.firstOrNull()?.rowCount ?: 0
    require(storeHandles.all { it.rowCount == rowCount }) { "Row count mismatch across ISAM3 stores" }
    val stores = storeHandles.toSeries()
    return ColumnarIsam(layout, stores, layoutPath, layout.defaultViewName())
}

fun openColumnarIsam(
    datafile: String,
    metafile: String,
): ColumnarIsam = openColumnarIsam(if (Files.exists(metafile)) metafile else datafile)

fun columnarFrom(cursor: Cursor, datafile: String): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile)
    return openColumnarIsam(datafile)
}

fun columnarFrom(cursor: Cursor, datafile: String, modifier: IndexModifier): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile, indexModifier = modifier)
    return openColumnarIsam(datafile)
}

enum class IndexModifier { None }

fun createIndexBlocks(
    rows: List<RowVec>,
    constraints: Series<RecordMeta>,
    indexModifier: IndexModifier,
): Pair<ByteArray, ByteArray> = Pair(ByteArray(0), ByteArray(0))

private fun readLayout(layoutPath: String): Isam3Layout {
    val lines = Files.readAllLines(layoutPath)
    val first = lines.firstOrNull()?.trim() ?: error("Missing layout file: $layoutPath")
    return when {
        first == "isam: 3" -> Isam3Layout.read(layoutPath)
        first.startsWith("# trikeshed-columnar-isam-v1") -> error("Legacy columnar layout is not supported by the new isam:3 writer")
        else -> error("Unrecognized ISAM layout: $layoutPath")
    }
}

private fun resolveLayoutPath(base: String): String = when {
    base.endsWith(".yaml") || base.endsWith(".yml") -> base
    else -> "$base$ISAM3_LAYOUT_SUFFIX"
}

private fun baseNameFor(layoutPath: String): String = when {
    layoutPath.endsWith(ISAM3_LAYOUT_SUFFIX) -> layoutPath.removeSuffix(ISAM3_LAYOUT_SUFFIX)
    layoutPath.endsWith(".yaml") -> layoutPath.removeSuffix(".yaml")
    layoutPath.endsWith(".yml") -> layoutPath.removeSuffix(".yml")
    else -> layoutPath
}

private fun filePath(baseName: String, fileName: String): String = "$baseName.$fileName.col"

internal data class Isam3StoreHandle(
    val file: Isam3File,
    val path: String,
    val bytes: ByteArray,
    val rowCount: Int,
) {
    val rowWidth: Int get() = file.rowWidth
}
