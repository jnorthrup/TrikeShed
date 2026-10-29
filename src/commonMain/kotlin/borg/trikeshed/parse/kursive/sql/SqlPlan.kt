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
    is Quantified -> left.aggregated() || right.aggregated()
    else -> false
}

/** Rename every column of a scan to `qualifier.name`, so joined tables keep distinct names; a ColumnCursor keeps its columns at any row count. */
fun Cursor.qualify(qualifier: String): Cursor {
    val exemplar: Series<ColumnMeta> = when {
        this is ColumnCursor -> metas.toSeries()
        size > 0 -> this[0].metas α { it() }
        else -> return this
    }
    val metas = Array(exemplar.size) { c -> exemplar[c].let { meta -> meta.renamed("$qualifier.${meta.name}") } }
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
    if (union.size > 0) return planUnion(resolve, ops, compiler, provenance, prelude, statement)
    val table = from
    // A FROM-less SELECT (`SELECT 1`, `SELECT version()`) reads the one-row, no-column relation, as pg's Result node does.
    val (source, itemPlan) = if (table == null) UNIT j emptySeriesOf<QueryK<Cursor>>() else fromItem(table, resolve)
    val stages = mutableListOf<QueryK<Cursor>>()
    // a table function's stages come first: they make the relation the rest of the statement reads, and the receipt lists them
    stages += itemPlan.view
    stages += prelude

    for (clause in joins.view) {
        val qualifier = (clause.table.alias ?: clause.table.name).name
        val (rightScan, rightPlan) = fromItem(clause.table, resolve)
        val right = if (rightPlan.size == 0) rightScan else QueryCursor(rightScan, rightPlan, ops, compiler)
        // CROSS JOIN and the comma form: the Cartesian product, a widen on no keys
        if (clause.on == null) { stages += QueryK.Widen(right, emptySeriesOf(), false); continue }
        val pairs = mutableListOf<Twin<String>>()
        val rightOnly = mutableListOf<Expr>()
        val residual = mutableListOf<Expr>()
        for (c in conjuncts(clause.on)) {
            val l = ((c as? BinaryExpr)?.left as? ColumnRef)?.id?.name
            val r = ((c as? BinaryExpr)?.right as? ColumnRef)?.id?.name
            var any = false
            var all = true
            c.forEachReference { any = true; if (!it.qualifiedBy(qualifier)) all = false }
            when {
                c is BinaryExpr && c.op == "=" && l != null && r != null && r.qualifiedBy(qualifier) != l.qualifiedBy(qualifier) ->
                    pairs += if (r.qualifiedBy(qualifier)) l.asString() j r.asString() else r.asString() j l.asString()
                any && all -> rightOnly += c
                !clause.outer -> residual += c
                else -> error("LEFT JOIN ${qualifier.asString()} ON ${c.render()}: only column equalities and ${qualifier.asString()}-only predicates")
            }
        }
        require(pairs.isNotEmpty()) { "JOIN ${qualifier.asString()} needs ON column equalities" }
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
            is Call -> if (e.name == EVOKE_QUERY) markers.getOrPut(e.render()) {
                val (index, query, k) = evokeArgs(e.args)
                "#e${markers.size}".also { stages += QueryK.LexicalTopK(index, query, it, k) }
            } else e.args.view.forEach(::mark)
            is BinaryExpr -> { mark(e.left); mark(e.right) }
            is Cast -> mark(e.expr)
            is Path -> mark(e.cell)
            is Not -> mark(e.expr)
            is Is -> mark(e.expr)
            is InList -> { mark(e.expr); e.items.view.forEach(::mark) }
            is Quantified -> { mark(e.left); mark(e.right) }
            is ColumnRef, is LitExpr, Star, is Subselect -> Unit
        }
    }
    columns.view.forEach { mark(it.expr) }
    orderBy.view.forEach { mark(it.expr) }
    // Each distinct subselect becomes one hidden column `#sN` through a Subquery stage placed where the rewrite meets
    // it: before WHERE's Filter for a predicate, after it (fewer rows) for the select list and ORDER BY.
    val subqueries = LinkedHashMap<String, String>()
    val scopes = scopes()
    fun subquery(e: Subselect): String = subqueries.getOrPut(e.render()) {
        "#s${subqueries.size}".also { name -> stages += QueryK.Subquery(name, e.array, e.select.subqueryRows(resolve, ops, compiler, scopes)) }
    }
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
        is Quantified -> Quantified(lexical(e.left), e.op, lexical(e.right), e.all)
        is Subselect -> ColumnRef(Identifier(subquery(e).toSeries()))
        is ColumnRef, is LitExpr, Star -> e
    }
    where?.let { stages += QueryK.Filter(lexical(it)) }

    val items = columns
    val star = items.size == 1 && items[0].isStar()
    /** Per select item: the pre-projection source column and its display name. */
    val sources = ArrayList<Twin<String>>()
    var derived = 0
    fun derive(expr: Expr): String {
        val name = "#${derived++}"
        stages += QueryK.Derive(name, expr)
        return name
    }

    var residual = having
    if (groupBy.size > 0 || items.view.any { it.expr.aggregated() } || residual?.aggregated() == true) {
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
            e is Call -> Call(e.name, e.args.view.map { lift(it) }.toSeries())
            e is BinaryExpr -> BinaryExpr(lift(e.left), e.op, lift(e.right))
            e is Cast -> Cast(lift(e.expr), e.type)
            e is Path -> Path(lift(e.cell), e.path, e.text)
            e is Not -> Not(lift(e.expr))
            e is Is -> Is(lift(e.expr), e.value, e.negated)
            e is InList -> InList(lift(e.expr), e.items.view.map { lift(it) }.toSeries(), e.negated)
            else -> e
        }
        val lifted = items.view.map { lift(lexical(it.expr)) }
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
    val tables = listOfNotNull(table?.scanName()) + joins.view.mapNotNull { it.table.scanName() }
    return QueryCursor(source, stages.toSeries(), ops, compiler, statement ?: render(), tables.associateWith(provenance))
}

