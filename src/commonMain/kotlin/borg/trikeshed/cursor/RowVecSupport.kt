package borg.trikeshed.cursor

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*

fun RowVec.getValue(key: String): Any? {//todo prepared context for binsearch?
    for (index in 0 until size) {
        val cell: Join<Any?, `ColumnMeta↻`> = b(index)
        if (cell.b().a == key) return cell.a
    }
    return null
}

fun RowVec.stringValue(name: String, default: String): String =
    getValue(name) as? String ?: default

fun RowVec.longValue(name: String): Long = when (val value = getValue(name)) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

fun RowVec.doubleValue(name: String): Double = when (val value = getValue(name)) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

fun RowVec.intValue(name: String): Int = when (val value = getValue(name)) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

fun cellsToRowVec(cells: Series<Any?>, keys: Series<String>): RowVec {
    require(cells.size == keys.size) { "cells and keys must have the same length" }
    val values: Series<Any?> = cells
    val meta: Series<`ColumnMeta↻`> = cells.size j { index: Int ->
        val type: IOMemento = when (cells[index]) {
            is Double -> IoDouble
            is Float -> IoFloat
            is Long -> IoLong
            is Int -> IoInt
            is Boolean -> IoBoolean
            is ByteArray -> IoByteArray
            null -> IoNothing
            else -> IoString
        }
        ColumnMeta(keys[index], type).`↻`
    }
    return ReifiedSplitSeries2(values, meta)
}

/** Column names extracted from the RowVec metadata. */
val RowVec.keys: List<String>
    get() = (0 until size).map { index -> b(index).b().name }

/** Cell values as a flat List. Semantically identical to [values] but returns List<Any?>. */
val RowVec.cells: List<Any?>
    get() = values.toList()

/** Child / nested row — deferred per architecture spec. Always null for now. */
val RowVec.child: RowVec?
    get() = null
