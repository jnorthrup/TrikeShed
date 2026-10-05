package borg.trikeshed.forge.table

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.forge.board.ForgeBoard
import borg.trikeshed.forge.board.ForgeBoardCard
import borg.trikeshed.forge.board.ForgeBoardColumn
import borg.trikeshed.forge.doc.BlockType
import borg.trikeshed.forge.doc.ForgeBlock
import borg.trikeshed.forge.doc.ForgePage
import borg.trikeshed.forge.forgeUid
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * One Cursor, three surfaces. A [ForgeLens] is a saved view over a Cursor: which rows (where), in what
 * order (sort), split into which lanes (group). The sheet, the board and the page read the same
 * source ordinals the lens selects, so a row is the same row on every surface; the Cursor is never
 * copied. Column types stay the Cursor's own `IOMemento`; no property taxonomy sits on top of them.
 */
typealias SourceRow = Int

enum class Op { Eq, Contains, Gt, Lt }

data class Clause(val column: String, val op: Op, val value: Any?)

data class Sort(val column: String, val descending: Boolean = false)

data class ForgeLens(val where: List<Clause> = emptyList(), val sort: Sort? = null, val group: String? = null)

fun Cursor.columnIndex(name: String): Int {
    require(size > 0) { "no row to name column '$name'" }
    val row = this[0]
    return (0 until row.size).firstOrNull { row[it].b().name.toString() == name } ?: error("no column '$name'")
}

fun Cursor.cell(row: SourceRow, column: Int): Any? = this[row].let { it[column].a }

/** Order for cells: numbers numerically, anything else by text, absent first. */
fun compareCells(a: Any?, b: Any?): Int = when {
    a == null || b == null -> (if (a == null) 0 else 1) - (if (b == null) 0 else 1)
    a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
    else -> a.toString().compareTo(b.toString())
}

/** [want] read in the cell's own type, so `Eq` on a number column accepts "3" as well as 3. */
fun asCellType(cell: Any?, want: Any?): Any? = if (cell is Number && want is String) want.toDoubleOrNull() ?: want else want

fun Clause.holds(cell: Any?): Boolean = when (op) {
    Op.Eq -> compareCells(cell, asCellType(cell, value)) == 0
    Op.Contains -> cell?.toString()?.contains(value.toString(), ignoreCase = true) == true
    Op.Gt -> cell != null && compareCells(cell, asCellType(cell, value)) > 0
    Op.Lt -> cell != null && compareCells(cell, asCellType(cell, value)) < 0
}

/** The source ordinals this lens shows, in its order. */
fun ForgeLens.rows(cursor: Cursor): IntArray {
    if (cursor.size == 0) return IntArray(0)
    val tests = where.map { cursor.columnIndex(it.column) to it }
    val kept = (0 until cursor.size).filter { r -> tests.all { (c, clause) -> clause.holds(cursor.cell(r, c)) } }
    val by = sort ?: return kept.toIntArray()
    val c = cursor.columnIndex(by.column)
    val order = Comparator<Int> { x, y -> if (by.descending) compareCells(cursor.cell(y, c), cursor.cell(x, c)) else compareCells(cursor.cell(x, c), cursor.cell(y, c)) }
    return kept.sortedWith(order).toIntArray()
}

/** The lens as a Cursor: a lazy view over the source rows it selects. */
fun ForgeLens.view(cursor: Cursor): Cursor = rows(cursor).let { picked -> picked.size j { i -> cursor[picked[i]] } }

/** The lanes of [group], in order of first appearance among [rows]; no group is one lane. */
fun ForgeLens.lanes(cursor: Cursor): Series<Join<String, IntArray>> {
    val picked = rows(cursor)
    val column = group?.let { cursor.columnIndex(it) }
    val lanes = LinkedHashMap<String, MutableList<Int>>()
    for (r in picked) lanes.getOrPut(column?.let { cursor.cell(r, it)?.toString() } ?: LANE_NONE) { mutableListOf() }.add(r)
    val named = lanes.entries.toList()
    return named.size j { i -> named[i].key j named[i].value.toIntArray() }
}

const val LANE_NONE = "none"

fun rowId(row: SourceRow): String = "r$row"

/** The board surface: one column per lane, one card per row, the card id naming the source row. */
fun ForgeLens.board(cursor: Cursor, title: String): ForgeBoard {
    val t = cursor.columnIndex(title)
    val lanes = lanes(cursor)
    return ForgeBoard(
        columns = (0 until lanes.size).map { ForgeBoardColumn(lanes[it].a, lanes[it].a) }.toMutableList(),
        cards = (0 until lanes.size).flatMap { l ->
            lanes[l].b.map { r -> ForgeBoardCard(rowId(r), cursor.cell(r, t)?.toString().orEmpty(), lanes[l].a) }
        }.toMutableList(),
    )
}

/** The page surface: a row opened as a titled page, one bullet per other column. */
fun Cursor.page(row: SourceRow, title: String): ForgePage {
    val t = columnIndex(title)
    val names = this[row]
    return ForgePage(
        id = rowId(row),
        title = cell(row, t)?.toString().orEmpty(),
        blocks = (0 until names.size).filter { it != t }.map { c ->
            ForgeBlock(forgeUid(), BlockType.BULLET, names[c].b().name.toString() + ": " + cell(row, c))
        }.toMutableList(),
    )
}
