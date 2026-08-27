package borg.trikeshed.lcnc

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.kanban.BoardCursor
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Operational tree sheets: [borg.trikeshed.forge.sheet.CursorSheet] projects
 * a static document; this projects a LIVE daemon element instead — the
 * running [BoardStoreElement], not a frozen JSON snapshot of it. Call this
 * again after every mutation and the sheet reflects exactly what changed,
 * because [BoardCursor.of] re-freezes straight from `store.cards()` — the
 * SAME source of truth `KanbanModule.boardJson()` reads. One Cursor, two
 * projections (JSON wire, sheet grid); neither is more authoritative than
 * the other, and there's still no JavaScript anywhere in this file.
 */
object LcncOperationalSheets {

    /**
     * [BoardCursor]'s own column shape, named explicitly: `sheetSeed` can only
     * derive a schema from a row-0 exemplar, so an empty board (0 rows, no
     * exemplar) would otherwise come back with NO columns at all — still
     * "operational", it just has nothing in it yet.
     */
    private val boardColumns = listOf(
        SheetColumn("jobId", "IoString"),
        SheetColumn("title", "IoString"),
        SheetColumn("columnId", "IoString"),
        SheetColumn("priority", "IoInt"),
        SheetColumn("order", "IoInt"),
        SheetColumn("revision", "IoLong"),
        SheetColumn("lastMoveMs", "IoLong"),
    )

    private val groupedColumns = listOf(
        SheetColumn("key", "IoString"),
        SheetColumn("value", "Any"),
    )

    /** The live board, right now, as a concentric-sheet-ready [SheetSeed]. */
    fun board(store: BoardStoreElement, title: String = "Kanban board (live)"): SheetSeed =
        sheetSeed("board", title, BoardCursor.of(store.cards()).cursor(), columns = boardColumns)

    /**
     * Grouped sheets: partition the board by a column (e.g., "columnId" or "priority")
     * and return a root sheet with each group as a [SheetRef] to its own subsheet.
     * Each subsheet contains the full card rows for that group.
     */
    fun grouped(
        store: BoardStoreElement,
        groupBy: String,
        title: String = "Kanban board grouped",
        rootId: String = "board-grouped",
    ): List<SheetSeed> {
        val cursor = BoardCursor.of(store.cards())
        val sheets = mutableListOf<SheetSeed>()

        // Find the column index for groupBy
        val groupColIdx = boardColumns.indexOfFirst { it.name == groupBy }
        if (groupColIdx < 0) return emptyList()

        // Collect rows grouped by the target column value
        val fullCursor = cursor.cursor()
        val groups = LinkedHashMap<String, MutableList<List<Any?>>>()

        for (i in 0 until fullCursor.size) {
            val row = fullCursor[i]
            val groupKey = row[groupColIdx].a.toString()
            groups.getOrPut(groupKey) { mutableListOf() }.add(
                (0 until row.size).map { row[it].a }
            )
        }

        // Root sheet: one row per group, with a SheetRef to its subsheet
        val rootRows = groups.map { (key, _) ->
            listOf(key, SheetRef("$rootId/$key"))
        }
        sheets.add(SheetSeed(rootId, title, groupedColumns, rootRows))

        // Subsheets: each group gets its own sheet
        for ((key, rows) in groups) {
            sheets.add(SheetSeed(
                "$rootId/$key",
                "$title: $key",
                boardColumns,
                rows,
                parent = rootId,
            ))
        }

        return sheets
    }

    /**
     * Column-grouped sheets: partition cards by their "columnId" (status).
     * A convenience over [grouped] with pre-set groupBy="columnId".
     */
    fun byStatus(
        store: BoardStoreElement,
        title: String = "Kanban board by status",
    ): List<SheetSeed> = grouped(store, "columnId", title, "board-by-status")

    /**
     * Priority-grouped sheets: partition cards by their "priority".
     * A convenience over [grouped] with pre-set groupBy="priority".
     */
    fun byPriority(
        store: BoardStoreElement,
        title: String = "Kanban board by priority",
    ): List<SheetSeed> = grouped(store, "priority", title, "board-by-priority")
}