/**
 * `UNION [ALL]`: the head's rows then each arm's ([QueryK.Combine]), distinct unless ALL (a grouping on every output
 * column), then the union's ORDER BY, LIMIT and OFFSET, which name the head's output columns or their positions.
 */
fun SelectStmt.planUnion(
    resolve: (String) -> Cursor,
    ops: ColumnOperations,
    compiler: ExprCompiler,
    provenance: (String) -> borg.trikeshed.job.ContentId?,
    prelude: List<QueryK<Cursor>>,
    statement: String?,
): QueryCursor {
    val head = copy(orderBy = emptySeriesOf(), limit = null, offset = null, union = emptySeriesOf()).plan(resolve, ops, compiler, provenance, prelude)
    val arms = union.view.map { it.b.plan(resolve, ops, compiler, provenance) }
    val star = columns.size == 1 && columns[0].isStar()
    // the head's output names: its display names, or for `SELECT *` the columns the head answers
    val outputs: List<String> by lazy { if (star) head.names.view.map { it.toString() } else columns.view.map(::displayName) }
    val stages = mutableListOf<QueryK<Cursor>>(QueryK.Combine(arms.toSeries()))
    if (union.view.any { !it.a }) stages += QueryK.Aggregate(outputs.toSeries(), emptySeriesOf())
    if (orderBy.size > 0 || limit != null || offset != null) {
        val axis = orderBy.view.map { o -> unionOrderSource(o.expr, outputs) }
        stages += QueryK.Order(
            axis.toSeries(),
            BooleanArray(orderBy.size) { orderBy[it].descending },
            limit ?: -1,
            offset ?: 0,
            BooleanArray(orderBy.size) { orderBy[it].nullsFirst ?: orderBy[it].descending },
        )
    }
    val tables = (listOf(this) + union.view.map { it.b }).flatMap { s -> listOfNotNull(s.from?.scanName()) + s.joins.view.mapNotNull { it.table.scanName() } }
    return QueryCursor(head, stages.toSeries(), ops, compiler, statement ?: render(), tables.associateWith(provenance))
}

/** A union's ORDER BY item: a 1-based output position or an output column's name; pg allows nothing else. */
fun unionOrderSource(expr: Expr, outputs: List<String>): String {
    val lit = (expr as? LitExpr)?.lit
    if (lit is NumericLiteral) {
        val k = lit.value.toInt()
        require(k in 1..outputs.size) { "ORDER BY position $k is not in select list" }
        return outputs[k - 1]
    }
    val text = expr.render()
    require(text in outputs) { "ORDER BY on a UNION names an output column or its position: $text" }
    return text
}

