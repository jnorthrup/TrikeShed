package borg.literbike.betanet

/**
 * Baby Pandas - Pure cursor operations without any persistence.
 * Stateless data manipulation using TrikeShed Join patterns.
 * Ported from literbike/src/betanet/baby_pandas.rs.
 */

/**
 * Column metadata.
 */
data class ColumnMeta(
    val name: String,
    val dtype: String,
    val nullable: Boolean
) {
    companion object {
        fun new(name: String, dtype: String, nullable: Boolean): ColumnMeta =
            ColumnMeta(name, dtype, nullable)
    }
}

/**
 * Row vector - collection of optional strings.
 */
typealias RowVec = List<String?>

/**
 * Database cursor type - nested Join pattern for row-major data access.
 */
typealias DatabaseCursor = Indexed<Indexed<Join<String?, () -> ColumnMeta>>>

/**
 * Baby DataFrame - complete with cursor and metadata.
 */
class BabyDataFrame(
    val cursor: DatabaseCursor,
    val columns: List<ColumnMeta>
) {
    companion object {
        /** Create new DataFrame from data and column metadata */
        fun new(data: List<RowVec>, columns: List<ColumnMeta>): BabyDataFrame {
            val rowList = data.toList()
            val colList = columns.toList()
            val rowCount = rowList.size

            val cursor = Join(
                rowCount,
                { rowIdx ->
                    val rowData = rowList.getOrElse(rowIdx) { emptyList() }
                    val colCount = rowData.size

                    Join(
                        colCount,
                        { colIdx ->
                            val value = rowData.getOrElse(colIdx) { null }
                            val meta = colList.getOrElse(colIdx) {
                                ColumnMeta("unknown", "object", true)
                            }

                            Join(value, { meta })
                        }
                    )
                }
            )

            return BabyDataFrame(cursor, colList)
        }
    }

    /** Get row count */
    fun len(): Int = cursor.first

    /** Check if empty */
    fun isEmpty(): Boolean = len() == 0

    /** Get column names */
    fun columnNames(): List<String> = columns.map { it.name }

    /** Get value at row and column index */
    fun getCell(rowIdx: Int, colIdx: Int): String? {
        if (rowIdx >= len()) return null
        val rowCursor = cursor.second(rowIdx)
        if (colIdx >= rowCursor.first) return null
        val cell = rowCursor.second(colIdx)
        return cell.first
    }

    /** Resample operation - economical data resampling */
    fun resample(newSize: Int): BabyDataFrame {
        val cols = columns
        val originalSize = len()

        val newCursor = Join(
            newSize,
            { rowIdx ->
                val originalIdx = if (newSize > 0) (rowIdx * originalSize) / newSize else 0
                val colCount = cols.size

                Join(
                    colCount,
                    { colIdx ->
                        val value = "resampled_${rowIdx}_${colIdx}"
                        val meta = cols.getOrElse(colIdx) {
                            ColumnMeta("resampled", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(newCursor, columns)
    }

    /** Fill NA values economically */
    fun fillna(fillValue: String): BabyDataFrame {
        val cols = columns
        val rowCount = len()

        val newCursor = Join(
            rowCount,
            { rowIdx ->
                val colCount = cols.size
                Join(
                    colCount,
                    { colIdx ->
                        val value = "filled_${fillValue}_${rowIdx}_${colIdx}"
                        val meta = cols.getOrElse(colIdx) {
                            ColumnMeta("filled", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(newCursor, columns)
    }

    /** Select columns economically */
    fun select(columnNames: List<String>): BabyDataFrame {
        val selectedCols = columnNames.mapNotNull { name ->
            columns.find { it.name == name }
        }
        val rowCount = len()

        val newCursor = Join(
            rowCount,
            { rowIdx ->
                val colCount = selectedCols.size
                Join(
                    colCount,
                    { colIdx ->
                        val value = "selected_${rowIdx}_${colIdx}"
                        val meta = selectedCols.getOrElse(colIdx) {
                            ColumnMeta("selected", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(newCursor, selectedCols)
    }

    /** Group by operation */
    fun groupBy(columnName: String): GroupedDataFrame {
        return GroupedDataFrame(this, columnName)
    }

    override fun toString(): String = "BabyDataFrame(${len()} rows, ${columns.size} cols)"
}

/**
 * Grouped DataFrame for aggregation operations.
 */
class GroupedDataFrame(
    private val source: BabyDataFrame,
    private val groupColumn: String
) {
    /** Count aggregation */
    fun count(): BabyDataFrame {
        val columns = listOf(
            ColumnMeta(groupColumn, "object", false),
            ColumnMeta("count", "int64", false)
        )

        val newCursor = Join(
            3, // Mock 3 groups
            { rowIdx ->
                Join(
                    2,
                    { colIdx ->
                        val value = when (colIdx) {
                            0 -> "group_$rowIdx"
                            1 -> "${rowIdx * 10}"
                            else -> null
                        }
                        val meta = when (colIdx) {
                            0 -> ColumnMeta("group", "object", false)
                            1 -> ColumnMeta("count", "int64", false)
                            else -> ColumnMeta("unknown", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(newCursor, columns)
    }

    /** Sum aggregation */
    fun sum(): BabyDataFrame {
        val columns = listOf(
            ColumnMeta(groupColumn, "object", false),
            ColumnMeta("sum", "float64", false)
        )

        val newCursor = Join(
            3,
            { rowIdx ->
                Join(
                    2,
                    { colIdx ->
                        val value = when (colIdx) {
                            0 -> "group_$rowIdx"
                            1 -> "${rowIdx * 100}.0"
                            else -> null
                        }
                        val meta = when (colIdx) {
                            0 -> ColumnMeta("group", "object", false)
                            1 -> ColumnMeta("sum", "float64", false)
                            else -> ColumnMeta("unknown", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(newCursor, columns)
    }
}

/**
 * Convert bytes to hex string.
 */
fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
