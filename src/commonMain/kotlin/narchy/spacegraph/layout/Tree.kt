package narchy.spacegraph.layout

// Port of upstream src/layout/tree.ts at 125b369.
// The tree placement: a box hangs under its parent, the tree grows downward, and a parent sits over the middle of
// its children. A placed box keeps its column: the engine reads `previous` and only places boxes it has not seen.

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.appendTo

/** The tree's vocabulary: the parent a box hangs from. */
data class TreeMeta(
    /** The parent box's id; null for a root. */
    val parent: String? = null,
)

data class TreeConfig(
    /** The horizontal distance between two columns (floor for leaf/item width). */
    val columnPitch: Double? = null,
    /** The vertical distance between two rows. */
    val rowPitch: Double? = null,
    /** The Z distance per depth. */
    val depthStep: Double? = null,
    /** The leaves a row holds before a block folds to the next row. */
    val columns: Int? = null,
    /** Gap between adjacent cells. Default: 24. */
    val cellGap: Double? = null,
)

private const val DEFAULT_COLUMN_PITCH = 260.0
private const val DEFAULT_ROW_PITCH = 96.0
private const val DEFAULT_DEPTH_STEP = 64.0
private const val DEFAULT_COLUMNS = 4
private const val DEFAULT_CELL_GAP = 24.0

/** The block a subtree occupies: its width, its height in rows, and where its children sit. */
private class Block(
    /** The block's width. */
    val width: Double,
    /** The block's height, in rows, the node's own row included. */
    val rows: Double,
    /** The grid rows of the block: their offset from the node's row and their height. */
    val rowsGrid: MutableList<GridRow> = mutableListOf(),
)

private class GridRow(var offset: Double, var height: Double)

private class Cell(val row: Int, val column: Int, val width: Double)

class TreePlacement(config: TreeConfig = TreeConfig()) : Placement<TreeMeta> {
    override val name = "tree"
    private val columnPitch: Double = config.columnPitch ?: DEFAULT_COLUMN_PITCH
    private val rowPitch: Double = config.rowPitch ?: DEFAULT_ROW_PITCH
    private val depthStep: Double = config.depthStep ?: DEFAULT_DEPTH_STEP
    private val columns: Int = maxOf(1, config.columns ?: DEFAULT_COLUMNS)
    private val cellGap: Double = config.cellGap ?: DEFAULT_CELL_GAP

    override fun place(items: List<PlacementItem<TreeMeta>>, previous: Map<String, Position>?): Map<String, Position> {
        val byId = items.associateBy { it.id }
        val children = LinkedHashMap<String, MutableList<String>>()

        for (item in items) {
            val parent = item.meta?.parent ?: continue
            if (parent !in byId) continue
            appendTo(children, parent, item.id)
        }

        val blocks = HashMap<String, Block>()
        val cells = HashMap<String, Cell>()
        val depths = items.associate { it.id to (it.depth ?: 0.0) }

        // Measure every box, so the leaf widths are known before the parents are placed.
        for (item in items) measure(item.id, children, blocks, cells, byId)

        val positions = LinkedHashMap<String, Position>()
        val roots = items.filter { item ->
            val parent = item.meta?.parent
            parent == null || parent !in byId
        }

        var cursor = 0.0

        for (root in roots) {
            val block = blocks.getValue(root.id)
            val centre = cursor + block.width / 2

            place(root.id, centre, 0.0, children, blocks, cells, depths, positions, previous)
            cursor += block.width
        }

        return positions
    }

    /** The size of a node's block, bottom up: a leaf is one cell, and a parent is its children's blocks side by side. */
    private fun measure(
        id: String,
        children: Map<String, List<String>>,
        blocks: MutableMap<String, Block>,
        cells: MutableMap<String, Cell>,
        byId: Map<String, PlacementItem<TreeMeta>>,
    ) {
        val kids = children[id].orEmpty()

        if (kids.isEmpty()) {
            // Leaf: width = max(columnPitch, item.width + cellGap)
            val itemWidth = byId[id]?.width ?: columnPitch
            val leafWidth = maxOf(columnPitch, itemWidth + cellGap)
            blocks[id] = Block(width = leafWidth, rows = 1.0)
            return
        }

        for (kid in kids) measure(kid, children, blocks, cells, byId)

        // cellWidth = max(columnPitch, max kid width + cellGap)
        val maxKidWidth = kids.maxOf { blocks.getValue(it).width }
        val cellWidth = maxOf(columnPitch, maxKidWidth + cellGap)

        val perRow = minOf(columns, kids.size)
        val gridRows = mutableListOf<GridRow>()
        var height = 0.0

        kids.forEachIndexed { index, kid ->
            val row = index / perRow
            val block = blocks.getValue(kid)

            if (row == gridRows.size) {
                gridRows.add(GridRow(offset = height, height = block.rows))
            } else {
                gridRows[row].height = maxOf(gridRows[row].height, block.rows)
            }

            cells[kid] = Cell(row = row, column = index % perRow, width = cellWidth)
            height = gridRows[row].offset + gridRows[row].height
        }

        blocks[id] = Block(
            width = maxOf(perRow * cellWidth, columnPitch),
            rows = 1 + height,
            rowsGrid = gridRows,
        )
    }

    /** The absolute position of a node and of everything under it. */
    private fun place(
        id: String,
        centre: Double,
        y: Double,
        children: Map<String, List<String>>,
        blocks: Map<String, Block>,
        cells: Map<String, Cell>,
        depths: Map<String, Double>,
        positions: MutableMap<String, Position>,
        previous: Map<String, Position>?,
    ) {
        val frozen = previous?.get(id)
        val block = blocks.getValue(id)
        val x = frozen?.x ?: centre
        val row = if (frozen != null) -frozen.y / rowPitch else y

        // The tree grows downward: the root is the highest point, the work is below it. JS `-0 || 0` is 0.
        val rowY = -row * rowPitch
        positions[id] = Vec3(
            x,
            if (rowY == 0.0 || rowY.isNaN()) 0.0 else rowY,
            (depths[id] ?: 0.0) * depthStep,
        )

        val kids = children[id].orEmpty()
        if (kids.isEmpty()) return

        val start = x - block.width / 2

        for (kid in kids) {
            val cell = cells.getValue(kid)
            val gridRow = block.rowsGrid[cell.row]
            place(
                kid,
                start + cell.column * cell.width + cell.width / 2,
                y + gridRow.offset + 1,
                children,
                blocks,
                cells,
                depths,
                positions,
                previous,
            )
        }
    }
}
