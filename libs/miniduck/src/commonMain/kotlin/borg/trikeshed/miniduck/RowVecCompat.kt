package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.getValue as rootGetValue

fun RowVec.getValue(name: String): Any? = rootGetValue(name)

/** Subscript alias: `row["column"]` → `getValue("column")` */
operator fun RowVec.get(name: String): Any? = getValue(name)
