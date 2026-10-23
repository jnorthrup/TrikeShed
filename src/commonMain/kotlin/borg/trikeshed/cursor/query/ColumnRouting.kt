package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations

/**
 * Kernel routing for column-major sources. Each `route*` answers null when the stage's shape is
 * outside what the kernels cover (a null in the data, a mixed-type expression, an operator
 * without a kernel), and [facet] then runs the closure path, which handles every shape.
 * A routed stage returns exactly what the closure path returns.
 */

private fun ColumnCursor.ordinals(): Series<CharSequence> = metas.size j { c: Int -> metas[c].name }

private fun literalOf(e: Expr): Any? = (e as? LitExpr)?.lit?.let {
    when (it) {
        is NumericLiteral -> it.value
        is StringLiteral -> it.asString()
        else -> null
    }
}

private fun comparisonOf(op: String, flip: Boolean): Comparison? = when (op) {
    "=" -> Comparison.EQ
    "!=", "<>" -> Comparison.NE
    "<" -> if (flip) Comparison.GT else Comparison.LT
    "<=" -> if (flip) Comparison.GE else Comparison.LE
    ">" -> if (flip) Comparison.LT else Comparison.GT
    ">=" -> if (flip) Comparison.LE else Comparison.GE
    else -> null
}

internal fun ColumnCursor.routeFilter(where: Expr, ops: ColumnOperations): Cursor? {
    if (rows == 0) return null
    val names = ordinals()
    val terms = conjuncts(where).map { e ->
        val b = e as? BinaryExpr ?: return null
        val flip = b.left is LitExpr
        val ref = (if (flip) b.right else b.left) as? ColumnRef ?: return null
        val operand = literalOf(if (flip) b.left else b.right) ?: return null
        Triple(names.ordinalOf(ref.id.asString()), comparisonOf(b.op, flip) ?: return null, operand)
    }
    var sel = IntArray(rows)
    var m = -1
    for ((column, op, operand) in terms) {
        val dense = dense(column)
        if (dense.valid != null) return null
        val first = m < 0
        val count = when (val data = dense.data) {
            is DoubleArray -> {
                val x = (operand as? Number)?.toDouble() ?: return null
                if (first) ops.whereF64(data, rows, op.ordinal, x, sel) else ops.refineF64(data, sel, m, op.ordinal, x, sel)
            }
            is LongArray -> {
                if (operand !is Long && operand !is Int) return null
                val x = (operand as Number).toLong()
                if (first) ops.whereI64(data, rows, op.ordinal, x, sel) else ops.refineI64(data, sel, m, op.ordinal, x, sel)
            }
            is DictColumn -> {
                if (operand !is String || (op != Comparison.EQ && op != Comparison.NE)) return null
                val code = data.codeOf(operand)
                if (first) borg.trikeshed.lib.intp.where(data.codes, rows, op, code, sel)
                else borg.trikeshed.lib.intp.refine(data.codes, sel, m, op, code, sel)
            }
            else -> return null
        }
        m = count
    }
    if (m < 0) return null
    return if (m == rows) this else take(sel, m)
}

/** The Double array an expression computes over this cursor's rows, or null outside the kernels' shapes. */
private fun ColumnCursor.denseExpr(e: Expr, ops: ColumnOperations): DoubleArray? {
    when (e) {
        is ColumnRef -> {
            val dense = dense(ordinals().ordinalOf(e.id.asString()))
            return if (dense.valid == null) dense.data as? DoubleArray else null
        }
        is BinaryExpr -> {
            val op = e.op
            if (op != "+" && op != "-" && op != "*" && op != "/") return null
            val lit = literalOf(e.left) as? Number
            val rit = literalOf(e.right) as? Number
            val out = DoubleArray(rows)
            when {
                lit != null && rit != null -> return null
                rit != null -> {
                    val a = denseExpr(e.left, ops) ?: return null
                    val x = rit.toDouble()
                    when (op) {
                        "+" -> ops.affineF64(a, 1.0, x, identity, rows, out)
                        "-" -> ops.affineF64(a, 1.0, -x, identity, rows, out)
                        "*" -> ops.affineF64(a, x, 0.0, identity, rows, out)
                        else -> return null
                    }
                }
                lit != null -> {
                    val a = denseExpr(e.right, ops) ?: return null
                    val x = lit.toDouble()
                    when (op) {
                        "+" -> ops.affineF64(a, 1.0, x, identity, rows, out)
                        "-" -> ops.affineF64(a, -1.0, x, identity, rows, out)
                        "*" -> ops.affineF64(a, x, 0.0, identity, rows, out)
                        else -> return null
                    }
                }
                else -> {
                    val a = denseExpr(e.left, ops) ?: return null
                    val b = denseExpr(e.right, ops) ?: return null
                    when (op) {
                        "+" -> borg.trikeshed.lib.doublep.add(a, b, rows, out)
                        "-" -> borg.trikeshed.lib.doublep.sub(a, b, rows, out)
                        "*" -> ops.mulF64(a, b, identity, rows, out)
                        else -> borg.trikeshed.lib.doublep.div(a, b, rows, out)
                    }
                }
            }
            return out
        }
        else -> return null
    }
}

