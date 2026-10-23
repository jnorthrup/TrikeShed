package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.parse.confix.columnCell
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import borg.trikeshed.parse.confix.kids
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.src
import borg.trikeshed.parse.confix.tag
import borg.trikeshed.parse.kursive.sql.compareCells
import borg.trikeshed.parse.kursive.sql.compile
import borg.trikeshed.parse.kursive.sql.confixCellOf
import borg.trikeshed.parse.kursive.sql.integral
import borg.trikeshed.parse.kursive.sql.ordinalOf
import borg.trikeshed.parse.kursive.sql.scalarText
import borg.trikeshed.cas.TermPostings

/**
 * The single executor of [QueryK] stages: one `when`, one cast. A [ColumnCursor] source runs the
 * stage on the kernels in [ops] when its shape is covered, and otherwise falls through to the
 * closure path below, which handles every shape and every Cursor.
 */
@Suppress("UNCHECKED_CAST")
fun <R> Cursor.facet(
    key: QueryK<R>,
    ops: ColumnOperations = KotlinColumnOperations,
    compiler: ExprCompiler = ClosureExprCompiler,
): R {
    val columnar = this as? ColumnCursor
    return when (key) {
        is QueryK.Filter -> columnar?.routeFilter(key.where, ops) ?: filterRows(key, compiler)
        is QueryK.Derive -> columnar?.routeDerive(key, ops) ?: derive(key, compiler)
        is QueryK.Project -> project(key)
        is QueryK.Widen -> columnar?.routeWiden(key, ops) ?: widen(key)
        is QueryK.Aggregate -> columnar?.routeAggregate(key, ops) ?: aggregate(key)
        is QueryK.Order -> columnar?.routeOrder(key) ?: order(key)
        is QueryK.Unnest -> unnest(key)
        is QueryK.LexicalTopK -> lexicalTopK(key)
        is QueryK.Fuse -> fuse(key)
        is QueryK.Receipt -> (this as? QueryCursor ?: error("a receipt belongs to a QueryCursor")).receipt
    } as R
}

/** Column names from the row-0 exemplar; empty when there are no rows. */
val Cursor.names: Series<CharSequence> get() = if (size == 0) emptySeriesOf() else columnNames

fun Cursor.filterRows(stage: QueryK.Filter, compiler: ExprCompiler = ClosureExprCompiler): Cursor {
    if (size == 0) return this
    val test by lazy { compiler.compile(stage.where, names) }
    return filter { row -> test(row) == true }
}

fun Cursor.derive(stage: QueryK.Derive, compiler: ExprCompiler = ClosureExprCompiler): Cursor {
    if (size == 0) return this
    val expr = compiler.compile(stage.expr, names)
    val meta by lazy { ColumnMeta(stage.name, mementoOf(expr(this[0]))) }
    val metas = this[0].metas
    val width = metas.size
    val widened: Series<`ColumnMeta↻`> = width + 1 j { c: Int -> if (c < width) metas[c] else ({ meta }) }
    return size j { r: Int ->
        val row = this[r]
        val values = row.values
        ReifiedSplitSeries2(width + 1 j { c: Int -> if (c < width) values[c] else expr(row) }, widened)
    }
}

/** The postings are built over the column per statement (brute force, as the vector kernels are). */
fun Cursor.lexicalTopK(stage: QueryK.LexicalTopK): Cursor {
    if (size == 0) return this
    val column = names.ordinalOf(stage.column)
    val postings = TermPostings()
    for (r in 0 until size) this[r].values[column]?.let { postings.upsert(r, scalarText(it)) }
    val hits = postings.topK(stage.query, if (stage.k < 0) size else stage.k)
    val picked = if (stage.k < 0) hits.sortedBy { it.key as Int } else hits
    val scores = DoubleArray(picked.size) { picked[it].score }
    val rows = IntArray(picked.size) { picked[it].key as Int }
    val meta: `ColumnMeta↻` = { ColumnMeta(stage.name, IOMemento.IoDouble) }
    val metas = this[0].metas
    val width = metas.size
    val widened: Series<`ColumnMeta↻`> = width + 1 j { c: Int -> if (c < width) metas[c] else meta }
    return rows.size j { i: Int ->
        val values = this[rows[i]].values
        ReifiedSplitSeries2(width + 1 j { c: Int -> if (c < width) values[c] else scores[i] }, widened)
    }
}

