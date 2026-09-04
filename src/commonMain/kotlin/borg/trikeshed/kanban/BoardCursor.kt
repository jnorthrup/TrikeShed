@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.kanban

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.leftIdentity

/**
 * BoardCursor — PRELOAD verbatim: "The board is a Cursor. Columns: title
 * (Series<String>), order (IntArray), priority (IntArray of enum ordinals),
 * columnId (ByteArray). A KanbanCard IS a row index." boardJson is a boundary
 * map over the cursor rows, never N Map allocations in the hot path.
 *
 * Built ONCE per committed change from the store's row table (SoA freeze);
 * readers index primitive arrays, no per-row objects.
 */
class BoardCursor private constructor(
    val jobIds: Array<String>,
    val titles: Array<String>,
    val columnId: ByteArray,
    val priority: IntArray,
    val order: IntArray,
    val revision: LongArray,
    val lastMoveMs: LongArray,
    val lastSequence: LongArray,
) {
    val size: Int get() = jobIds.size

    fun col(row: Int): BoardCol = BoardCol.fromId(ColId(columnId[row]))

    /** Cursor projection — row = card, columns typed by META (the KanbanProjection idiom). */
    fun cursor(): Cursor {
        val rows: Series<Series<Any?>> = size j { r ->
            META_WIDTH j { c ->
                when (c) {
                    0 -> jobIds[r] as Any?
                    1 -> titles[r] as Any?
                    2 -> col(r).wire as Any?
                    3 -> priority[r] as Any?
                    4 -> order[r] as Any?
                    5 -> revision[r] as Any?
                    6 -> lastMoveMs[r] as Any?
                    else -> error("$META_WIDTH")
                }
            }
        }
        return size j { r -> ReifiedSplitSeries2(rows.b(r), META) }
    }

    /** Cards of one column, ordered — index walk over the byte array, zero allocation until the hit list. */
    fun rowsIn(col: BoardCol): IntArray {
        val id = col.id.value
        var n = 0
        for (i in columnId.indices) if (columnId[i] == id) n++
        val out = IntArray(n)
        var w = 0
        for (i in columnId.indices) if (columnId[i] == id) out[w++] = i
        return out
    }

    companion object {
        private const val META_WIDTH = 7

        private val META: Series<`ColumnMeta↻`> = META_WIDTH j { c ->
            val cm = when (c) {
                0 -> ColumnMeta("jobId", IOMemento.IoString)
                1 -> ColumnMeta("title", IOMemento.IoString)
                2 -> ColumnMeta("columnId", IOMemento.IoString)
                3 -> ColumnMeta("priority", IOMemento.IoInt)
                4 -> ColumnMeta("order", IOMemento.IoInt)
                5 -> ColumnMeta("revision", IOMemento.IoLong)
                6 -> ColumnMeta("lastMoveMs", IOMemento.IoLong)
                else -> error("$META_WIDTH")
            }
            cm.leftIdentity
        }

        /** SoA freeze of the store's committed rows (deterministic order: column, then order, then jobId). */
        fun of(cards: Collection<CardRow>): BoardCursor {
            val sorted = cards.sortedWith(compareBy({ it.col.order }, { it.order }, { it.jobId }))
            val n = sorted.size
            val jobIds = Array(n) { sorted[it].jobId }
            val titles = Array(n) { sorted[it].title }
            val colId = ByteArray(n) { sorted[it].col.id.value }
            val pri = IntArray(n) { sorted[it].priority }
            val ord = IntArray(n) { sorted[it].order }
            val rev = LongArray(n) { sorted[it].revision }
            val moved = LongArray(n) { sorted[it].lastMoveMs }
            val seq = LongArray(n) { sorted[it].lastSequence }
            return BoardCursor(jobIds, titles, colId, pri, ord, rev, moved, seq)
        }
    }
}

/**
 * Boundary projection: the rich board shape the PWA renders (columns with
 * wip data + cards + watermark). Pure structures — JSON stringify at the wire.
 */
fun BoardCursor.toBoardMap(sequence: Long, title: String = "Board"): Map<String, Any?> {
    val counts = IntArray(BoardCol.entries.size)
    for (i in 0 until size) counts[columnId[i].toInt()]++
    return linkedMapOf(
        "title" to title,
        "sequence" to sequence,
        // Render order is BoardCol.order, not declaration: REVIEW is declared last (ColId stability).
        "columns" to BoardCol.rendered.map { c ->
            linkedMapOf(
                "id" to c.wire,
                "name" to c.wire.replaceFirstChar { it.uppercase() },
                "order" to c.order,
                "wipLimit" to c.wipLimit,
                "count" to counts[c.ordinal],
            )
        },
        "items" to (0 until size).map { r ->
            linkedMapOf(
                "id" to jobIds[r],
                "title" to titles[r],
                "status" to col(r).wire,
                "priority" to priority[r],
                "order" to order[r],
                "revision" to revision[r],
                "lastMoveMs" to lastMoveMs[r],
            )
        },
    )
}