/** [f] applied to every node of this expression after its children, bottom-up; a [Subselect] is one node, its statement is not descended. */
fun Expr.transform(f: (Expr) -> Expr): Expr = f(
    when (this) {
        is BinaryExpr -> BinaryExpr(left.transform(f), op, right.transform(f))
        is Call -> Call(name, args.view.map { it.transform(f) }.toSeries())
        is Cast -> Cast(expr.transform(f), type)
        is Path -> Path(cell.transform(f), path, text)
        is Not -> Not(expr.transform(f))
        is Is -> Is(expr.transform(f), value, negated)
        is InList -> InList(expr.transform(f), items.view.map { it.transform(f) }.toSeries(), negated)
        is Quantified -> Quantified(left.transform(f), op, right.transform(f), all)
        is ColumnRef, is LitExpr, Star, is Subselect -> this
    },
)

/** The FROM items' names as references qualify them: the alias, else the table's name; windows of the identifiers, not copies. */
fun SelectStmt.scopes(): List<Series<Char>> =
    listOfNotNull(from?.let { (it.alias ?: it.name).name }) + joins.view.map { (it.table.alias ?: it.table.name).name }

/** The index of the last '.' in these chars, -1 when there is none. */
private fun Series<Char>.lastDot(): Int {
    var i = size
    while (--i >= 0) if (this[i] == '.') break
    return i
}

/** The window before the last '.': a reference's qualifier, empty when unqualified. */
private fun Series<Char>.qualifier(): Series<Char> = slice(0, lastDot().coerceAtLeast(0))

private fun Series<Char>.sameChars(o: Series<Char>): Boolean = size == o.size && (0 until size).all { this[it] == o[it] }

/** Whether these chars read `q.` then a name, compared in place. */
private fun Series<Char>.qualifiedBy(q: Series<Char>): Boolean = size > q.size && this[q.size] == '.' && (0 until q.size).all { this[it] == q[it] }

private fun Series<Char>.equalsIgnoreCase(s: String): Boolean = size == s.length && (0 until size).all { this[it].equals(s[it], ignoreCase = true) }

/** A select item that is the literal `*`. */
private fun Column.isStar(): Boolean = (expr as? LitExpr)?.lit.let { it is StringLiteral && it.value.size == 1 && it.value[0] == '*' }

/** Every expression of the statement: select list, WHERE, HAVING, FROM item arguments, ON, GROUP BY, ORDER BY, and the union arms'. */
fun SelectStmt.expressions(): List<Expr> =
    columns.view.map { it.expr } + listOfNotNull(where, having) + (from?.args?.view?.toList() ?: emptyList()) +
        joins.view.flatMap { listOfNotNull(it.on) + (it.table.args?.view?.toList() ?: emptyList()) } + groupBy.view.toList() +
        orderBy.view.map { it.expr } + union.view.flatMap { it.b.expressions() }

/** Whether a reference qualified by one of [outer]'s names and none of [inner]'s occurs anywhere, nested subselects included. */
fun SelectStmt.correlated(outer: List<Series<Char>>, inner: List<Series<Char>> = scopes()): Boolean {
    var found = false
    for (e in expressions()) e.transform { n ->
        when (n) {
            is ColumnRef -> n.id.name.qualifier().let { q -> if (q.size > 0 && inner.none { it.sameChars(q) } && outer.any { it.sameChars(q) }) found = true }
            is Subselect -> if (n.select.correlated(outer, inner + n.select.scopes())) found = true
            else -> Unit
        }
        n
    }
    return found
}

/** The statement with every reference to one of [row]'s columns, qualified by a name outside [inner], replaced by that cell. */
fun SelectStmt.bind(row: RowVec, inner: List<Series<Char>> = scopes()): SelectStmt {
    val metas = row.metas
    fun cell(ref: Series<Char>): Expr? {
        val q = ref.qualifier()
        if (inner.any { it.sameChars(q) }) return null
        val c = (0 until metas.size).firstOrNull { m -> metas[m]().name.let { n -> n.length == ref.size && (0 until n.length).all { i -> n[i] == ref[i] } } } ?: return null
        return LitExpr(CellLiteral(row.values[c]))
    }
    fun g(e: Expr): Expr = e.transform { n ->
        when (n) {
            is ColumnRef -> cell(n.id.name) ?: n
            is Subselect -> Subselect(n.select.bind(row, inner + n.select.scopes()), n.array)
            else -> n
        }
    }
    fun item(t: TableRef) = TableRef(t.name, t.alias, t.args?.let { a -> a.view.map(::g).toSeries() })
    return copy(
        columns = columns.view.map { Column(g(it.expr), it.alias) }.toSeries(),
        from = from?.let(::item),
        where = where?.let(::g),
        joins = joins.view.map { JoinClause(item(it.table), it.on?.let(::g), it.outer) }.toSeries(),
        groupBy = groupBy.view.map(::g).toSeries(),
        having = having?.let(::g),
        orderBy = orderBy.view.map { OrderItem(g(it.expr), it.descending, it.nullsFirst) }.toSeries(),
        union = union.view.map { it.a j it.b.bind(row, it.b.scopes()) }.toSeries(),
    )
}

