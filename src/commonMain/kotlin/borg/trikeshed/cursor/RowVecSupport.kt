package borg.trikeshed.cursor

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.lib.*

fun RowVec.getValue(key: CharSequence): Any? {
    for (index in 0 until size) {
        val cell = b(index)
        val meta = when (val raw = cell.b as Any?) {
            is RecordMeta -> raw
            is Function0<*> -> raw.invoke()
            else -> null
        }
        // Names are CharSequence in ColumnMeta, and CharSequence deliberately
        // does not refine equals — the JDK says so outright — so `==` is false
        // for equal content unless both happen to be Strings. The old fix was
        // .toString(), which answers the question by materialising it.
        // contentEquals asks the same question without allocating anything.
        when (meta) {
            is RecordMeta -> if (meta.name.contentEquals(key)) return cell.a
            is Join<*, *> -> if ((meta.a as? CharSequence)?.contentEquals(key) == true) return cell.a
        }
    }
    return null
}

fun RowVec.stringValue(name: CharSequence, default: String): String =
    getValue(name) as? String ?: default

fun RowVec.longValue(name: CharSequence): Long = when (val value = getValue(name)) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

fun RowVec.doubleValue(name: CharSequence): Double = when (val value = getValue(name)) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

fun RowVec.intValue(name: CharSequence): Int = when (val value = getValue(name)) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}

/**
 * Column names are CharSequence in ColumnMeta already — this signature was the
 * one place that still demanded String, so every caller building a row had to
 * hand over canonical text it did not need to canonicalise.
 */
fun cellsToRowVec(cells: Series<Any?>, keys: Series<CharSequence>): RowVec {
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
        ColumnMeta(keys[index], type).leftIdentity
    }
    return ReifiedSplitSeries2(values, meta)
}

/** Column names extracted from the RowVec metadata. */
val RowVec.keys: Series<String> get() = right α `ColumnMeta↻`::invoke α { cm: ColumnMeta -> cm.name.toString() }

/** Cell values as a flat List. Semantically identical to [values] but returns List<Any?>. */
val RowVec.cells get() = values

/** Child / nested row — deferred per architecture spec. Always null for now. */
val RowVec.child: RowVec?
    get() = null
