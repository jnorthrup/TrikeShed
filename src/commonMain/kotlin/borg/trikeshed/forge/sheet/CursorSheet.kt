package borg.trikeshed.forge.sheet

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.cellKids
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.tag

/**
 * Cursor → sheet: the first UI projection that reads the canonical algebra directly.
 *
 * A sheet is a grid whose cells are scalars or references to other sheets (`SheetRef`) — the
 * TreeSheets idiom, grid-in-cell — so a hierarchy (a Confix document) and a flat table (any Cursor)
 * render through one view. Column names/types come from row 0's `ColumnMeta` exemplar
 * (`Cursor.kt`: "row 0 is the idempotent meta exemplar").
 */
data class SheetColumn(val name: String, val type: String)

/** A cell that nests another sheet (serialized as `{"sheet": id}`). */
data class SheetRef(val sheet: String)

data class SheetSeed(
    val id: String,
    val title: String,
    val columns: List<SheetColumn>,
    val rows: List<List<Any?>>,
    val parent: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "parent" to parent,
        "columns" to columns.map { mapOf("name" to it.name, "type" to it.type) },
        "rows" to rows.map { row -> row.map { cell -> if (cell is SheetRef) mapOf("sheet" to cell.sheet) else cell } },
    )
}

/** Any Cursor as one flat sheet. */
fun sheetSeed(id: String, title: String, cursor: Cursor, parent: String? = null): SheetSeed {
    if (cursor.size == 0) return SheetSeed(id, title, emptyList(), emptyList(), parent)
    val exemplar = cursor[0]
    val columns = (0 until exemplar.size).map { c ->
        val meta = exemplar[c].b()
        SheetColumn(meta.name.toString(), meta.type.toString())
    }
    val rows = (0 until cursor.size).map { r ->
        val row = cursor[r]
        (0 until row.size).map { c -> row[c].a }
    }
    return SheetSeed(id, title, columns, rows, parent)
}

/**
 * A Confix document as a family of sheets: every object/array node becomes a sheet whose
 * rows are `(key, value)` / `(index, value)`, and a nested container cell becomes a [SheetRef]
 * to its own sheet. Hierarchy-as-index (the Confix flat index) → grid-in-cell (TreeSheets).
 */
fun confixSheets(id: String, title: String, doc: ConfixDoc, maxSheets: Int = 256): List<SheetSeed> {
    val out = ArrayList<SheetSeed>()
    val root = doc.rootCell ?: return out
    fun walk(cell: ConfixCell, sheetId: String, sheetTitle: String, parent: String?) {
        if (out.size >= maxSheets) return
        val kids = cell.cellKids
        val isObject = cell.row.tag == IOMemento.IoObject
        val columns = if (isObject) listOf(SheetColumn("key", "IoString"), SheetColumn("value", "Any"))
        else listOf(SheetColumn("index", "IoInt"), SheetColumn("value", "Any"))
        val rows = ArrayList<List<Any?>>()
        val pending = ArrayList<Triple<ConfixCell, String, String>>()
        if (isObject) {
            // Confix flat-kid order inside an object is (key, value) pairs (verified by CursorSheetTest on JSON;
            // the comment in ConfixKit.step() claiming (value, key) is stale).
            var i = 0
            while (i + 1 < kids.size) {
                val key = kids[i].reify()?.toString() ?: "#$i"
                val value = kids[i + 1]
                rows.add(listOf(key, cellValue(value, "$sheetId/$key", key, pending)))
                i += 2
            }
        } else {
            for (i in 0 until kids.size) {
                rows.add(listOf(i, cellValue(kids[i], "$sheetId/$i", "[$i]", pending)))
            }
        }
        out.add(SheetSeed(sheetId, sheetTitle, columns, rows, parent))
        pending.forEach { (child, childId, childTitle) -> walk(child, childId, childTitle, sheetId) }
    }
    walk(root, id, title, null)
    return out
}

private fun cellValue(
    cell: ConfixCell,
    childId: String,
    childTitle: String,
    pending: MutableList<Triple<ConfixCell, String, String>>,
): Any? = when (cell.row.tag) {
    IOMemento.IoObject, IOMemento.IoArray -> {
        pending.add(Triple(cell, childId, childTitle))
        SheetRef(childId)
    }
    else -> cell.reify()
}