/**
 * A subselect's rows for one outer row. Uncorrelated: planned once and shared by every row. Correlated (a reference
 * qualified by one of the [outer] FROM items): that row's cells bound as literals, then planned, on the closure
 * compiler (a class per row would cost more than it saves): O(outer × inner), the catalog's sizes.
 */
fun SelectStmt.subqueryRows(resolve: (String) -> Cursor, ops: ColumnOperations, compiler: ExprCompiler, outer: List<Series<Char>>): (RowVec) -> Cursor {
    if (!correlated(outer)) {
        val once by lazy { plan(resolve, ops, compiler) }
        return { once }
    }
    return { row -> bind(row).plan(resolve, ops, ClosureExprCompiler) }
}

/** Evoke's marker and the FROM-item functions: `evoke_query(index, query, k)` and pg's `unnest(array)`. */
const val EVOKE_QUERY = "evoke_query"
const val UNNEST = "unnest"

/** `evoke_query(index, query[, k])`'s constant arguments, the one reading for the marker and the FROM form; k is -1 when absent. */
fun evokeArgs(args: Series<Expr>): Triple<String, String, Int> {
    require(args.size in 2..3) { "$EVOKE_QUERY(index, query[, k]) takes two or three arguments" }
    val index = constantText(args[0]) ?: error("$EVOKE_QUERY: the index must be a constant")
    val query = constantText(args[1]) ?: error("$EVOKE_QUERY: the query must be a constant")
    val k = if (args.size == 3) (constant(args[2]) as? Number)?.toInt() ?: error("$EVOKE_QUERY: k must be a number") else -1
    return Triple(index, query, k)
}

/**
 * `evoke_query`'s index text as (table, column): a leading `public.` names the one schema, as
 * [Catalog.table] reads it; the column is empty when the index names only the table.
 */
fun evokeIndex(index: String): Twin<String> {
    var from = 0
    if (index.startsWith("public.")) from = 7
    if (index.startsWith("PUBLIC.", from)) from += 7
    val dot = index.indexOf('.', from)
    return if (dot < 0) index.substring(from) j "" else index.substring(from, dot) j index.substring(dot + 1)
}

/** The table a FROM item scans: its own name, or the index's table of `evoke_query(index, …)`; `unnest` scans none. */
fun TableRef.scanName(): String? = when {
    args == null -> name.asString()
    function().equalsIgnoreCase(UNNEST) -> null
    else -> constantText(args[0])?.let { evokeIndex(it).a }
}

/** The name of a table function with pg's `pg_catalog.` schema dropped, as a window of the identifier. */
private fun TableRef.function(): Series<Char> {
    val n = name.name
    val schema = SqlParser.PG_CATALOG
    return if (n.size >= schema.length && (0 until schema.length).all { n[it].equals(schema[it], ignoreCase = true) }) n.drop(schema.length) else n
}

/**
 * A FROM item as the scan qualified by its alias and the stages that make the item's relation: none
 * for a table; for the table function `evoke_query(index, query, k)`, Evoke's explicit-hit form, the
 * same [QueryK.LexicalTopK] stage the select-list marker uses and a projection to `(id, score)`, the
 * best [k] documents first. `index` is a table or `table.column` (the first text column that is not
 * `id` when unnamed) and the qualifier is the alias or, as pg names an unaliased function's columns,
 * the function; a `JOIN t ON t.id = h.id` reads the rows back. An empty table answers itself: no hits,
 * and no row to type its columns by.
 */
