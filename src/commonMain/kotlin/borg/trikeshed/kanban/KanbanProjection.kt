@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.kanban

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.row
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.leftIdentity
import borg.trikeshed.lib.size

// ── Kanban-as-projection ────────────────────────────────────────────────────
//
// The Kanban board is NOT a separate system. It is a projection over the
// Blackboard's content-addressed state. A Hermes-style agent (running in
// GraalPy or Kotlin) publishes a board state to the blackboard; this module
// projects that state into a Cursor for rendering, querying, and ISAM
// column-grouping without copying the data into a parallel type system.
//
// The projection is pure: it reads KanbanBoard and emits Cursor. It has no
// mutable state, no side effects, no GraalVM imports. It is the common
// substrate that both Kotlin and GraalPy agents operate over.
//
// Column layout (CardCursor):
//   0: cardId     IoString
//   1: title      IoString
//   2: columnId   IoString   (FK to Column)
//   3: priority   IoString   (enum name)
//   4: order      IoInt
//   5: assignee   IoString?  (nullable)
//
// Column layout (ColumnCursor):
//   0: columnId   IoString
//   1: name       IoString
//   2: order      IoInt
//   3: wipLimit   IoInt?
//   4: wipCount   IoInt      (projected)

/** Column metadata suppliers for the card cursor projection. */
private val CARD_META: Series<`ColumnMeta↻`> = 6 j { c ->
    val cm = when (c) {
        0 -> ColumnMeta("cardId", IOMemento.IoString)
        1 -> ColumnMeta("title", IOMemento.IoString)
        2 -> ColumnMeta("columnId", IOMemento.IoString)
        3 -> ColumnMeta("priority", IOMemento.IoString)
        4 -> ColumnMeta("order", IOMemento.IoInt)
        5 -> ColumnMeta("assignee", IOMemento.IoString)
        else -> error("6")
    }
    cm.leftIdentity
}

/** Column metadata suppliers for the column cursor projection. */
private val COLUMN_META: Series<`ColumnMeta↻`> = 5 j { c ->
    val cm = when (c) {
        0 -> ColumnMeta("columnId", IOMemento.IoString)
        1 -> ColumnMeta("name", IOMemento.IoString)
        2 -> ColumnMeta("order", IOMemento.IoInt)
        3 -> ColumnMeta("wipLimit", IOMemento.IoInt)
        4 -> ColumnMeta("wipCount", IOMemento.IoInt)
        else -> error("5")
    }
    cm.leftIdentity
}

/** Project a KanbanBoard into a Cursor of cards — one row per card. */
fun KanbanBoard.cardCursor(): Cursor {
    val rows: Series<Series<Any?>> = cards.count() j { rowIdx ->
        val card = cards[rowIdx]
        6 j { c ->
            when (c) {
                0 -> card.id.value as Any?
                1 -> card.title as Any?
                2 -> card.columnId.value as Any?
                3 -> card.priority.name as Any?
                4 -> card.order as Any?
                5 -> card.assignee as Any?
                else -> error("6")
            }
        }
    }
    return rows.size j { row ->
        ReifiedSplitSeries2(rows.b(row), CARD_META)
    }
}

/** Project a KanbanBoard into a Cursor of columns — one row per column. */
fun KanbanBoard.columnCursor(): Cursor {
    val rows: Series<Series<Any?>> = columns.count() j { rowIdx ->
        val column = columns[rowIdx]
        val wipCount = cards.count { it.columnId == column.id }
        5 j { c ->
            when (c) {
                0 -> column.id.value as Any?
                1 -> column.name as Any?
                2 -> column.order as Any?
                3 -> column.wipLimit as Any?
                4 -> wipCount as Any?
                else -> error("5")
            }
        }
    }
    return rows.size j { row ->
        ReifiedSplitSeries2(rows.b(row), COLUMN_META)
    }
}

/**
 * Project a KanbanBoard into a single combined cursor: columns followed by
 * cards. The first N rows are columns, the remaining rows are cards.
 * This is the "board as one table" projection that a GraalPy agent or ISAM
 * column-grouper can scan in a single pass.
 */
fun KanbanBoard.boardCursor(): Cursor {
    val cols = columnCursor()
    val cardRows = cardCursor()
    val total = cols.size + cardRows.size
    return total j { row ->
        if (row < cols.size) cols row row else cardRows row (row - cols.size)
    }
}
