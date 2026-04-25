package borg.trikeshed.isam

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.*
import borg.trikeshed.platform.PlatformCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * JVM implementation of the Columnar ISAM. This was moved from commonMain because it uses java.nio APIs.
 * Kept minimal helper stubs for indexing and schema discovery so it compiles cleanly.
 */
class ColumnarIsam internal constructor(
    override val a: Int,
    override val b: (Int) -> RowVec,
    val datafileFilename: String,
    val metafile: IsamMetaFileReader,
    val indexModifier: IndexModifier
) : borg.trikeshed.common.Usable, Cursor {
    init {
        require(a == metafile.constraints.size) { "Column count mismatch" }
    }

    override fun open() {
        require(Files.exists(Path.of(datafileFilename))) { "Data file missing" }
        val data = Files.readAllBytes(Path.of(datafileFilename))
        val view = data.toSeries()
        require(view.size >= 8) { "Header too small" }
    }

    override fun close() { /* no-op for now */ }

    companion object {
        /** Write a ColumnarIsam from an input Cursor. */
        fun write(
            cursor: Cursor,
            datafilename: String,
            indexModifier: IndexModifier = IndexModifier.None,
            varChars: Map<String, Int> = emptyMap(),
            transform: ((RowVec) -> RowVec)? = null
        ) {
            require(cursor.size >= 0) { "Cursor size must be non-negative" }

            // discover schema from cursor (use cursor.meta as a best-effort)
            val constraints: Series<ColumnMeta> = buildColumnMeta(cursor, transform)

            // open/create data file and write column-major data
            val p = Path.of(datafilename)
            Files.createDirectories(p.parent ?: Path.of("."))

            val out = Files.newOutputStream(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                out.use { stream ->
                    // header: numColumns, then per-column begin/end
                    stream.write(PlatformCodec.writeInt(constraints.size))
                    for (c in constraints) {
                        // attempt to coerce begin/end via sanitize later in metafile writer if needed
                        val begin = (c as? RecordMeta)?.begin ?: -1
                        val end = (c as? RecordMeta)?.end ?: -1
                        stream.write(PlatformCodec.writeInt(begin))
                        stream.write(PlatformCodec.writeInt(end))
                    }

                    // serialize rows column-wise
                    for (i in 0 until cursor.size) {
                        val row = transform?.invoke(cursor.at(i)) ?: cursor.at(i)
                        for (ci in 0 until constraints.size) {
                            val col = constraints[ci]
                            val encoder = (col.type as? IOMemento)?.createEncoder((col.type.networkSize ?: 0)) ?: { _: Any? -> ByteArray(0) }
                            val bytes = encoder(row[ci])
                            stream.write(bytes)
                        }
                    }
                }
            } finally {
                // stream auto-closed by use
            }

            // write metadata file via IsamMetaFileReader companion
            IsamMetaFileReader.write(datafilename, constraints, varChars)

            // create index blocks if needed (stubbed)
            if (indexModifier != IndexModifier.None) {
                val rows = (0 until cursor.size).map { cursor.at(it) }
                val (indexData, indexSummary) = createIndexBlocks(rows, constraints, indexModifier)
                Files.writeString(Path.of("$datafilename.idx"), indexSummary.decodeToString())
            }
        }

        /** Append rows to an existing columnar store (same schema). */
        fun append(
            rows: Iterable<RowVec>,
            datafilename: String,
            indexModifier: IndexModifier = IndexModifier.None,
            transform: ((RowVec) -> RowVec)? = null
        ) {
            val p = Path.of(datafilename)
            require(Files.exists(p)) { "Cannot append to non-existent ColumnarIsam: $datafilename" }
            val existing = IsamMetaFileReader(datafilename + ".meta")
            val constraints = existing.constraints
            require(constraints.isNotEmpty()) { "Cannot append to empty schema" }

            val out = Files.newOutputStream(p, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            try {
                out.use { stream ->
                    for (row in rows) {
                        val r = transform?.invoke(row) ?: row
                        for (ci in 0 until constraints.size) {
                            val col = constraints[ci]
                            val encoder = (col.type as? IOMemento)?.createEncoder((col.type.networkSize ?: 0)) ?: { _: Any? -> ByteArray(0) }
                            stream.write(encoder(r[ci]))
                        }
                    }
                }
            } finally {
                // closed by use
            }
        }
    }
}

/** Simple column-major row view backed by a ColumnarIsam. */
class ColumnarCursor(
    private val isam: ColumnarIsam
) : Cursor {
    override val a: Int get() = isam.a
    override val b: (Int) -> RowVec get() = { index ->
        require(index in 0 until a) { "Index out of bounds" }
        val cells = (0 until isam.a).map { col ->
            val constraint = isam.metafile.constraints[col]
            val width = constraint.end - constraint.begin
            val offset = 8 + col * 8 + index * width
            val data = Files.readAllBytes(Path.of(isam.datafileFilename))
            val slice = data.sliceArray(offset until (offset + width))
            constraint.decoder(slice)
        }
        cells.toSeries().j(isam.metafile.constraints.α { c -> { c } })
    }
}

/** Build ColumnarIsam from an existing data+meta pair (open). */
fun openColumnarIsam(
    datafile: String,
    metafile: String = "$datafile.meta"
): ColumnarIsam {
    val reader = IsamMetaFileReader(metafile)
    val data = Files.readAllBytes(Path.of(datafile))
    val numColumns = PlatformCodec.readInt(data.copyOfRange(0, 4))
    val recordLen = reader.recordlen
    val numRows = if (recordLen > 0) (data.size - 8 - numColumns * 8) / recordLen else 0

    val constraints = reader.constraints
    require(constraints.size == numColumns) { "Constraint count mismatch" }

    val accessor: (Int) -> RowVec = { idx ->
        val cells = (0 until constraints.size).map { col ->
            val c = constraints[col]
            val width = c.end - c.begin
            val offset = 8 + col * 8 + idx * width
            val slice = data.sliceArray(offset until (offset + width))
            c.decoder(slice)
        }
        cells.toSeries().j(constraints.α { c -> { c } })
    }

    return ColumnarIsam(
        a = numColumns,
        b = accessor,
        datafileFilename = datafile,
        metafile = reader,
        indexModifier = IndexModifier.None
    )
}

fun columnarFrom(cursor: Cursor, datafile: String): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile)
    return openColumnarIsam(datafile)
}

fun columnarFrom(cursor: Cursor, datafile: String, modifier: IndexModifier): ColumnarIsam {
    ColumnarIsam.write(cursor, datafile, indexModifier = modifier)
    return openColumnarIsam(datafile)
}

/** Minimal IndexModifier enum (stubbed). */
enum class IndexModifier { None }

/** Minimal helpers used by ColumnarIsam (stubs) */
fun buildColumnMeta(cursor: Cursor, transform: ((RowVec) -> RowVec)? = null): Series<ColumnMeta> = cursor.meta

fun createIndexBlocks(rows: List<RowVec>, constraints: Series<ColumnMeta>, indexModifier: IndexModifier): Pair<ByteArray, ByteArray> = Pair(ByteArray(0), ByteArray(0))
