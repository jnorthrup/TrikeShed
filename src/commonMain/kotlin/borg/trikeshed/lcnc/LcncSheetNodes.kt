package borg.trikeshed.lcnc

import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetSeed
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * LCNC node types for sheet manipulation and navigation — part of the
 * "patch fractal panel programming" system. Each node operates on sheets,
 * creating subsheets, filtering, grouping, and enabling navigation into
 * nested hierarchies.
 */
object LcncSheetNodes {

    /**
     * `sheet.flatten` — takes a SheetSeed (possibly with SheetRef cells) and
     * returns all its flattened row data without the references.
     */
    fun flattenNode(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        @Suppress("UNCHECKED_CAST")
        val sheet = inputs["sheet"] as? Map<String, Any?> ?: return@LcncNodeRunner emptyMap()
        val rows = (sheet["rows"] as? List<List<Any?>>) ?: emptyList()
        val columns = (sheet["columns"] as? List<Map<String, String>>) ?: emptyList()

        val flatRows = rows.map { row ->
            row.map { cell ->
                if (cell is Map<*, *> && cell.containsKey("sheet")) {
                    (cell as Map<String, String>)["sheet"] ?: ""
                } else cell
            }
        }
        // Output rides the `sheet` port so the family composes: flatten → filter → count.
        mapOf(
            "sheet" to mapOf(
                "id" to (sheet["id"] ?: "flattened"),
                "title" to (sheet["title"] ?: ""),
                "columns" to columns,
                "rows" to flatRows,
            ),
        )
    }

    /**
     * `sheet.filter` — takes rows and a column name + value, returning only
     * rows where that column matches. Useful for drilling into a grouped
     * subsheet's content via LCNC.
     */
    fun filterNode(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        @Suppress("UNCHECKED_CAST")
        val sheet = inputs["sheet"] as? Map<String, Any?> ?: return@LcncNodeRunner emptyMap()
        val rows = (sheet["rows"] as? List<List<Any?>>) ?: emptyList()
        val columns = (sheet["columns"] as? List<Map<String, String>>) ?: emptyList()
        val columnName = node.params["columnName"] ?: return@LcncNodeRunner emptyMap()
        val columnValue = node.params["columnValue"] ?: return@LcncNodeRunner emptyMap()

        val colIdx = columns.indexOfFirst { (it["name"] as? String) == columnName }
        if (colIdx < 0) return@LcncNodeRunner emptyMap()

        val filtered = rows.filter { row ->
            if (colIdx < row.size) row[colIdx].toString() == columnValue else false
        }
        mapOf(
            "sheet" to mapOf(
                "id" to (sheet["id"] ?: "filtered"),
                "title" to (sheet["title"] ?: ""),
                "columns" to columns,
                "rows" to filtered,
            ),
        )
    }

    /**
     * `sheet.count` — returns the number of rows in a sheet. Used to measure
     * the size of a group or filtered subset.
     */
    fun countNode(): LcncNodeRunner = LcncNodeRunner { _, inputs ->
        @Suppress("UNCHECKED_CAST")
        val sheet = inputs["sheet"] as? Map<String, Any?> ?: return@LcncNodeRunner emptyMap()
        val rows = (sheet["rows"] as? List<*>) ?: emptyList<Any>()
        mapOf("count" to rows.size)
    }

    /**
     * `sheet.columns` — extracts just the column definitions from a sheet,
     * useful for schema interrogation or wiring to downstream nodes.
     */
    fun columnsNode(): LcncNodeRunner = LcncNodeRunner { _, inputs ->
        @Suppress("UNCHECKED_CAST")
        val sheet = inputs["sheet"] as? Map<String, Any?> ?: return@LcncNodeRunner emptyMap()
        val columns = (sheet["columns"] as? List<Map<String, String>>) ?: emptyList()
        mapOf("columns" to columns)
    }

    /**
     * `sheet.cell` — extracts a single cell by row and column index.
     * Useful for drilling into specific data points within a sheet.
     */
    fun cellNode(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        @Suppress("UNCHECKED_CAST")
        val sheet = inputs["sheet"] as? Map<String, Any?> ?: return@LcncNodeRunner emptyMap()
        val rows = (sheet["rows"] as? List<List<Any?>>) ?: emptyList()
        val rowIdx = node.params["row"]?.toIntOrNull() ?: 0
        val colIdx = node.params["column"]?.toIntOrNull() ?: 0

        val value = if (rowIdx in rows.indices && colIdx in rows[rowIdx].indices) {
            rows[rowIdx][colIdx]
        } else null
        mapOf("value" to value)
    }
}

/**
 * A registry of sheet manipulation nodes for LCNC programs.
 * Wire these into programs to enable operational sheet transformations.
 */
fun sheetLcncRegistry(): Map<String, LcncNodeRunner> = mapOf(
    "sheet.flatten" to LcncSheetNodes.flattenNode(),
    "sheet.filter" to LcncSheetNodes.filterNode(),
    "sheet.count" to LcncSheetNodes.countNode(),
    "sheet.columns" to LcncSheetNodes.columnsNode(),
    "sheet.cell" to LcncSheetNodes.cellNode(),
)

/**
 * Convert a [SheetSeed] to the map format LCNC nodes expect for sheet inputs.
 * The result can be wired directly as a node output, or fed into `inputs["sheet"]`.
 */
fun SheetSeed.toLcncMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "parent" to parent,
    "columns" to columns.map { mapOf("name" to it.name, "type" to it.type) },
    "rows" to rows.map { row ->
        row.map { cell ->
            if (cell is SheetRef) mapOf("sheet" to cell.sheet) else cell
        }
    },
)

/**
 * Convert a map (from LCNC node output) back to a [SheetSeed].
 * Reconstructs the sheet structure from the serialized form.
 */
@Suppress("UNCHECKED_CAST")
fun lcncMapToSheetSeed(data: Map<String, Any?>): SheetSeed? {
    val id = data["id"]?.toString() ?: return null
    val title = data["title"]?.toString() ?: return null
    val parent = data["parent"]?.toString()
    val columns = (data["columns"] as? List<Map<String, String>>)?.mapNotNull { col ->
        val name = col["name"] ?: return@mapNotNull null
        val type = col["type"] ?: return@mapNotNull null
        SheetColumn(name, type)
    } ?: emptyList()
    val rows = (data["rows"] as? List<List<Any?>>)?.map { row ->
        row.map { cell ->
            if (cell is Map<*, *> && cell.containsKey("sheet")) {
                SheetRef(cell["sheet"].toString())
            } else cell
        }
    } ?: emptyList()
    return SheetSeed(id, title, columns, rows, parent)
}
