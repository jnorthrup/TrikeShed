package borg.literbike.betanet

/**
 * Columnar data layout on top of memory-mapped files.
 * Ported from literbike/src/betanet/columnar_mmap.rs.
 */

/**
 * Represents the type of data in a column.
 * A direct port of IOMemento from the Kotlin columnar project.
 */
enum class ColumnType(val size: Int) {
    Int32(4),
    Int64(8),
    Float32(4),
    Float64(8),
    Timestamp(8); // Nanosecond precision timestamp
}

/**
 * Metadata for a single column.
 */
data class ColumnMetadata(
    val name: String,
    val colType: ColumnType
)

/**
 * Defines the schema for a table stored in a memory-mapped file.
 */
data class TableSchema(
    val magic: ByteArray = byteArrayOf(0x43, 0x4F, 0x4C, 0x53), // "COLS"
    val version: Int = 1,
    val rowCount: Int = 0,
    val columnCount: Int = 0,
    val columns: List<ColumnMetadata> = emptyList()
) {
    fun getRowSize(): Int = columns.sumOf { it.colType.size }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TableSchema) return false
        return magic.contentEquals(other.magic) &&
                version == other.version &&
                rowCount == other.rowCount &&
                columnCount == other.columnCount &&
                columns == other.columns
    }

    override fun hashCode(): Int {
        var result = magic.contentHashCode()
        result = 31 * result + version
        result = 31 * result + rowCount
        result = 31 * result + columnCount
        result = 31 * result + columns.hashCode()
        return result
    }
}

/**
 * A handle to a memory-mapped columnar table.
 * Provides access to columns and rows without heap allocation.
 */
class MmapColumnarTable(
    private val cursor: MmapCursor,
    private val schema: TableSchema
) {

    companion object {
        /**
         * Opens a memory-mapped file and treats it as a columnar table.
         */
        fun open(path: String, indexPath: String): Result<MmapColumnarTable> {
            return runCatching {
                val cursor = MmapCursor(path, indexPath)
                // Schema would be read from file header in a full implementation
                // For now, require it to be provided
                val schema = TableSchema()
                if (!schema.magic.contentEquals(byteArrayOf(0x43, 0x4F, 0x4C, 0x53))) {
                    throw IllegalStateException("Invalid file format")
                }
                MmapColumnarTable(cursor, schema)
            }
        }
    }

    fun rowCount(): Int = schema.rowCount

    fun columnCount(): Int = schema.columnCount

    /**
     * Calculate cell offset for a given row and column.
     */
    fun getCellOffset(rowIdx: Int, colIdx: Int): Int {
        var colOffset = 0
        for (i in 0 until colIdx) {
            colOffset += schema.columns[i].colType.size
        }
        val rowSize = schema.getRowSize()
        return rowIdx * rowSize + colOffset
    }

    /**
     * Read a u32 value from a specific cell.
     */
    fun getInt32(rowIdx: Int, colIdx: Int): Int? {
        val offset = getCellOffset(rowIdx, colIdx)
        val buf = cursor.seek(offset.toLong()) ?: return null
        return buf.int
    }

    /**
     * Read a u64 value from a specific cell.
     */
    fun getInt64(rowIdx: Int, colIdx: Int): Long? {
        val offset = getCellOffset(rowIdx, colIdx)
        val buf = cursor.seek(offset.toLong()) ?: return null
        return buf.long
    }

    /**
     * Read a f64 value from a specific cell.
     */
    fun getFloat64(rowIdx: Int, colIdx: Int): Double? {
        val offset = getCellOffset(rowIdx, colIdx)
        val buf = cursor.seek(offset.toLong()) ?: return null
        return buf.double
    }

    fun close() {
        cursor.close()
    }
}
