package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import borg.trikeshed.cursor.ReifiedSplitSeries2

/** Aggregate function names the planner lowers to [Reduce] kinds. */
val AGGREGATES: Map<String, Reduce> = mapOf(
    "count" to Reduce.COUNT, "sum" to Reduce.SUM, "min" to Reduce.MIN, "max" to Reduce.MAX, "avg" to Reduce.AVG,
)

fun Expr.aggregated(): Boolean = when (this) {
    is Call -> name in AGGREGATES || args.view.any { it.aggregated() }
    is BinaryExpr -> left.aggregated() || right.aggregated()
    is Path -> cell.aggregated()
    is Cast -> expr.aggregated()
    else -> false
}

/** Rename every column of a scan to `qualifier.name`, so joined tables keep distinct names. */
fun Cursor.qualify(qualifier: String): Cursor {
    if (size == 0) return this
    val exemplar = this[0].metas
    val metas = Array(exemplar.size) { c ->
        val meta = exemplar[c]()
        ColumnMeta("$qualifier.${meta.name}", meta.type, meta.child)
    }
    if (this is ColumnCursor) return renamed(metas)
    val metaSeries: Series<`ColumnMeta↻`> = metas.size j { c: Int -> { metas[c] } }
    return size j { r: Int -> ReifiedSplitSeries2(this[r].values, metaSeries) }
}

/**
 * Lower a SELECT to [QueryK] stages over the resolved tables.
 *
 * Order of stages: scan (qualified by alias) → Widen per JOIN → Filter (WHERE) →
 * Derive / Aggregate (GROUP BY, aggregates) → Filter (HAVING) → Order (ORDER BY, OFFSET, LIMIT)
 * → Project (select list). ORDER BY resolves select-list ordinals, aliases and expressions
 * before projection, so it may also name columns the select list drops.
 */
