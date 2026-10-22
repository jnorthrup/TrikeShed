package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import borg.trikeshed.lib.intp.Selection

/**
 * Dictionary-coded strings: `codes[r]` indexes [dictionary]. Equality over the column is an Int
 * compare, and a group key is the code — the DuckDB / Arrow dictionary vector.
 */
class DictColumn(val codes: IntArray, val dictionary: Array<String>) {
    operator fun get(row: Int): String = dictionary[codes[row]]
    fun gather(sel: Selection, m: Int): DictColumn = DictColumn(borg.trikeshed.lib.intp.gather(codes, sel, m), dictionary)
    fun codeOf(value: String): Int = dictionary.indexOf(value)

    companion object {
        /** Codes by first occurrence. */
        fun of(values: Array<String>): DictColumn {
            val index = HashMap<String, Int>()
            val dictionary = ArrayList<String>()
            val codes = IntArray(values.size) { r -> index.getOrPut(values[r]) { dictionary.add(values[r]); dictionary.size - 1 } }
            return DictColumn(codes, dictionary.toTypedArray())
        }
    }
}

/**
 * One column's rows as a primitive array: [data] is a `DoubleArray`, `LongArray`, `IntArray`,
 * `FloatArray`, `BooleanArray`, [DictColumn] or `Array<Any?>`; [valid] (true = present) is null
 * when no row is null, which is what the kernels require.
 */
class DenseColumn(val data: Any, val valid: BooleanArray? = null) {
    val size: Int get() = when (data) {
        is DoubleArray -> data.size
        is LongArray -> data.size
        is IntArray -> data.size
        is FloatArray -> data.size
        is BooleanArray -> data.size
        is DictColumn -> data.codes.size
        is Array<*> -> data.size
        else -> error("not a dense column: ${data::class.simpleName}")
    }

    operator fun get(row: Int): Any? = if (valid != null && !valid[row]) null else when (data) {
        is DoubleArray -> data[row]
        is LongArray -> data[row]
        is IntArray -> data[row]
        is FloatArray -> data[row]
        is BooleanArray -> data[row]
        is DictColumn -> data[row]
        is Array<*> -> data[row]
        else -> error("not a dense column: ${data::class.simpleName}")
    }

    fun gather(sel: Selection, m: Int): DenseColumn = DenseColumn(
        when (data) {
            is DoubleArray -> borg.trikeshed.lib.doublep.gather(data, sel, m)
            is LongArray -> borg.trikeshed.lib.longp.gather(data, sel, m)
            is IntArray -> borg.trikeshed.lib.intp.gather(data, sel, m)
            is FloatArray -> FloatArray(m) { data[sel[it]] }
            is BooleanArray -> BooleanArray(m) { data[sel[it]] }
            is DictColumn -> data.gather(sel, m)
            is Array<*> -> Array<Any?>(m) { data[sel[it]] }
            else -> error("not a dense column: ${data::class.simpleName}")
        },
        valid?.let { v -> BooleanArray(m) { v[sel[it]] } },
    )
}

/**
 * A column-major Cursor: each column is a [DenseColumn], so [dense] is O(1) and the kernels run
 * on the arrays directly (Arrow's RecordBatch). Rows are ordinary RowVec views and box only when
 * read, so every Cursor operation — including the S1 closure path — works on it unchanged.
 *
 * [take] and [with] are lazy: a gathered column copies on its first [dense], so a filter pays
 * only for the columns the rest of the query reads.
 */
