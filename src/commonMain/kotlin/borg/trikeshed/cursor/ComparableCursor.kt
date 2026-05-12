@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ============================================================================
// Comparable cursor operations — RowVec is NOT Comparable.
// Row-by-row comparison extracts leftSeries[col] as Comparable<T> via
// ReifiedSplitSeries2.  This preserves the algebraic thesis: comparable
// columns compose, not whole rows.
// ============================================================================

/** RowVec accessor returning zero-allocation ReifiedSplitSeries2.
 *  Callsite: access leftSeries[col] and rightSeries[col] directly. */
private fun Cursor.rowVecAt(y: Int): ReifiedSplitSeries2<Any?, `ColumnMeta↻`> =
    row(y) as ReifiedSplitSeries2<Any?, `ColumnMeta↻`>

/** Compare rows [i] and [j] by their [col] index, extracting
 *  Comparable<T> from ReifiedSplitSeries2.leftSeries. */
fun <T : Comparable<T>> Cursor.compareAt(col: Int, i: Int, j: Int): Int {
    val a = rowVecAt(i).leftSeries[col] as T
    val b = rowVecAt(j).leftSeries[col] as T
    return a.compareTo(b)
}

/** Multi-column lexicographic compare over [axis] indices. */
fun <T : Comparable<T>> Cursor.compareAtMultiple(axis: IntArray, i: Int, j: Int): Int {
    val rowA = rowVecAt(i)
    val rowB = rowVecAt(j)
    for (col in axis) {
        val a = rowA.leftSeries[col] as? T
        val b = rowB.leftSeries[col] as? T
        if (a != null && b != null) {
            val c = a.compareTo(b)
            if (c != 0) return c
        } else {
            if (a != b) return if (a == null) 1 else -1
        }
    }
    return 0
}

/** Return a lazily-sorted Cursor by the [col] index.
 *  Builds a permutation IntArray via in-place insertion sort.
 *  Outer row access remains lazy: `size j { y -> row(perm[y]) }`.
 */
fun <T : Comparable<T>> Cursor.sortedBy(col: Int): Cursor {
    val perm = IntArray(size) { it }
    insertionSort(perm) { i, j -> compareAt<T>(col, i, j) }
    return size j { y -> row(perm[y]) }
}

/** Lexicographic sort by multiple [axis] columns. */
fun <T : Comparable<T>> Cursor.sortedBy(axis: IntArray): Cursor {
    val perm = IntArray(size) { it }
    insertionSort(perm) { i, j -> compareAtMultiple<T>(axis, i, j) }
    return size j { y -> row(perm[y]) }
}

/** Find indices where column [col] values change — cluster boundaries.
 *  Returns a Series of starting indices for each cluster.
 *  Always includes 0 as the first boundary. */
fun <T : Comparable<T>> Cursor.clusterBoundaries(col: Int): Series<Int> {
    if (size == 0) return emptySeries()
    val perm = IntArray(size) { it }
    insertionSort(perm) { i, j -> compareAt<T>(col, i, j) }
    var count = 1
    val rowRef = this
    for (i in 1 until perm.size) {
        val a = rowRef.rowVecAt(perm[i - 1]).leftSeries[col] as? T
        val b = rowRef.rowVecAt(perm[i]).leftSeries[col] as? T
        if (a != b) count++
    }
    val boundaries = IntArray(count)
    var bi = 0
    boundaries[bi++] = perm[0]
    for (i in 1 until perm.size) {
        val a = rowRef.rowVecAt(perm[i - 1]).leftSeries[col] as? T
        val b = rowRef.rowVecAt(perm[i]).leftSeries[col] as? T
        if (a != b) {
            boundaries[bi++] = perm[i]
        }
    }
    return boundaries.size j { boundaries[it] }
}

/** Cluster via ReifiedSplitSeries2.leftSeries direct extraction.
 *  Returns IntArrays of row indices per cluster. */
fun <T : Comparable<T>> Cursor.clusters(col: Int): Series<IntArray> {
    if (size == 0) return emptySeries()
    val perm = IntArray(size) { it }
    val rowRef = this
    insertionSort(perm) { i, j ->
        val ra = rowRef.rowVecAt(i).leftSeries[col] as? T
        val rb = rowRef.rowVecAt(j).leftSeries[col] as? T
        when {
            ra == null && rb == null -> 0
            ra == null -> 1
            rb == null -> -1
            else -> ra.compareTo(rb)
        }
    }

    var clusterCount = 1
    for (i in 1 until perm.size) {
        val a = rowRef.rowVecAt(perm[i - 1]).leftSeries[col] as? T
        val b = rowRef.rowVecAt(perm[i]).leftSeries[col] as? T
        if (a != b) clusterCount++
    }
    val result = Array(clusterCount) { IntArray(0) }
    var ci = 0
    var start = 0
    for (i in 1 until perm.size) {
        val a = rowRef.rowVecAt(perm[i - 1]).leftSeries[col] as? T
        val b = rowRef.rowVecAt(perm[i]).leftSeries[col] as? T
        if (a != b) {
            result[ci] = perm.sliceArray(start until i)
            ci++
            start = i
        }
    }
    result[ci] = perm.sliceArray(start until perm.size)
    return result.size j { result[it] }
}

// ── Internal helpers ─────────────────────────────────────────────────────

private inline fun insertionSort(perm: IntArray, compare: (Int, Int) -> Int) {
    for (i in 1 until perm.size) {
        val key = perm[i]
        var j = i - 1
        while (j >= 0 && compare(key, perm[j]) < 0) {
            perm[j + 1] = perm[j]
            j--
        }
        perm[j + 1] = key
    }
}
