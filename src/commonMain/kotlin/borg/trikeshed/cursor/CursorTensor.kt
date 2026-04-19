package borg.trikeshed.cursor

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.*
import kotlin.collections.forEach

/**
 * Compile-time constants for cursor/tensor layouts.
 *
 * These are deliberately static so the Kotlin/Wasm backend can keep the
 * tensor contract simple and predictable for the optimizer.
 */
object CursorTensorLayout {
    const val DOUBLE_BYTES = 8
    const val TILE_BYTES = 512
    const val TILE_DOUBLES = TILE_BYTES / DOUBLE_BYTES
    const val DEFAULT_FEATURE_COUNT = 6
    const val TYPE_UNKNOWN = -1
}

/**
 * Dense, row-major numeric snapshot reified from a Cursor.
 *
 * The metadata remains attached for semantic access, while the hot-path data
 * stays in a contiguous DoubleArray for Wasm/SIMD-friendly execution.
 */
data class CursorTensorSnapshot(
    val rowCount: Int,
    val columnCount: Int,
    val values: DoubleArray,
    val columnNames: Series<String>,
    val sourceColumnIndices: IntArray,
    val columnTypeCodes: IntArray,
) {
    init {
        require(rowCount >= 0) { "rowCount must be >= 0" }
        require(columnCount >= 0) { "columnCount must be >= 0" }
        require(values.size == rowCount * columnCount) {
            "values size must equal rowCount * columnCount"
        }
        require(columnNames.size == columnCount) { "columnNames size must equal columnCount" }
        require(sourceColumnIndices.size == columnCount) { "sourceColumnIndices size must equal columnCount" }
        require(columnTypeCodes.size == columnCount) { "columnTypeCodes size must equal columnCount" }
    }

    fun index(row: Int, column: Int): Int {
        require(row in 0 until rowCount) { "row must be in [0, ${rowCount - 1}]" }
        require(column in 0 until columnCount) { "column must be in [0, ${columnCount - 1}]" }
        return row * columnCount + column
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun row(row: Int): DoubleArray {
        require(row in 0 until rowCount) { "row must be in [0, ${rowCount - 1}]" }
        return DoubleArray(columnCount) { column -> this[row, column] }
    }

    fun column(column: Int): DoubleArray {
        require(column in 0 until columnCount) { "column must be in [0, ${columnCount - 1}]" }
        return DoubleArray(rowCount) { row -> this[row, column] }
    }

    fun rowMean(row: Int): Double {
        if (columnCount == 0) {
            return Double.NaN
        }
        var sum = 0.0
        for (column in 0 until columnCount) {
            sum += this[row, column]
        }
        return sum / columnCount
    }

    fun columnMean(column: Int): Double {
        if (rowCount == 0) {
            return Double.NaN
        }
        var sum = 0.0
        for (row in 0 until rowCount) {
            sum += this[row, column]
        }
        return sum / rowCount
    }

    fun columnVariance(column: Int): Double {
        if (rowCount == 0) return Double.NaN
        val mean = columnMean(column)
        var sumSq = 0.0
        for (row in 0 until rowCount) {
            val delta = this[row, column] - mean
            sumSq += delta * delta
        }
        val variance = sumSq / rowCount
        return if (variance < 0.0 && variance > -1e-12) 0.0 else variance
    }

    fun windowMean(column: Int, startRowInclusive: Int, endRowExclusive: Int): Double {
        require(startRowInclusive in 0..rowCount) { "startRowInclusive must be in [0, $rowCount]" }
        require(endRowExclusive in 0..rowCount) { "endRowExclusive must be in [0, $rowCount]" }
        require(endRowExclusive >= startRowInclusive) { "endRowExclusive must be >= startRowInclusive" }
        val count = endRowExclusive - startRowInclusive
        if (count == 0) return Double.NaN
        var sum = 0.0
        for (row in startRowInclusive until endRowExclusive) sum += this[row, column]
        return sum / count
    }

    fun toWasmDoubleTensor(): WasmDoubleTensor = WasmDoubleTensor(rowCount, columnCount, values.copyOf())

    fun asColumnSeries(column: Int): Series<Double> = rowCount j { row: Int -> this[row, column] }
}

/**
 * Dense, row-major tensor substrate for SIMD-friendly numeric kernels.
 */
class WasmDoubleTensor(
    val rows: Int,
    val columns: Int,
    val values: DoubleArray,
) {
    init {
        require(rows >= 0) { "rows must be >= 0" }
        require(columns >= 0) { "columns must be >= 0" }
        require(values.size == rows * columns) {
            "values size must equal rows * columns"
        }
    }

    fun index(row: Int, column: Int): Int {
        require(row in 0 until rows) { "row must be in [0, ${rows - 1}]" }
        require(column in 0 until columns) { "column must be in [0, ${columns - 1}]" }
        return row * columns + column
    }

    operator fun get(row: Int, column: Int): Double = values[index(row, column)]

    fun row(row: Int): DoubleArray {
        require(row in 0 until rows) { "row must be in [0, ${rows - 1}]" }
        return DoubleArray(columns) { column -> this[row, column] }
    }

    fun column(column: Int): DoubleArray {
        require(column in 0 until columns) { "column must be in [0, ${columns - 1}]" }
        return DoubleArray(rows) { row -> this[row, column] }
    }

    fun rowMean(row: Int): Double {
        if (columns == 0) {
            return Double.NaN
        }
        var sum = 0.0
        for (column in 0 until columns) {
            sum += this[row, column]
        }
        return sum / columns
    }

    fun columnMean(column: Int): Double {
        if (rows == 0) {
            return Double.NaN
        }
        var sum = 0.0
        for (row in 0 until rows) {
            sum += this[row, column]
        }
        return sum / rows
    }

    fun windowMean(column: Int, startRowInclusive: Int, endRowExclusive: Int): Double {
        require(startRowInclusive in 0..rows) { "startRowInclusive must be in [0, $rows]" }
        require(endRowExclusive in 0..rows) { "endRowExclusive must be in [0, $rows]" }
        require(endRowExclusive >= startRowInclusive) { "endRowExclusive must be >= startRowInclusive" }
        val count = endRowExclusive - startRowInclusive
        if (count == 0) {
            return Double.NaN
        }
        var sum = 0.0
        for (row in startRowInclusive until endRowExclusive) {
            sum += this[row, column]
        }
        return sum / count
    }

    fun copy(): WasmDoubleTensor = WasmDoubleTensor(rows, columns, values.copyOf())
}

/**
 * Converts Cursor values into a dense numeric tensor.
 */
object CursorTensorReifier {
    private fun tensorTypeCode(type: TypeMemento): Int = when (type) {
        is IOMemento -> type.ordinal
        else -> CursorTensorLayout.TYPE_UNKNOWN
    }

    private fun isNumeric(type: TypeMemento): Boolean = when (type) {
        is IOMemento -> when (type) {
            IOMemento.IoBoolean,
            IOMemento.IoByte,
            IOMemento.IoUByte,
            IOMemento.IoShort,
            IOMemento.IoUShort,
            IOMemento.IoInt,
            IOMemento.IoUInt,
            IOMemento.IoLong,
            IOMemento.IoULong,
            IOMemento.IoFloat,
            IOMemento.IoDouble -> true
            else -> false
        }

        else -> false
    }

    private fun Any?.toTensorDouble(columnName: String, row: Int, column: Int): Double = when (this) {
        null -> Double.NaN
        is Double -> this
        is Float -> toDouble()
        is Int -> toDouble()
        is Long -> toDouble()
        is Short -> toDouble()
        is Byte -> toDouble()
        is UByte -> toDouble()
        is UShort -> toDouble()
        is UInt -> toDouble()
        is ULong -> toDouble()
        is Boolean -> if (this) 1.0 else 0.0
        else -> throw IllegalArgumentException(
            "cursor cell [$row,$column] ($columnName) is not numeric: ${this::class.simpleName}",
        )
    }

    private fun selectNumericColumns(meta: Series<ColumnMeta>): IntArray {
        val selected = mutableListOf<Int>()
        (0 until meta.size).forEach { column ->
            val value: ColumnMeta = meta[column]
            if (isNumeric(value.b)) {
                selected.add(column)
            }
        }
        return selected.toIntArray()
    }

    fun fromCursor(cursor: Cursor, columns: IntArray = intArrayOf()): CursorTensorSnapshot {
        require(cursor.size > 0) { "cursor must contain at least one row to infer tensor metadata" }

        val meta: Series<ColumnMeta> = cursor.meta
        val selectedColumns: IntArray = if (columns.isNotEmpty()) columns.copyOf() else selectNumericColumns(meta)

        require(selectedColumns.isNotEmpty()) {
            "cursor does not contain any numeric columns suitable for tensor reification"
        }

        val rowCount = cursor.size
        val columnCount = selectedColumns.size
        val values = DoubleArray(rowCount * columnCount)
        val columnNames :Series<String> = (columnCount) j { index:Int ->
            val i: Int = selectedColumns[index]
            val value: ColumnMeta = meta[i]
            value.a
        }
        val sourceColumnIndices = selectedColumns.copyOf()
        val columnTypeCodes = IntArray(columnCount) { index -> tensorTypeCode(meta[selectedColumns[index]].b) }

        for (row in 0..<rowCount) {
            val rowVec: Cursor = cursor[row]
            for (denseColumn in 0 until columnCount) {
                val sourceColumn = selectedColumns[denseColumn]
                val columnMeta: ColumnMeta = meta[sourceColumn]
                val cell = rowVec[sourceColumn].a
                values[row * columnCount + denseColumn] = cell.toTensorDouble(columnMeta.a, row, sourceColumn)
            }
        }

        return CursorTensorSnapshot(
            rowCount = rowCount,
            columnCount = columnCount,
            values = values,
            columnNames = columnNames,
            sourceColumnIndices = sourceColumnIndices,
            columnTypeCodes = columnTypeCodes,
        )
    }
}

/**
 * Convenience extension for turning a cursor into a dense tensor snapshot.
 */
fun Cursor.toCursorTensor(columns: IntArray = intArrayOf()): CursorTensorSnapshot = CursorTensorReifier.fromCursor(this, columns)
