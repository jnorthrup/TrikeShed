package borg.trikeshed.cursor

import borg.trikeshed.lib.*

typealias RowReducer = (Any?, Any?) -> Any?

@PublishedApi internal data class GroupState(
    val keys: List<List<Any?>>,
    val slabs: List<IntArray>,
    val colCount: Int,
    val axisPos: IntArray,
    val axisSet: Collection<Int>,
)

@PublishedApi internal fun Cursor.buildGroups(axis: IntArray): GroupState {
    val axisSet = if (axis.size > 16) axis.toHashSet() else axis.toList()
    val clusters = linkedMapOf<List<Any?>, IntAccumulator>()
    for (r in 0 until size) {
        val row = this[r]
        val key = axis.map { row[it].a }
        clusters.getOrPut(key) { IntAccumulator() }.add(r)
    }
    val keys = clusters.keys.toList()
    val slabs = clusters.values.map { acc -> acc.toIntArray().also { acc.close() } }
    val colCount = row(0).size
    val axisPos = IntArray(colCount) { -1 }.also { a -> axis.forEachIndexed { pos, col -> a[col] = pos } }
    return GroupState(keys, slabs, colCount, axisPos, axisSet)
}

/** Group by axis columns; non-key columns become Series<Any?> of grouped row values. */
fun Cursor.groupBy(vararg axis: Int): Cursor {
    if (size == 0) return this
    val (keys, slabs, colCount, axisPos, axisSet) = buildGroups(axis)
    val cm = meta
    return keys.size j { cy ->
        val rowIndices = slabs[cy]; val key = keys[cy]
        colCount j { cx ->
            if (cx in axisSet) key[axisPos[cx]] j { cm[cx] }
            else (rowIndices.size j { i: Int -> this[rowIndices[i]][cx].a }) j { cm[cx] }
        }
    }
}

/** Group by axis columns, reducing non-key columns with [reducer]. */
inline fun Cursor.groupBy(axis: IntArray, crossinline reducer: RowReducer): Cursor {
    if (size == 0) return this
    val (keys, slabs, colCount, axisPos, axisSet) = buildGroups(axis)
    val cm = meta
    val valueIndices = (0 until colCount).filter { it !in axisSet }
    return keys.size j { cy ->
        val rowIndices = slabs[cy]; val key = keys[cy]
        val acc = arrayOfNulls<Any?>(colCount)
        for (ri in rowIndices) for (cx in valueIndices) acc[cx] = reducer(acc[cx], this[ri][cx].a)
        colCount j { cx ->
            if (cx in axisSet) key[axisPos[cx]] j { cm[cx] }
            else acc[cx] j { cm[cx] }
        }
    }
}
