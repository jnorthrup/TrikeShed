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
    is Not -> expr.aggregated()
    is Is -> expr.aggregated()
    is InList -> expr.aggregated() || items.view.any { it.aggregated() }
    else -> false
}

/** Rename every column of a scan to `qualifier.name`, so joined tables keep distinct names. */
fun Cursor.qualify(qualifier: String): Cursor {
    if (size == 0) return this
    val exemplar = this[0].metas
    val metas = Array(exemplar.size) { c ->
        val meta = exemplar[c]()
        meta.renamed("$qualifier.${meta.name}")
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
    /** The content id of a source table's current content, when the catalog knows one; recorded on the receipt. */
    provenance: (String) -> borg.trikeshed.job.ContentId? = { null },
    /** Stages run on the source before this statement's own (a SPARQL front end's graph pattern). */
    prelude: List<QueryK<Cursor>> = emptyList(),
    /** The statement text the receipt hashes when this SELECT was lowered from another language; the rendered SQL otherwise. */
    statement: String? = null,
): QueryCursor {
    val table = requireNotNull(from) { "SELECT without FROM" }
    val source = resolve(table.name.asString()).qualify((table.alias ?: table.name).asString())
    val stages = mutableListOf<QueryK<Cursor>>()
    stages += prelude

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
    // Evoke's planner-native marker: each distinct `evoke_query(index, query[, k])` in the select list or
    // ORDER BY scores the scan once, before WHERE, with corpus statistics over the whole table, and keeps
    // only matching rows; the marker then reads as a column. The index names a text column of the scan.
    val markers = LinkedHashMap<String, String>()
    fun mark(e: Expr) {
        when (e) {
            is Call -> if (e.name == "evoke_query") markers.getOrPut(e.render()) {
                val args = e.args.view.toList()
                require(args.size in 2..3) { "evoke_query(index, query[, k]) takes two or three arguments" }
                val index = constantText(args[0]) ?: error("evoke_query: the index must be a constant")
                val query = constantText(args[1]) ?: error("evoke_query: the query must be a constant")
                val k = args.getOrNull(2)?.let { (constant(it) as? Number)?.toInt() ?: error("evoke_query: k must be a number") } ?: -1
                "#e${markers.size}".also { stages += QueryK.LexicalTopK(index, query, it, k) }
            } else e.args.view.forEach(::mark)
            is BinaryExpr -> { mark(e.left); mark(e.right) }
            is Cast -> mark(e.expr)
            is Path -> mark(e.cell)
            is Not -> mark(e.expr)
            is Is -> mark(e.expr)
            is InList -> { mark(e.expr); e.items.view.forEach(::mark) }
            is ColumnRef, is LitExpr, Star -> Unit
        }
    }
    columns.view.forEach { mark(it.expr) }
    orderBy.view.forEach { mark(it.expr) }
    /**
     * [e] with every marker read as its score column. Series are rebuilt through `map { }`, not
     * `α ::lexical`: a reference to a local function inside an inline lambda compiles to a class that
     * cannot reach the local function's private static form (IllegalAccessError at run time).
     */
    fun lexical(e: Expr): Expr = when (e) {
        is Call -> markers[e.render()]?.let { ColumnRef(Identifier(it.toSeries())) } ?: Call(e.name, e.args.view.map { lexical(it) }.toSeries())
        is BinaryExpr -> BinaryExpr(lexical(e.left), e.op, lexical(e.right))
        is Cast -> Cast(lexical(e.expr), e.type)
        is Path -> Path(lexical(e.cell), e.path, e.text)
        is Not -> Not(lexical(e.expr))
        is Is -> Is(lexical(e.expr), e.value, e.negated)
        is InList -> InList(lexical(e.expr), e.items.view.map { lexical(it) }.toSeries(), e.negated)
        is ColumnRef, is LitExpr, Star -> e
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
            e is Cast -> Cast(lift(e.expr), e.type)
            e is Path -> Path(lift(e.cell), e.path, e.text)
            e is Not -> Not(lift(e.expr))
            e is Is -> Is(lift(e.expr), e.value, e.negated)
            e is InList -> InList(lift(e.expr), e.items.view.map { lift(it) }.toSeries(), e.negated)
            else -> e
        }
        val lifted = items.map { lift(lexical(it.expr)) }
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
        val e = lexical(item.expr)
        sources += (if (e is ColumnRef) e.id.asString() else derive(e)) j displayName(item)
    }
    residual?.let { stages += QueryK.Filter(it) }

    if (orderBy.size > 0 || limit != null || offset != null) {
        val axis = orderBy.view.map { order -> orderSource(order.expr, items, sources) { derive(lexical(it)) } }
        stages += QueryK.Order(
            axis.toSeries(),
            BooleanArray(orderBy.size) { orderBy[it].descending },
            limit ?: -1,
            offset ?: 0,
            BooleanArray(orderBy.size) { orderBy[it].nullsFirst ?: orderBy[it].descending },
        )
    }
    stages += QueryK.Project(sources.toSeries())
    val tables = listOf(table.name.asString()) + joins.view.map { it.table.name.asString() }
    return QueryCursor(source, stages.toSeries(), ops, compiler, statement ?: render(), tables.associateWith(provenance))
}

/** A literal's value, through a cast of a literal (`'idx'::regclass`); null for anything else. */
fun constant(e: Expr): Any? = when (e) {
    is LitExpr -> literal(e.lit)
    is Cast -> if (e.expr is LitExpr) SqlRuntime.cast(literal(e.expr.lit), e.type) else null
    else -> null
}

fun constantText(e: Expr): String? = constant(e)?.let { scalarText(it) }

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
    is Not -> expr.references()
    is Is -> expr.references()
    is InList -> expr.references() + items.view.flatMap { it.references() }
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
