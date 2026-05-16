package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.mutable.SeriesBuffer
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view

/** Hash join with another cursor. */
fun Cursor.hashJoin(other: Cursor, leftKey: CharSequence, rightKey: CharSequence): Cursor {
    if (this.size == 0 || other.size == 0) return emptyCursor()

    // Build index: each entry is (key, SeriesBuffer of matching rows)
    val rightIndex: SeriesBuffer<Pair<Any?, SeriesBuffer<RowVec>>> = SeriesBuffer()
    for (i in 0 until other.size) {
        val row = other.at(i)
        val key = row.getValue(rightKey)
        if (key != null) {
            val existing = rightIndex.view.find { it.first == key }
            if (existing != null) {
                existing.second.add(row)
            } else {
                val buf: SeriesBuffer<RowVec> = SeriesBuffer()
                buf.add(row)
                rightIndex.add(key to buf)
            }
        }
    }

    val resultRows: SeriesBuffer<RowVec> = SeriesBuffer()
    for (i in 0 until this.size) {
        val leftRow = this.at(i)
        val key = leftRow.getValue(leftKey)
        val existing = rightIndex.view.find { it.first == key } ?: continue
        val matches = existing.second
        for (matchIdx in 0 until matches.size) {
            val rightRow = matches[matchIdx]
            val keys: SeriesBuffer<CharSequence> = SeriesBuffer()
            val cells: SeriesBuffer<Any?> = SeriesBuffer()
            appendRowData(keys, cells, leftRow)
            appendJoinedRowData(keys, cells, rightRow, rightKey.toString())
            val keysList = keys.toList()
            val cellsList = cells.toList()
            resultRows.add(DocRowVec(keysList, cellsList))
        }
    }

    return resultRows.size j { i: Int -> resultRows[i] as RowVec }
}