fun SelectStmt.plan(
    resolve: (String) -> Cursor,
    ops: ColumnOperations = KotlinColumnOperations,
    compiler: ExprCompiler = ClosureExprCompiler,
): QueryCursor {
    val table = requireNotNull(from) { "SELECT without FROM" }
    val source = resolve(table.name.asString()).qualify((table.alias ?: table.name).asString())
    val stages = mutableListOf<QueryK<Cursor>>()

    for (clause in joins.view) {
        val qualifier = (clause.table.alias ?: clause.table.name).asString()
        val right = resolve(clause.table.name.asString()).qualify(qualifier)
        val pairs = mutableListOf<Twin<String>>()
        val rightOnly = mutableListOf<Expr>()
        val residual = mutableListOf<Expr>()
        val prefix = "$qualifier."
        for (c in conjuncts(clause.on)) {
            val l = ((c as? BinaryExpr)?.left as? ColumnRef)?.id?.asString()
            val r = ((c as? BinaryExpr)?.right as? ColumnRef)?.id?.asString()
            val names = c.references()
            when {
                c is BinaryExpr && c.op == "=" && l != null && r != null && r.startsWith(prefix) != l.startsWith(prefix) ->
                    pairs += if (r.startsWith(prefix)) l j r else r j l
                names.isNotEmpty() && names.all { it.startsWith(prefix) } -> rightOnly += c
                !clause.outer -> residual += c
                else -> error("LEFT JOIN $qualifier ON ${c.render()}: only column equalities and $qualifier-only predicates")
            }
        }
        require(pairs.isNotEmpty()) { "JOIN $qualifier needs ON column equalities" }
        val filtered = if (rightOnly.isEmpty()) right
        else QueryCursor(right, s_[QueryK.Filter(rightOnly.reduce { x, y -> BinaryExpr(x, "AND", y) })])
        stages += QueryK.Widen(filtered, pairs.toSeries(), clause.outer)
        for (c in residual) stages += QueryK.Filter(c)
    }
    where?.let { stages += QueryK.Filter(it) }

    val items = columns.view.toList()
    val star = items.size == 1 && (items[0].expr as? LitExpr)?.lit.let { it is StringLiteral && it.asString() == "*" }
    /** Per select item: the pre-projection source column and its display name. */
    val sources = ArrayList<Twin<String>>()
    var derived = 0
    fun derive(expr: Expr): String {
        val name = "#${derived++}"
        stages += QueryK.Derive(name, expr)
        return name
    }

    var residual = having
    if (groupBy.size > 0 || items.any { it.expr.aggregated() } || residual?.aggregated() == true) {
        val axis = groupBy.view.map { e -> (e as? ColumnRef)?.id?.asString() ?: derive(e) }
        val reductions = mutableListOf<Reduction>()
        val byText = linkedMapOf<String, String>()
        fun reduction(call: Call): String = byText.getOrPut(call.render()) {
            val reducer = AGGREGATES[call.name] ?: error("aggregate ${call.name}")
            val arg = call.args.view.singleOrNull() ?: error("${call.name} takes one argument")
            val column = when (arg) {
                Star -> null.also { require(reducer == Reduce.COUNT) { "${call.name}(*)" } }
                is ColumnRef -> arg.id.asString()
                else -> derive(arg)
            }
            "#r${reductions.size}".also { reductions += Reduction(reducer, column, it) }
        }
        /** Replace aggregate calls by references to their reduction columns. */
        fun lift(e: Expr): Expr = when {
            e is Call && e.name in AGGREGATES -> ColumnRef(Identifier(reduction(e).toSeries()))
            e is BinaryExpr -> BinaryExpr(lift(e.left), e.op, lift(e.right))
            else -> e
        }
        val lifted = items.map { lift(it.expr) }
        residual = residual?.let(::lift)
        val grouped = groupBy.view.map { it.render() }
        stages += QueryK.Aggregate(axis.toSeries(), reductions.toSeries())
        lifted.forEachIndexed { i, e ->
            val group = grouped.indexOf(items[i].expr.render())
            val source = when {
                group >= 0 -> axis[group]
                e is ColumnRef -> e.id.asString()
                else -> derive(e)
            }
            sources += source j displayName(items[i])
        }
    } else if (star) {
        sources += "*" j "*"
    } else for (item in items) {
        val e = item.expr
        sources += (if (e is ColumnRef) e.id.asString() else derive(e)) j displayName(item)
    }
    residual?.let { stages += QueryK.Filter(it) }

    if (orderBy.size > 0 || limit != null || offset != null) {
        val axis = orderBy.view.map { order -> orderSource(order.expr, items, sources, ::derive) }
        stages += QueryK.Order(
            axis.toSeries(),
            BooleanArray(orderBy.size) { orderBy[it].descending },
            limit ?: -1,
            offset ?: 0,
        )
    }
    stages += QueryK.Project(sources.toSeries())
    return QueryCursor(source, stages.toSeries(), ops, compiler)
}

/** The AND-separated conjuncts of [e]. */
fun conjuncts(e: Expr?): List<Expr> = when {
    e == null -> emptyList()
    e is BinaryExpr && e.op.uppercase() == "AND" -> conjuncts(e.left) + conjuncts(e.right)
    else -> listOf(e)
}

/** The column names an expression reads. */
fun Expr.references(): List<String> = when (this) {
    is ColumnRef -> listOf(id.asString())
    is BinaryExpr -> left.references() + right.references()
    is Path -> cell.references()
    is Cast -> expr.references()
    is Call -> args.view.flatMap { it.references() }
    else -> emptyList()
}

/** pg naming: the alias, else a column's last segment, else the function name, else `?column?`. */
fun displayName(item: Column): String = item.alias?.asString() ?: when (val e = item.expr) {
    is ColumnRef -> e.id.asString().substringAfterLast('.')
    is Call -> e.name
    else -> "?column?"
}

/**
 * An ORDER BY item: a 1-based select ordinal, a select alias, a select expression, or a column. Any other
 * expression (`ORDER BY embedding <=> '[1,0,0]'`) becomes a hidden derived column through [derive].
 */
fun orderSource(expr: Expr, items: List<Column>, sources: List<Twin<String>>, derive: (Expr) -> String): String {
    val lit = (expr as? LitExpr)?.lit
    if (lit is NumericLiteral) {
        val k = lit.value.toInt()
        require(k in 1..sources.size) { "ORDER BY position $k is not in select list" }
        return sources[k - 1].a
    }
    val text = expr.render()
    items.indexOfFirst { it.alias?.asString() == text || it.expr.render() == text }.takeIf { it >= 0 }
        ?.let { return sources[it].a }
    return if (expr is ColumnRef) expr.id.asString() else derive(expr)
}
