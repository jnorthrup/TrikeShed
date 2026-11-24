package narchy.spacegraph.layout

import kotlin.math.max
import kotlin.math.min

// Port of upstream src/layout/LaneLayout.ts at 125b369.
// Generic lane layout: deterministic, append-only placement of items into columns and rows. Columns advance on X,
// rows advance on Y. Every (column, row) cell keeps its own cursor. A column's width is measured from the rows it
// holds, or declared when the host knows it up front.
// Expected values in LaneLayoutTest.kt are upstream test/lane-layout.test.ts, copied by hand.

typealias LaneLayoutPosition = DoubleArray

data class LaneLayoutConfig(
    /** Ordered lane ids; a lane's index defines its row. */
    val lanes: List<String>? = null,
    /** The Y distance between rows. */
    val lanePitch: Double? = null,
    /** The X distance between adjacent items in a row. */
    val step: Double? = null,
    /** The X distance between columns. */
    val groupGap: Double? = null,
    /** The Z distance per hierarchy depth. */
    val depthStep: Double? = null,
    /** The lane used when an item names an unknown or missing lane. */
    val defaultLane: String? = null,
)

data class LaneItem(
    val id: String,
    /** The lane the item belongs to; the lane's index defines its row. */
    val lane: String? = null,
    /** The row index, when the host numbers its rows itself. It wins over the lane. */
    val row: Int? = null,
    /** The column the item belongs to; columns advance on X. */
    val group: String? = null,
    /** The box the item occupies; the cell's cursor advances by its width plus the gap. */
    val width: Double? = null,
    /** An X offset inside the column, for an indented item. */
    val indent: Double? = null,
    /** The hierarchy depth recorded in Z. */
    val depth: Double? = null,
)

data class LaneBounds(
    val min: LaneLayoutPosition,
    val max: LaneLayoutPosition,
    val center: LaneLayoutPosition,
    val size: LaneLayoutPosition,
)

private const val DEFAULT_GROUP = "__default__"

class LaneLayout(config: LaneLayoutConfig) {
    private val lanes: List<String> = config.lanes ?: emptyList()
    private val lanePitch: Double = config.lanePitch ?: 56.0
    private val step: Double = config.step ?: 48.0
    private val groupGap: Double = config.groupGap ?: 320.0
    private val depthStep: Double = config.depthStep ?: 64.0
    private val defaultLane: String = config.defaultLane ?: lanes.firstOrNull() ?: DEFAULT_GROUP

    private val placed = LinkedHashMap<String, LaneLayoutPosition>()
    private val columnBase = HashMap<String, Double>()
    private val columnSpan = HashMap<String, Double>()
    private val cellCursor = HashMap<String, Double>()
    private val columnExtent = HashMap<String, Double>()
    private var nextColumnX = 0.0

    val positions: Map<String, LaneLayoutPosition> get() = placed

    /**
     * Assign a position for [item], once. Re-placing a known id returns the stored position, so graph mutations
     * never move earlier items. The position is the item's centre.
     */
    fun place(item: LaneItem): LaneLayoutPosition {
        placed[item.id]?.let { return it }

        val group = item.group ?: DEFAULT_GROUP
        val base = baseOf(group)
        val width = item.width ?: 0.0
        val indent = item.indent ?: 0.0
        val row = rowOf(item)
        val cell = "$group $row"
        val cursor = cellCursor[cell] ?: base

        val position = doubleArrayOf(
            cursor + indent + width / 2,
            row * lanePitch,
            (item.depth ?: 0.0) * depthStep,
        )

        placed[item.id] = position
        cellCursor[cell] = cursor + width + step
        columnExtent[group] = max(columnExtent[group] ?: 0.0, cursor + indent + width - base)

        return position
    }

    fun placeAll(items: List<LaneItem>): Map<String, LaneLayoutPosition> {
        for (item in items) place(item)
        return placed
    }

    /** Open a column where the previous one ended, and declare its width when the host knows it. */
    fun open(group: String, span: Double? = null) {
        baseOf(group)
        if (span != null) columnSpan[group] = span
    }

    /** Close a column: the next one starts after it. The width is the declared one, or the measured. */
    fun close(group: String, span: Double? = null) {
        val base = baseOf(group)
        val width = span ?: columnSpan[group] ?: columnExtent[group] ?: 0.0
        columnSpan[group] = width
        nextColumnX = base + width + groupGap
    }

    /** The width of a column: the declared one, or the widest row it holds. */
    fun width(group: String): Double = columnSpan[group] ?: columnExtent[group] ?: 0.0

    fun bounds(ids: List<String>? = null): LaneBounds? {
        val points = (ids ?: placed.keys.toList()).mapNotNull { placed[it] }
        if (points.isEmpty()) return null
        val minimum = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val maximum = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (point in points) {
            for (i in 0 until 3) {
                minimum[i] = min(minimum[i], point[i])
                maximum[i] = max(maximum[i], point[i])
            }
        }
        val center = DoubleArray(3) { (minimum[it] + maximum[it]) / 2 }
        val size = DoubleArray(3) { maximum[it] - minimum[it] }
        return LaneBounds(minimum, maximum, center, size)
    }

    fun clear() {
        placed.clear()
        columnBase.clear()
        columnSpan.clear()
        cellCursor.clear()
        columnExtent.clear()
        nextColumnX = 0.0
    }

    private fun rowOf(item: LaneItem): Int {
        item.row?.let { return it }
        val index = lanes.indexOf(item.lane ?: defaultLane)
        if (index >= 0) return index
        return max(lanes.indexOf(defaultLane), 0)
    }

    private fun baseOf(group: String): Double {
        columnBase[group]?.let { return it }
        columnBase[group] = nextColumnX
        return nextColumnX
    }
}
