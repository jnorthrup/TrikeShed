package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.getValue as rootGetValue

fun RowVec.getValue(name: CharSequence): Any? = rootGetValue(name)

/** Subscript alias: `row["column"]` → `getValue("column")` */
operator fun RowVec.get(name: CharSequence): Any? = getValue(name)