fun Cursor.project(stage: QueryK.Project): Cursor {
    if (size == 0) return this
    val names = names
    val ordinals = ArrayList<Int>()
    val outputs = ArrayList<String>()
    for (pair in stage.columns.view) {
        if (pair.a == "*") for (c in 0 until names.size) {
            // `#n` columns are the planner's hidden derivations (ORDER BY expressions, aggregate arguments).
            if (names[c].startsWith("#")) continue
            ordinals += c
            outputs += names[c].toString().substringAfterLast('.')
        } else {
            ordinals += names.ordinalOf(pair.a)
            outputs += pair.b
        }
    }
    val exemplar = this[0].metas
    val metas = Array(ordinals.size) { i ->
        exemplar[ordinals[i]]().renamed(outputs[i])
    }
    val metaSeries: Series<`ColumnMeta↻`> = metas.size j { c: Int -> { metas[c] } }
    val picks = ordinals.toIntArray()
    return size j { r: Int ->
        val values = this[r].values
        ReifiedSplitSeries2(picks.size j { c: Int -> values[picks[c]] }, metaSeries)
    }
}

fun Cursor.widen(stage: QueryK.Widen): Cursor {
    if (size == 0) return this
    if (stage.right.size == 0) return if (stage.outer) this else emptySeriesOf()
    val left = names
    val right = stage.right.names
    val on = IntArray(stage.on.size) { left.ordinalOf(stage.on[it].a) } j
        IntArray(stage.on.size) { right.ordinalOf(stage.on[it].b) }
    return join(this, stage.right, on, stage.outer)
}

fun Cursor.aggregate(stage: QueryK.Aggregate): Cursor {
    val reductions = stage.reductions
    if (size == 0) {
        if (stage.axis.size > 0) return this
        val metas = Array(reductions.size) { i -> ColumnMeta(reductions[i].name, reductionType(reductions[i].reducer, null, null)) }
        val values = Array<Any?>(reductions.size) { i -> if (reductions[i].reducer == Reduce.COUNT) 0L else null }
        val metaSeries: Series<`ColumnMeta↻`> = metas.size j { c: Int -> { metas[c] } }
        return 1 j { _: Int -> ReifiedSplitSeries2(values.size j values::get, metaSeries) }
    }
    val names = names
    val axis = IntArray(stage.axis.size) { names.ordinalOf(stage.axis[it]) }
    val columns = IntArray(reductions.size) { i -> reductions[i].column?.let(names::ordinalOf) ?: -1 }
    val groups = groupClusters(axis)
    val exemplar = this[0].metas
    val results = Array(groups.size) { g ->
        val rows = groups[g]
        Array(reductions.size) { i -> reduce(reductions[i].reducer, columns[i], rows) }
    }
    val metas = Array(axis.size + reductions.size) { c ->
        if (c < axis.size) exemplar[axis[c]]()
        else {
            val i = c - axis.size
            val input = columns[i].takeIf { it >= 0 }?.let { exemplar[it]().type }
            ColumnMeta(reductions[i].name, reductionType(reductions[i].reducer, input, results.firstNotNullOfOrNull { it[i] }))
        }
    }
    val metaSeries: Series<`ColumnMeta↻`> = metas.size j { c: Int -> { metas[c] } }
    return groups.size j { g: Int ->
        val exemplarRow = this[groups[g][0]].values
        ReifiedSplitSeries2(metas.size j { c: Int ->
            if (c < axis.size) exemplarRow[axis[c]] else results[g][c - axis.size]
        }, metaSeries)
    }
}

fun Cursor.reduce(reducer: Reduce, column: Int, rows: IntArray): Any? {
    if (reducer == Reduce.COUNT) {
        if (column < 0) return rows.size.toLong()
        var n = 0L
        for (r in rows) if (this[r].values[column] != null) n++
        return n
    }
    var count = 0L
    var whole = 0L
    var fraction = 0.0
    var exact = true
    var best: Any? = null
    for (r in rows) {
        val value = this[r].values[column] ?: continue
        count++
        when (reducer) {
            Reduce.SUM, Reduce.AVG -> {
                val number = value as? Number ?: error("$reducer over non-numeric ${value::class.simpleName}")
                if (exact && integral(number)) whole += number.toLong()
                else { if (exact) { fraction = whole.toDouble(); exact = false }; fraction += number.toDouble() }
            }
            Reduce.MIN -> if (best == null || compareCells(value, best) < 0) best = value
            Reduce.MAX -> if (best == null || compareCells(value, best) > 0) best = value
            Reduce.COUNT -> Unit
        }
    }
    if (count == 0L) return null
    return when (reducer) {
        Reduce.SUM -> if (exact) whole else fraction
        Reduce.AVG -> (if (exact) whole.toDouble() else fraction) / count
        else -> best
    }
}

