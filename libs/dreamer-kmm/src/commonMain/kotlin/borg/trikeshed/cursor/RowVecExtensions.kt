package borg.trikeshed.cursor

import borg.trikeshed.miniduck.getValue

public fun RowVec.stringValue(name: String, default: String): String =
    getValue(name) as? String ?: default

public fun RowVec.longValue(name: String): Long = when (val value = getValue(name)) {
    is Long -> value
    is Number -> value.toLong()
    is String -> value.toLongOrNull() ?: 0L
    else -> 0L
}

public fun RowVec.doubleValue(name: String): Double = when (val value = getValue(name)) {
    is Double -> value
    is Number -> value.toDouble()
    is String -> value.toDoubleOrNull() ?: 0.0
    else -> 0.0
}

public fun RowVec.intValue(name: String): Int = when (val value = getValue(name)) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull() ?: 0
    else -> 0
}