/** The constant vector an expression stands for: a literal, or a literal cast to `vector`. Null for anything per-row. */
private fun constantVector(e: Expr): FloatArray? {
    val lit = when {
        e is LitExpr -> e
        e is Cast && e.type == "vector" -> e.expr as? LitExpr
        else -> null
    } ?: return null
    return runCatching { vectorOf(literal(lit.lit)) }.getOrNull()
}

/**
 * `column <=> probe`, `<->` and `<#>` over a vector column and a constant: one kernel pass over the
 * flat array, then the same scalar the closure path computes per row (`1 - cosine`, `sqrt(l2²)`, `-dot`),
 * so the answers are identical. A null row stays null. A width mismatch is left to the closure path,
 * which reports it as the row is read.
 */
private fun ColumnCursor.vectorDistance(e: BinaryExpr, ops: ColumnOperations): DenseColumn? {
    if (e.op != "<=>" && e.op != "<->" && e.op != "<#>") return null
    val ref = (e.left as? ColumnRef) ?: (e.right as? ColumnRef) ?: return null
    val probe = constantVector(if (ref === e.left) e.right else e.left) ?: return null
    val column = dense(ordinals().ordinalOf(ref.id.asString()))
    val vectors = column.data as? VectorColumn ?: return null
    if (vectors.dims == 0 || probe.size != vectors.dims) return null
    val raw = FloatArray(rows)
    val scores = when (e.op) {
        "<=>" -> { ops.cosineF32(vectors.flat, rows, vectors.dims, probe, raw); DoubleArray(rows) { 1.0 - raw[it] } }
        "<->" -> { ops.l2SquaredF32(vectors.flat, rows, vectors.dims, probe, raw); DoubleArray(rows) { kotlin.math.sqrt(raw[it].toDouble()) } }
        else -> { ops.dotF32(vectors.flat, rows, vectors.dims, probe, raw); DoubleArray(rows) { -raw[it].toDouble() } }
    }
    return DenseColumn(scores, column.valid)
}

internal fun ColumnCursor.routeDerive(stage: QueryK.Derive, ops: ColumnOperations): Cursor? {
    if (rows == 0 || stage.expr !is BinaryExpr) return null
    vectorDistance(stage.expr, ops)?.let { distance -> return with(ColumnMeta(stage.name, IOMemento.IoDouble)) { distance } }
    val computed = denseExpr(stage.expr, ops) ?: return null
    return with(ColumnMeta(stage.name, IOMemento.IoDouble)) { DenseColumn(computed) }
}