class ColumnCursor private constructor(
    val metas: Array<ColumnMeta>,
    val rows: Int,
    private val loader: (Int) -> DenseColumn,
) : Join<Int, (Int) -> RowVec> {
    private val cache = arrayOfNulls<DenseColumn>(metas.size)
    private val metaSeries: Series<`ColumnMeta↻`> = metas.size j { c: Int -> { metas[c] } }

    val width: Int get() = metas.size
    override val a: Int get() = rows

    override val b: (Int) -> RowVec = { r: Int ->
        require(r in 0 until rows) { "row $r outside 0 until $rows" }
        ReifiedSplitSeries2(width j { c: Int -> dense(c)[r] }, metaSeries)
    }

    fun dense(column: Int): DenseColumn = cache[column] ?: loader(column).also { cache[column] = it }

    /** The rows at `sel[0 until m]`, columns gathered on demand. */
    fun take(sel: Selection, m: Int): ColumnCursor = ColumnCursor(metas, m) { c -> dense(c).gather(sel, m) }

    /** This cursor plus one more column, computed on first read. */
    fun with(meta: ColumnMeta, column: () -> DenseColumn): ColumnCursor {
        val extra by lazy(column)
        val w = width
        return ColumnCursor(metas + meta, rows) { c -> if (c < w) dense(c) else extra }
    }

    /** The same columns under new metadata (qualified names); no column copies. */
    fun renamed(newMetas: Array<ColumnMeta>): ColumnCursor {
        require(newMetas.size == width) { "${newMetas.size} metas for $width columns" }
        return ColumnCursor(newMetas, rows) { c -> dense(c) }
    }

    /** Rows [from] until [until] as a view of this cursor. */
    fun slice(from: Int, until: Int): ColumnCursor = take(IntArray(until - from) { from + it }, until - from)

    private var identityCache: Selection? = null
    /** `0 until rows`, shared by every kernel call on this cursor. */
    val identity: Selection get() = identityCache ?: IntArray(rows) { it }.also { identityCache = it }

    companion object {
        /** Left rows `lsel[0 until m]` beside right rows `rsel[0 until m]`: the output of a join. */
        fun widen(left: ColumnCursor, lsel: Selection, right: ColumnCursor, rsel: Selection, m: Int): ColumnCursor {
            val lw = left.width
            return ColumnCursor(left.metas + right.metas, m) { c ->
                if (c < lw) left.dense(c).gather(lsel, m) else right.dense(c - lw).gather(rsel, m)
            }
        }

        fun of(metas: Array<ColumnMeta>, columns: Array<DenseColumn>): ColumnCursor {
            require(metas.size == columns.size) { "${metas.size} metas for ${columns.size} columns" }
            val rows = if (columns.isEmpty()) 0 else columns[0].size
            for (c in columns) require(c.size == rows) { "column of ${c.size} rows in a $rows-row cursor" }
            return ColumnCursor(metas, rows) { c -> columns[c] }
        }

        /** Densify every column of [cursor] once. */
        fun from(cursor: Cursor): ColumnCursor {
            if (cursor is ColumnCursor) return cursor
            if (cursor.size == 0) return ColumnCursor(emptyArray(), 0) { error("no columns") }
            val metas = Array(cursor[0].size) { cursor[0].metas[it]() }
            return ColumnCursor(metas, cursor.size) { c -> cursor.dense(c) }
        }
    }
}

/**
 * Column [column] of this Cursor as a primitive array. A [ColumnCursor] answers its own array
 * without copying; any other Cursor is read once, typed by the column's memento: IoLong / IoInt
 * → `LongArray`, IoDouble → `DoubleArray`, IoFloat → `FloatArray`, IoBoolean → `BooleanArray`,
 * IoString → [DictColumn], anything else `Array<Any?>`. A null cell marks the column's validity.
 */
fun Cursor.dense(column: Int): DenseColumn {
    if (this is ColumnCursor) return dense(column)
    val n = size
    if (n == 0) return DenseColumn(DoubleArray(0))
    val type = this[0].metas[column]().type
    val valid = BooleanArray(n)
    var anyNull = false
    fun cell(r: Int): Any? = this[r].values[column].also { if (it == null) anyNull = true else valid[r] = true }
    val data: Any = when (type) {
        IOMemento.IoLong, IOMemento.IoInt -> LongArray(n) { r -> (cell(r) as? Number)?.toLong() ?: 0L }
        IOMemento.IoDouble -> DoubleArray(n) { r -> (cell(r) as? Number)?.toDouble() ?: Double.NaN }
        IOMemento.IoFloat -> FloatArray(n) { r -> (cell(r) as? Number)?.toFloat() ?: Float.NaN }
        IOMemento.IoBoolean -> BooleanArray(n) { r -> cell(r) as Boolean? ?: false }
        IOMemento.IoString -> DictColumn.of(Array(n) { r -> cell(r)?.toString() ?: "" })
        else -> Array<Any?>(n) { r -> cell(r) }
    }
    return DenseColumn(data, if (anyNull) valid else null)
}
