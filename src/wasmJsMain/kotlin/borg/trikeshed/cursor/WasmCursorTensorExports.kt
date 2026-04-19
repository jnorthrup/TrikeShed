@file:OptIn(ExperimentalJsExport::class)

package borg.trikeshed.cursor

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName

@JsName("Float64Array")
external class JsFloat64Array(length: Int) {
    val length: Int

    operator fun get(index: Int): Double
    operator fun set(index: Int, value: Double)
}

private fun JsFloat64Array.toDoubleArray(): DoubleArray = DoubleArray(length) { index -> this[index] }

private fun DoubleArray.toFloat64Array(): JsFloat64Array = JsFloat64Array(size).also { typed ->
    for (index in indices) {
        typed[index] = this[index]
    }
}

@JsExport
fun wasmDoubleTensorRow(values: JsFloat64Array, rows: Int, columns: Int, row: Int): JsFloat64Array =
    WasmDoubleTensor(rows, columns, values.toDoubleArray()).row(row).toFloat64Array()

@JsExport
fun wasmDoubleTensorValueAt(values: JsFloat64Array, rows: Int, columns: Int, row: Int, column: Int): Double =
    WasmDoubleTensor(rows, columns, values.toDoubleArray())[row, column]

@JsExport
fun wasmDoubleTensorColumn(values: JsFloat64Array, rows: Int, columns: Int, column: Int): JsFloat64Array =
    WasmDoubleTensor(rows, columns, values.toDoubleArray()).column(column).toFloat64Array()

@JsExport
fun wasmDoubleTensorRowMean(values: JsFloat64Array, rows: Int, columns: Int, row: Int): Double =
    WasmDoubleTensor(rows, columns, values.toDoubleArray()).rowMean(row)

@JsExport
fun wasmDoubleTensorColumnMean(values: JsFloat64Array, rows: Int, columns: Int, column: Int): Double =
    WasmDoubleTensor(rows, columns, values.toDoubleArray()).columnMean(column)

@JsExport
fun wasmDoubleTensorWindowMean(
    values: JsFloat64Array,
    rows: Int,
    columns: Int,
    column: Int,
    startRowInclusive: Int,
    endRowExclusive: Int,
): Double = WasmDoubleTensor(rows, columns, values.toDoubleArray()).windowMean(column, startRowInclusive, endRowExclusive)