internal fun ColumnCursor.routeAggregate(stage: QueryK.Aggregate, ops: ColumnOperations): Cursor? {
    if (rows == 0) return null
    val names = ordinals()
    val axis = IntArray(stage.axis.size) { names.ordinalOf(stage.axis[it]) }
    val reductions = stage.reductions
    val axisColumns = Array(axis.size) { dense(axis[it]) }
    for (c in axisColumns) if (c.valid != null) return null
    // Group key: one Long per row. Dictionary axes pack their codes; a lone Long axis is its own key.
    val keys = LongArray(rows)
    when {
        axis.isEmpty() -> Unit
        axis.size == 1 && axisColumns[0].data is LongArray -> (axisColumns[0].data as LongArray).copyInto(keys)
        axisColumns.all { it.data is DictColumn } -> {
            var radix = 1L
            for (c in axisColumns) {
                val dict = c.data as DictColumn
                if (radix > Long.MAX_VALUE / maxOf(1, dict.dictionary.size)) return null
                for (r in 0 until rows) keys[r] = keys[r] * dict.dictionary.size + dict.codes[r]
                radix *= maxOf(1, dict.dictionary.size)
            }
        }
        else -> return null
    }
    val inputs = Array(reductions.size) { i -> reductions[i].column?.let { dense(names.ordinalOf(it)) } }
    for (i in inputs.indices) {
        val input = inputs[i] ?: continue
        if (input.valid != null) return null
        if (reductions[i].reducer != Reduce.COUNT && input.data !is DoubleArray && input.data !is LongArray) return null
    }
    val sel = identity
    val ids = IntArray(rows)
    val firstRow = IntArray(rows)
    val groups = if (axis.isEmpty()) 1.also { firstRow[0] = 0 }
    else {
        val table = IntArray(borg.trikeshed.lib.longp.tableSizeFor(rows))
        ops.groupIdsI64(keys, sel, rows, ids, firstRow, table, LongArray(table.size))
    }
    val counts = LongArray(groups)
    if (axis.isEmpty()) counts[0] = rows.toLong() else ops.countByGroup(ids, rows, counts)

    val outMetas = ArrayList<ColumnMeta>()
    val outColumns = ArrayList<DenseColumn>()
    for (c in axis.indices) {
        outMetas += metas[axis[c]]
        val picked = IntArray(groups) { firstRow[it] }
        outColumns += axisColumns[c].gather(picked, groups)
    }
    for (i in 0 until reductions.size) {
        val red = reductions[i]
        val data = inputs[i]?.data
        fun sumsLong(): LongArray = LongArray(groups).also { s ->
            if (axis.isEmpty()) s[0] = ops.sumI64(data as LongArray, sel, rows) else ops.sumByGroupI64(data as LongArray, sel, ids, rows, s)
        }
        fun sumsDouble(): DoubleArray = DoubleArray(groups).also { s ->
            if (axis.isEmpty()) s[0] = ops.sumF64(data as DoubleArray, sel, rows) else ops.sumByGroupF64(data as DoubleArray, sel, ids, rows, s)
        }
        val (type, column) = when (red.reducer) {
            Reduce.COUNT -> IOMemento.IoLong to DenseColumn(counts)
            Reduce.SUM -> if (data is LongArray) IOMemento.IoLong to DenseColumn(sumsLong()) else IOMemento.IoDouble to DenseColumn(sumsDouble())
            Reduce.AVG -> {
                val sums = if (data is LongArray) sumsLong().let { s -> DoubleArray(groups) { s[it].toDouble() } } else sumsDouble()
                IOMemento.IoDouble to DenseColumn(DoubleArray(groups) { sums[it] / counts[it] })
            }
            Reduce.MIN, Reduce.MAX -> {
                val min = red.reducer == Reduce.MIN
                if (data is LongArray) {
                    val out = LongArray(groups) { if (min) Long.MAX_VALUE else Long.MIN_VALUE }
                    for (r in 0 until rows) { val g = if (axis.isEmpty()) 0 else ids[r]; val v = data[r]; if (if (min) v < out[g] else v > out[g]) out[g] = v }
                    IOMemento.IoLong to DenseColumn(out)
                } else {
                    val arr = data as DoubleArray
                    val out = DoubleArray(groups) { if (min) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY }
                    for (r in 0 until rows) { val g = if (axis.isEmpty()) 0 else ids[r]; val v = arr[r]; if (if (min) v < out[g] else v > out[g]) out[g] = v }
                    IOMemento.IoDouble to DenseColumn(out)
                }
            }
        }
        outMetas += ColumnMeta(red.name, type)
        outColumns += column
    }
    return ColumnCursor.of(outMetas.toTypedArray(), outColumns.toTypedArray())
}

internal fun ColumnCursor.routeOrder(stage: QueryK.Order): Cursor? {
    if (rows == 0 || stage.limit < 0 || stage.axis.size != 1) return null
    val column = dense(ordinals().ordinalOf(stage.axis[0]))
    val scores = column.data as? DoubleArray ?: return null
    if (column.valid != null) return null
    val best = borg.trikeshed.lib.doublep.topK(scores, rows, stage.offset + stage.limit, stage.descending[0])
    val from = minOf(stage.offset, best.size)
    val picked = best.copyOfRange(from, best.size)
    return take(picked, picked.size)
}

internal fun ColumnCursor.routeWiden(stage: QueryK.Widen, ops: ColumnOperations): Cursor? {
    val right = stage.right as? ColumnCursor ?: return null
    if (stage.outer || stage.on.size != 1 || rows == 0 || right.rows == 0) return null
    val leftKey = dense(ordinals().ordinalOf(stage.on[0].a))
    val rightKey = right.dense(right.ordinals().ordinalOf(stage.on[0].b))
    if (leftKey.valid != null || rightKey.valid != null) return null
    val lk = leftKey.data as? LongArray ?: return null
    val rk = rightKey.data as? LongArray ?: return null
    val head = IntArray(borg.trikeshed.lib.longp.tableSizeFor(right.rows)) { -1 }
    val next = IntArray(right.rows)
    borg.trikeshed.lib.longp.hashBuild(rk, right.rows, head, next)
    var capacity = maxOf(rows, 1024)
    while (true) {
        val outLeft = IntArray(capacity)
        val outRight = IntArray(capacity)
        val pairs = borg.trikeshed.lib.longp.probe(lk, rows, rk, head, next, outLeft, outRight)
        if (pairs < 0) { capacity *= 2; continue }
        return ColumnCursor.widen(this, outLeft, right, outRight, pairs)
    }
}