fun reductionType(reducer: Reduce, input: TypeMemento?, sample: Any?): TypeMemento = when (reducer) {
    Reduce.COUNT -> IOMemento.IoLong
    Reduce.AVG -> IOMemento.IoDouble
    Reduce.SUM -> when {
        sample != null -> if (sample is Double) IOMemento.IoDouble else IOMemento.IoLong
        input == IOMemento.IoLong || input == IOMemento.IoInt -> IOMemento.IoLong
        else -> IOMemento.IoDouble
    }
    Reduce.MIN, Reduce.MAX -> input ?: mementoOf(sample)
}

fun Cursor.order(stage: QueryK.Order): Cursor {
    if (size == 0) return this
    val sorted = if (stage.axis.size == 0) this else {
        val names = names
        val axis = IntArray(stage.axis.size) { names.ordinalOf(stage.axis[it]) }
        val descending = stage.descending
        val nullsFirst = stage.nullsFirst
        ordered(axis, Comparator { left, right ->
            for (i in axis.indices) {
                val l = left[i]
                val r = right[i]
                val c = when {
                    l == null && r == null -> 0
                    l == null -> if (nullsFirst[i]) -1 else 1
                    r == null -> if (nullsFirst[i]) 1 else -1
                    else -> compareCells(l, r).let { if (descending[i]) -it else it }
                }
                if (c != 0) return@Comparator c
            }
            0
        })
    }
    val from = stage.offset.coerceIn(0, sorted.size)
    val until = if (stage.limit < 0) sorted.size else minOf(sorted.size, from + stage.limit)
    return if (from == 0 && until == sorted.size) sorted else sorted.range(from until until)
}

fun Cursor.unnest(stage: QueryK.Unnest): Cursor {
    if (size == 0) return this
    val column = names.ordinalOf(stage.column)
    val rows = IntArrayList()
    val elements = IntArrayList()
    for (r in 0 until size) {
        val value = this[r].values[column] ?: continue
        val cell = confixCellOf(value)?.takeIf { value !is String && it.row.tag == IOMemento.IoArray }
        if (cell == null) { rows.add(r); elements.add(-1); continue }
        for (e in 0 until cell.row.kids.size) { rows.add(r); elements.add(e) }
    }
    val exemplar = this[0].metas
    val element by lazy {
        val first = (0 until rows.size).firstOrNull { elements[it] >= 0 }?.let { valueAt(rows[it], column, elements[it]) }
        ColumnMeta(exemplar[column]().name, mementoOf(first))
    }
    val metas: Series<`ColumnMeta↻`> = exemplar.size j { c: Int -> if (c == column) ({ element }) else exemplar[c] }
    return rows.size j { i: Int ->
        val values = this[rows[i]].values
        ReifiedSplitSeries2(values.size j { c: Int ->
            if (c == column && elements[i] >= 0) valueAt(rows[i], column, elements[i]) else values[c]
        }, metas)
    }
}

fun Cursor.valueAt(row: Int, column: Int, element: Int): Any? {
    val cell = confixCellOf(this[row].values[column])!!
    return columnCell(cell.row.kids[element], cell.src, null)
}

/** The memento a value answers to, for metadata of computed columns. */
fun mementoOf(value: Any?): TypeMemento = when (value) {
    null -> IOMemento.IoNothing
    is Boolean -> IOMemento.IoBoolean
    is Int, is Short, is Byte -> IOMemento.IoInt
    is Long -> IOMemento.IoLong
    is Float -> IOMemento.IoFloat
    is Double -> IOMemento.IoDouble
    is String -> IOMemento.IoString
    is ByteArray -> IOMemento.IoBytes
    else -> if (confixCellOf(value) != null) IOMemento.IoObject else IOMemento.IoString
}
