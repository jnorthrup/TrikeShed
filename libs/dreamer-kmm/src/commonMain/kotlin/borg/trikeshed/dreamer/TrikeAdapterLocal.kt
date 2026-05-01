package borg.trikeshed.dreamer

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.miniduck.toRowVec

/**
 * Small in-package adapter helpers so no cross-package imports are required
 * during incremental migration. These provide minimal conversions from
 * cell lists / Kline to the canonical RowVec used by the Cursor algebra.
 */

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

fun Kline.toTrikeRowVec(): RowVec = this.toDocRowVec().toRowVec()