fun fromItem(ref: TableRef, resolve: (String) -> Cursor): Join<Cursor, QueryPlan> {
    val qualifier = (ref.alias ?: ref.name).asString()
    val args = ref.args ?: return resolve(ref.name.asString()).qualify(qualifier) j emptySeriesOf<QueryK<Cursor>>()
    val function = ref.function()
    if (function.equalsIgnoreCase(UNNEST)) {
        // pg's `unnest(array) [AS] alias`: one row per element in the column the alias names; the array is a literal or an
        // outer row's cell the planner bound, since no expression is evaluated while planning
        require(args.size == 1) { "$UNNEST(array) in FROM takes one argument" }
        val arg = args[0]
        val cell = if (arg is LitExpr || arg is Cast && arg.expr is LitExpr) constant(arg) else error("$UNNEST in FROM takes an array value: a literal or an outer row's column")
        val column = ref.alias?.asString() ?: UNNEST
        val elements = cell?.let(SqlFunctions::elementsOf) ?: emptyList()
        return rowsCursor(listOf(column), elements.map { mapOf(column to it) }).qualify(qualifier) j emptySeriesOf<QueryK<Cursor>>()
    }
    if (!function.equalsIgnoreCase(EVOKE_QUERY)) error("function ${function.asString().lowercase()}(...) does not exist as a table function")
    require(args.size == 3) { "$EVOKE_QUERY(index, query, k) in FROM takes three arguments; the two-argument form is a scalar" }
    val (index, query, k) = evokeArgs(args)
    require(k > 0) { "$EVOKE_QUERY: k must be positive" }
    val (tableName, named) = evokeIndex(index)
    val raw = resolve(tableName)
    if (raw.size == 0) return raw.qualify(qualifier) j emptySeriesOf<QueryK<Cursor>>()
    val column = named.ifEmpty { lexicalColumn(raw) ?: error("$EVOKE_QUERY: $tableName has no text column to index") }
    val hits: QueryPlan = s_[QueryK.LexicalTopK("$qualifier.$column", query, "#f", k), QueryK.Project(s_["$qualifier.id" j "$qualifier.id", "#f" j "$qualifier.score"])]
    return raw.qualify(qualifier) j hits
}

/** The column `evoke_query` indexes when the index names only a table: the first text column that is not `id`; [scan] has a row. */
fun lexicalColumn(scan: Cursor): String? {
    val metas: Series<ColumnMeta> = if (scan is ColumnCursor) scan.metas.toSeries() else scan[0].metas α { it() }
    return metas.view.firstOrNull { it.type == IOMemento.IoString && !(it.name.length == 2 && it.name[0] == 'i' && it.name[1] == 'd') }?.name?.toString()
}

/** The one-row relation with no columns: what a SELECT without FROM scans. */
val UNIT: Cursor = 1 j { _: Int -> ReifiedSplitSeries2(emptySeriesOf<Any?>(), emptySeriesOf<`ColumnMeta↻`>()) }

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
    e is BinaryExpr && e.op.equals("AND", ignoreCase = true) -> conjuncts(e.left) + conjuncts(e.right)
    else -> listOf(e)
}

/** [f] applied to the name window of every column an expression reads. */
fun Expr.forEachReference(f: (Series<Char>) -> Unit) {
    when (this) {
        is ColumnRef -> f(id.name)
        is BinaryExpr -> { left.forEachReference(f); right.forEachReference(f) }
        is Path -> cell.forEachReference(f)
        is Cast -> expr.forEachReference(f)
        is Call -> args.view.forEach { it.forEachReference(f) }
        is Not -> expr.forEachReference(f)
        is Is -> expr.forEachReference(f)
        is InList -> { expr.forEachReference(f); items.view.forEach { it.forEachReference(f) } }
        is Quantified -> { left.forEachReference(f); right.forEachReference(f) }
        else -> Unit
    }
}

/** pg naming: the alias, else a column's last segment, else the function name, a scalar subselect's one column, `array` for ARRAY(…), else `?column?`. */
fun displayName(item: Column): String = item.alias?.asString() ?: when (val e = item.expr) {
    is ColumnRef -> e.id.name.let { n -> n.slice(n.lastDot() + 1, n.size).asString() }
    is Call -> if (e.name == SqlParser.CASE_WHEN) SqlParser.CASE else e.name
    is Subselect -> if (e.array) "array" else e.select.columns.view.singleOrNull()?.let(::displayName) ?: "?column?"
    else -> "?column?"
}

/**
 * An ORDER BY item: a 1-based select ordinal, a select alias, a select expression, or a column. Any other
 * expression (`ORDER BY embedding <=> '[1,0,0]'`) becomes a hidden derived column through [derive].
 */
fun orderSource(expr: Expr, items: Series<Column>, sources: List<Twin<String>>, derive: (Expr) -> String): String {
    val lit = (expr as? LitExpr)?.lit
    if (lit is NumericLiteral) {
        val k = lit.value.toInt()
        require(k in 1..sources.size) { "ORDER BY position $k is not in select list" }
        return sources[k - 1].a
    }
    val text = expr.render()
    for (i in 0 until items.size) {
        val item = items[i]
        if (item.alias?.name?.matches(text) == true || item.expr.render() == text) return sources[i].a
    }
    return if (expr is ColumnRef) expr.id.asString() else derive(expr)
}
