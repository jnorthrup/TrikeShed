package borg.trikeshed.dreamer.adapter

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.j
import borg.trikeshed.cursor.joins
import borg.trikeshed.dreamer.Kline
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec

/**
 * Converts a list of column metadata definitions (e.g., from Kline.schemaKeys) into a Series of providers.
 */
fun List<Pair<String, IOMemento>>.toColumnMetaProviders(): Series<() -> ColumnMeta> {
    val metaObjects = this.map { (name, type) -> ColumnMeta(name, type) }.toTypedArray()
    return this.size j { index -> { metaObjects[index] } }
}

/**
 * Extension on DocRowVec to provide a direct bridge to Trike RowVec.
 */
fun DocRowVec.toTrikeRowVec(): RowVec = this.toRowVec()

/**
 * Converts an array of untyped cells into a canonical RowVec given a schema.
 */
fun cellsToTrikeRowVec(cells: Array<Any?>, schema: List<Pair<String, IOMemento>>): RowVec {
    val providers = schema.toColumnMetaProviders()
    val cellSeries = cells.size j { index: Int -> cells[index] }
    return cellSeries j providers
}

fun cellsToTrikeRowVec(cells: List<Any?>, keys: List<String>): RowVec {
    val values: Series<Any?> = cells.toSeries()
    val meta: Series<() -> ColumnMeta> = cells.size j { idx: Int ->
        val type = when (cells[idx]) {
            is Double -> IOMemento.IoDouble
            is Float -> IOMemento.IoFloat
            is Long -> IOMemento.IoLong
            is Int -> IOMemento.IoInt
            is Boolean -> IOMemento.IoBoolean
            else -> IOMemento.IoString
        }
        { ColumnMeta(keys[idx], type) }
    }
    return values.joins(meta)
}

/**
 * Convert a Kline to a canonical RowVec.
 */
fun Kline.toTrikeRowVec(): RowVec = this.toDocRowVec().toRowVec()
