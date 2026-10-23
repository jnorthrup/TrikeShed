package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/**
 * Equi-join widening [left] with [right] on key ordinals `on.a` (left) and `on.b` (right).
 *
 * The right side is the build side: one hash table from key to right-row ordinals. The probe
 * walks left rows in order, so the output keeps left order and, within one left row, right
 * order. A key holding null never matches (SQL equality). Integral numbers key as Long and
 * fractions as Double, so `1` and `1.0` meet. With [outer], an unmatched left row is widened
 * with nulls carrying the right side's row-0 metadata.
 *
 * Only the pair index materializes (two IntArrays); rows widen lazily.
 */
fun join(left: Cursor, right: Cursor, on: Twin<IntArray>, outer: Boolean = false): Cursor {
    require(on.a.size == on.b.size) { "join key arity ${on.a.size} != ${on.b.size}" }
    if (left.size == 0) return left
    if (right.size == 0) return if (outer) left else emptySeriesOf()
    left.requireColumns(on.a)
    right.requireColumns(on.b)
    val build = HashMap<CursorKey, IntArrayList>()
    for (r in 0 until right.size) {
        val key = right[r].joinKey(on.b) ?: continue
        build.getOrPut(key) { IntArrayList() }.add(r)
    }
    val lefts = IntArrayList()
    val rights = IntArrayList()
    for (l in 0 until left.size) {
        val matches = left[l].joinKey(on.a)?.let(build::get)
        if (matches != null) for (i in 0 until matches.size) { lefts.add(l); rights.add(matches[i]) }
        else if (outer) { lefts.add(l); rights.add(-1) }
    }
    val rightMetas = right[0].metas
    val nulls: RowVec = ReifiedSplitSeries2(rightMetas.size j { _: Int -> null }, rightMetas)
    return lefts.size j { row: Int ->
        val lr = left[lefts[row]]
        val rr = rights[row].let { if (it < 0) nulls else right[it] }
        val lw = lr.size
        lw + rr.size j { c: Int -> if (c < lw) lr[c] else rr[c - lw] }
    }
}

/** The equi-join key, or null when any key cell is null. */
internal fun RowVec.joinKey(axis: IntArray): CursorKey? {
    val source = values
    val cells = arrayOfNulls<Any?>(axis.size)
    for (i in axis.indices) cells[i] = when (val cell = source[axis[i]]) {
        null -> return null
        is Byte, is Short, is Int, is Long -> (cell as Number).toLong()
        is Float, is Double -> (cell as Number).toDouble().let { d -> if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong() else d }
        else -> cell
    }
    return CursorKey(cells)
}

/** Growable Int buffer for ordinal lists; the backing array is the K `where` vector. */
class IntArrayList(capacity: Int = 4) {
    var array = IntArray(capacity)
    var size = 0
    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(maxOf(4, size * 2))
        array[size++] = value
    }
    operator fun get(index: Int): Int = array[index]
}
