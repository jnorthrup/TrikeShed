package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/** A structural, snapshotted key at the hash-index boundary; Series itself has identity equality. */
internal class CursorKey(val cells: Array<Any?>) : Series<Any?> by (cells.size j cells::get) {
    override fun equals(other: Any?): Boolean = other is CursorKey && cells.contentEquals(other.cells)
    override fun hashCode(): Int = cells.contentHashCode()
}

internal fun RowVec.key(axis: IntArray): CursorKey {
    val source = values
    return CursorKey(Array(axis.size) { source[axis[it]] })
}

/** Validate ordinals without evaluating values outside the key columns. */
internal fun Cursor.requireColumns(columns: IntArray) {
    require(columns.all { it >= 0 }) { "Column indices must be nonnegative" }
    if (size > 0) require(columns.all { it < width }) { "Column index outside cursor width $width" }
}

/** First-occurrence group order, source order within groups. Only keys and row ordinals materialize. */
fun Cursor.groupClusters(axis: IntArray): Series<IntArray> {
    requireColumns(axis)
    val clusters = linkedMapOf<CursorKey, MutableList<Int>>()
    for (r in 0 until size) clusters.getOrPut(this[r].key(axis)) { mutableListOf() }.add(r)
    val slabs = Array(clusters.size) { IntArray(0) }
    clusters.values.forEachIndexed { i, indices -> slabs[i] = indices.toIntArray() }
    return slabs.size j slabs::get
}

/** Group by axis columns; non-key columns are lazy Series with array metadata and the original child schema. */
fun Cursor.groupBy(vararg axis: Int): Cursor {
    val slabs = groupClusters(axis)
    if (size == 0) return this
    val axisSet = BooleanArray(width) { it in axis }
    return slabs.size j { group ->
        val rows = slabs[group]
        val exemplar = this[rows[0]]
        exemplar.size j { column ->
            val cell = exemplar[column]
            if (axisSet[column]) cell
            else (rows.size j { i: Int -> this[rows[i]].values[column] }) j {
                val meta = cell.b()
                ColumnMeta(meta.name, IOMemento.IoArray, meta)
            }
        }
    }
}

/** Reduce non-key columns in source order with a null initial accumulator, retaining the exemplar metadata. */
inline fun Cursor.groupBy(axis: IntArray, crossinline reducer: RowReducer): Cursor {
    val slabs = groupClusters(axis)
    if (size == 0) return this
    val axisSet = BooleanArray(width) { it in axis }
    return slabs.size j { group ->
        val rows = slabs[group]
        val exemplar = this[rows[0]]
        exemplar.size j { column ->
            val cell = exemplar[column]
            if (axisSet[column]) cell
            else {
                var accumulator: Any? = null
                for (row in rows) accumulator = reducer(accumulator, this[row].values[column])
                accumulator j cell.b
            }
        }
    }
}

/** Stable row ordering with an explicit key comparator; no stringification of numeric keys. */
fun Cursor.ordered(axis: IntArray, comparator: Comparator<Series<Any?>>): Cursor {
    requireColumns(axis)
    val keys = Array(size) { row -> this[row].key(axis) }
    val indices = Array(size) { it }
    indices.sortWith(Comparator { left, right -> comparator.compare(keys[left], keys[right]) })
    return size j { row -> this[indices[row]] }
}
