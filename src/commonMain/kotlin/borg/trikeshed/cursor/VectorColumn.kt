package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.intp.Selection

/**
 * A column of fixed-width float vectors, row-major in one array: row `r` is `flat[r * dims until (r + 1) * dims]`.
 * The kernels read it in place, so a distance over the whole column is one pass over contiguous floats.
 */
class VectorColumn(val flat: FloatArray, val dims: Int, val rows: Int) {
    init { require(flat.size == dims * rows) { "${flat.size} floats are not $rows vectors of $dims" } }

    /** Row [row] as a fresh array. */
    operator fun get(row: Int): FloatArray = flat.copyOfRange(row * dims, (row + 1) * dims)

    fun gather(sel: Selection, m: Int): VectorColumn {
        val out = FloatArray(m * dims)
        for (j in 0 until m) flat.copyInto(out, j * dims, sel[j] * dims, (sel[j] + 1) * dims)
        return VectorColumn(out, dims, m)
    }

    companion object {
        /** Rows packed in order; every non-null row must have [dims] floats, a null row is zeros. */
        fun of(dims: Int, rows: Int, row: (Int) -> FloatArray?): VectorColumn {
            val flat = FloatArray(dims * rows)
            for (r in 0 until rows) row(r)?.let { v ->
                require(v.size == dims) { "row $r has ${v.size} floats, the column has $dims" }
                v.copyInto(flat, r * dims)
            }
            return VectorColumn(flat, dims, rows)
        }
    }
}

/** A vector column's metadata: an array whose child is float. */
fun vectorMeta(name: CharSequence): ColumnMeta = ColumnMeta(name, IOMemento.IoArray, ColumnMeta("", IOMemento.IoFloat))

val ColumnMeta.isVector: Boolean get() = type == IOMemento.IoArray && child?.type == IOMemento.IoFloat

/**
 * Embedding results (`ModelMux.embed`: id × vector) as a column-major Cursor: ids dictionary-coded,
 * vectors packed flat, so pgvector distances over it run on the float kernels. Every vector must have
 * the width of the first.
 */
fun embeddingCursor(rows: Series<Join<String, Series<Double>>>, id: String = "id", vector: String = "embedding"): ColumnCursor {
    val n = rows.size
    val dims = if (n == 0) 0 else rows[0].b.size
    val column = VectorColumn.of(dims, n) { r ->
        val v = rows[r].b
        require(v.size == dims) { "embedding $r has ${v.size} dimensions, the first has $dims" }
        FloatArray(dims) { v[it].toFloat() }
    }
    return ColumnCursor.of(
        arrayOf(ColumnMeta(id, IOMemento.IoString), vectorMeta(vector)),
        arrayOf(DenseColumn(DictColumn.of(Array(n) { rows[it].a })), DenseColumn(column)),
    )
}
