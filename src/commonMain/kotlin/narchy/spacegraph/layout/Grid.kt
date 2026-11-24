package narchy.spacegraph.layout

import narchy.spacegraph.Vec3

// Port of upstream src/layout/grid.ts at 125b369.
// The grid placement: the first implementation of the seam. Places boxes into columns and rows; append-only by
// construction, so a placed id keeps the position it was given.

/** The grid's vocabulary: the column and the row a box belongs to. */
data class GridMeta(
    /** The column the box belongs to; columns advance on X. */
    val group: String? = null,
    /** The row the box belongs to; rows advance on Y. It wins over the lane. */
    val row: Int? = null,
    /** The lane the box belongs to; the lane's index defines its row. */
    val lane: String? = null,
    /** An X offset inside the column, for an indented box. */
    val indent: Double? = null,
    /** The width the host declares for the column, when it knows it up front. */
    val columnWidth: Double? = null,
    /** The item declares an empty column and carries no box: an empty turn still takes its place. */
    val empty: Boolean? = null,
)

data class GridConfig(
    val lanes: List<String>? = null,
    val lanePitch: Double? = null,
    val step: Double? = null,
    val groupGap: Double? = null,
    val depthStep: Double? = null,
    val defaultLane: String? = null,
    /** The narrowest a column may be. */
    val minColumnWidth: Double? = null,
)

class GridPlacement(config: GridConfig) : Placement<GridMeta> {
    override val name = "grid"
    private val layout = LaneLayout(
        LaneLayoutConfig(
            lanes = config.lanes,
            lanePitch = config.lanePitch,
            step = config.step,
            groupGap = config.groupGap,
            depthStep = config.depthStep,
            defaultLane = config.defaultLane,
        ),
    )
    private val minColumnWidth: Double? = config.minColumnWidth
    private val declared = HashSet<String>()

    override fun place(items: List<PlacementItem<GridMeta>>, previous: Map<String, Position>?): Map<String, Position> {
        var group: String? = null

        for (item in items) {
            val next = item.meta?.group
            if (next != group) {
                close(group)
                group = next
                if (group != null) {
                    val declaredWidth = item.meta?.columnWidth
                    if (declaredWidth != null) declared.add(group)
                    layout.open(group, declaredWidth)
                }
            }

            if (item.meta?.empty == true) continue

            layout.place(
                LaneItem(
                    id = item.id,
                    lane = item.meta?.lane,
                    row = item.meta?.row,
                    group = group,
                    width = item.width,
                    indent = item.meta?.indent,
                    depth = item.depth,
                ),
            )
        }

        close(group)

        return layout.positions.mapValues { (_, p) -> Vec3(p[0], p[1], p[2]) }
    }

    /**
     * Close the column. A column the host declared a width for keeps it; a measured one grows to the narrowest
     * the host allows.
     */
    private fun close(group: String?) {
        if (group == null) return
        val span = if (group in declared) null else maxOf(layout.width(group), minColumnWidth ?: 0.0)
        layout.close(group, span)
    }
}
