package borg.trikeshed.lcnc

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.JsPath
import borg.trikeshed.parse.confix.JsPathElement
import borg.trikeshed.parse.confix.idx
import borg.trikeshed.parse.confix.idxOf
import borg.trikeshed.parse.confix.key
import borg.trikeshed.parse.confix.keyOf
import borg.trikeshed.parse.confix.kids
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.src
import borg.trikeshed.parse.confix.tag
import borg.trikeshed.parse.kursive.sql.*

/**
 * A statement the graph cannot say, or a graph that does not say a statement. [nodeId] names the
 * offending node ("" for the statement as a whole); [code] is the machine-readable reason.
 */
class SqlGraphError(val nodeId: String, val code: String, detail: String) :
    IllegalArgumentException(code + (if (nodeId.isEmpty()) "" else " at $nodeId") + ": " + detail)

/**
 * SQL as an LCNC graph. The authoring representation of a SELECT is an [LcncProgram] of `sql.*`
 * nodes named after the IBM SQL Reference non-terminals (subselect, table-reference, joined-table,
 * search-condition, expression, ...); the Kotlin [SelectStmt] is the bound planner IR. [lift] and
 * [lower] are the two total halves of one codec, so a statement can be drawn, edited and planned
 * without either side keeping a second copy of the other.
 *
 * Port kinds are the IBM categories, so [LcncFacts.accepts] is the grammar's type system:
 * [QUERY] subselect, [TABLE] table-reference, [EXPR] expression, [COND] search-condition,
 * [ITEM] select-list item, [KEY] sort key. Expression and FROM trees are wires; a node's output
 * feeds the parent's named input. Where the AST lets a predicate stand for a value or the reverse
 * the two bridges `sql.asCondition` and `sql.asValue` are the only places the kinds meet.
 *
 * Order that carries meaning (select list, ORDER BY) lives in an `ord` param, never in geometry
 * or wire position; an absent `ord` sorts after every numbered one, so a freshly mated node
 * appends. GROUP BY and call arguments follow wire order, and a call with more than one argument is
 * refused until an ord-bearing argument wrapper exists.
 *
 * Lifted programs are `inspectionOnly`: there is no runner, the statement runs by lowering it.
 */
object SqlGraph {
    const val QUERY = "SqlQuery"
    const val TABLE = "SqlTable"
    const val EXPR = "SqlExpr"
    const val COND = "SqlCond"
    const val ITEM = "SqlItem"
    const val KEY = "SqlKey"

    const val SELECT = "sql.select"
    const val ITEM_EXPR = "sql.item"
    const val ITEM_STAR = "sql.itemStar"
    const val SORT_KEY = "sql.sortKey"
    const val TABLE_NAME = "sql.table"
    const val JOIN = "sql.join"
    const val COL = "sql.col"
    const val LIT = "sql.lit"
    const val CALL = "sql.call"
    const val PATH = "sql.path"
    const val CAST = "sql.cast"
    const val ARITH = "sql.arith"
    const val CMP = "sql.cmp"
    const val LIKE = "sql.like"
    const val LOGIC = "sql.logic"
    const val AS_CONDITION = "sql.asCondition"
    const val AS_VALUE = "sql.asValue"

    val TYPES: List<String> = listOf(
        SELECT, ITEM_EXPR, ITEM_STAR, SORT_KEY, TABLE_NAME, JOIN, COL, LIT, CALL, PATH, CAST, ARITH, CMP, LIKE, LOGIC,
        AS_CONDITION, AS_VALUE,
    )

    private val CMP_OPS = listOf("=", "<>", "!=", "<", "<=", ">", ">=")
    private val ARITH_OPS = listOf("+", "-", "*", "/", "%", "||", "<=>", "<->", "<#>")
    private val VECTOR_OPS = setOf("<=>", "<->", "<#>")
    private val LOGIC_OPS = listOf("AND", "OR")

    /** The output port of each node type: the name its parent's wire leaves from. */
    private val OUT: Map<String, String> = mapOf(
        SELECT to "query", ITEM_EXPR to "item", ITEM_STAR to "item", SORT_KEY to "sortKey", TABLE_NAME to "table",
        JOIN to "table", COL to "expr", LIT to "expr", CALL to "expr", PATH to "expr", CAST to "expr", ARITH to "expr",
        CMP to "cond", LIKE to "cond", LOGIC to "cond", AS_CONDITION to "cond", AS_VALUE to "expr",
    )

    // ── vocabulary ──────────────────────────────────────────────────────────────────────────────

    private fun param(v: String = "", opts: List<String> = emptyList(), optional: Boolean = false, ta: Boolean = false) =
        LcncPortContract.LcncParamSpec(v = v, opts = opts, optional = optional, ta = ta)

    private fun contract(
        type: String,
        inputs: List<String>,
        outputs: List<String>,
        inputKinds: Map<String, String>,
        outputKinds: Map<String, String>,
        params: Map<String, LcncPortContract.LcncParamSpec> = emptyMap(),
        many: Set<String> = emptySet(),
    ) = LcncPortContract(
        type = type, title = type, inputs = inputs, outputs = outputs,
        cardinality = many.associateWith { LcncCardinality.MANY },
        inputKinds = inputKinds, outputKinds = outputKinds, params = params,
    )

    /** The `sql.*` vocabulary, in IBM diagram order; every port's kind is an IBM category. */
    fun contracts(): List<LcncPortContract> = listOf(
        contract(SELECT, listOf("items", "from", "where?", "groupBy?", "having?", "orderBy?"), listOf("query"),
            mapOf("items" to ITEM, "from" to TABLE, "where" to COND, "groupBy" to EXPR, "having" to COND, "orderBy" to KEY),
            mapOf("query" to QUERY), mapOf("limit" to param(optional = true), "offset" to param(optional = true)),
            many = setOf("items", "groupBy", "orderBy")),
        contract(ITEM_EXPR, listOf("expr"), listOf("item"), mapOf("expr" to EXPR), mapOf("item" to ITEM),
            mapOf("alias" to param(optional = true), "ord" to param(optional = true))),
        contract(ITEM_STAR, emptyList(), listOf("item"), emptyMap(), mapOf("item" to ITEM), mapOf("ord" to param(optional = true))),
        contract(SORT_KEY, listOf("expr"), listOf("sortKey"), mapOf("expr" to EXPR), mapOf("sortKey" to KEY),
            mapOf("direction" to param("ASC", listOf("ASC", "DESC")), "ord" to param(optional = true))),
        contract(TABLE_NAME, emptyList(), listOf("table"), emptyMap(), mapOf("table" to TABLE),
            mapOf("name" to param(), "alias" to param(optional = true))),
        contract(JOIN, listOf("left", "right", "on?"), listOf("table"),
            mapOf("left" to TABLE, "right" to TABLE, "on" to COND), mapOf("table" to TABLE),
            mapOf("join" to param("INNER", listOf("INNER", "LEFT")))),
        contract(COL, emptyList(), listOf("expr"), emptyMap(), mapOf("expr" to EXPR), mapOf("name" to param())),
        contract(LIT, emptyList(), listOf("expr"), emptyMap(), mapOf("expr" to EXPR),
            mapOf("kind" to param("string", listOf("string", "int", "double")), "value" to param())),
        contract(CALL, listOf("args?"), listOf("expr"), mapOf("args" to EXPR), mapOf("expr" to EXPR),
            mapOf("name" to param(), "star" to param("false", listOf("false", "true"), optional = true)), many = setOf("args")),
        contract(PATH, listOf("cell"), listOf("expr"), mapOf("cell" to EXPR), mapOf("expr" to EXPR),
            mapOf("steps" to param("[]", ta = true), "text" to param("false", listOf("false", "true")))),
        contract(CAST, listOf("value"), listOf("expr"), mapOf("value" to EXPR), mapOf("expr" to EXPR), mapOf("type" to param())),
        contract(ARITH, listOf("left", "right"), listOf("expr"), mapOf("left" to EXPR, "right" to EXPR), mapOf("expr" to EXPR),
            mapOf("op" to param("+", ARITH_OPS))),
        contract(CMP, listOf("left", "right"), listOf("cond"), mapOf("left" to EXPR, "right" to EXPR), mapOf("cond" to COND),
            mapOf("op" to param("=", CMP_OPS))),
        contract(LIKE, listOf("match", "pattern"), listOf("cond"), mapOf("match" to EXPR, "pattern" to EXPR), mapOf("cond" to COND)),
        contract(LOGIC, listOf("left", "right"), listOf("cond"), mapOf("left" to COND, "right" to COND), mapOf("cond" to COND),
            mapOf("op" to param("AND", LOGIC_OPS))),
        contract(AS_CONDITION, listOf("value"), listOf("cond"), mapOf("value" to EXPR), mapOf("cond" to COND)),
        contract(AS_VALUE, listOf("cond"), listOf("expr"), mapOf("cond" to COND), mapOf("expr" to EXPR)),
    )

    // ── text -> graph ───────────────────────────────────────────────────────────────────────────

    /** Parse with the strict SELECT grammar (never the regex fallback) and lift. */
    fun parse(text: String, name: String = "sql"): LcncProgram {
        val stmt = runCatching { SqlParser.parse(text.toSeries()) }.getOrNull()
            ?: throw SqlGraphError("", "parse", "not a statement the strict SELECT grammar accepts")
        return lift(stmt, name)
    }

    /**
     * The graph of [stmt]. The parser's silent misreads that are visible in the AST (keywords read as
     * names, syntax read as calls, the `||` precedence, path steps the graph cannot carry) are refused here
     * rather than drawn; wrapped numbers and dropped clauses are not visible in the AST and wait for the
     * parser fix. A quoted keyword is refused too, since the AST does not record quoting.
     */
    fun lift(stmt: SelectStmt, name: String = "sql"): LcncProgram {
        guard(stmt)
        val lift = Lift()
        val root = lift.select(stmt)
        return LcncProgram(
            name = name,
            nodes = lift.placed(root).toSeries(),
            wires = lift.wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true),
            seq = lift.seq + 1,
        )
    }

    private class Lift {
        val nodes = ArrayList<LcncNode>()
        val wires = ArrayList<LcncWire>()
        private val typeOf = HashMap<String, String>()
        private val feeds = HashMap<String, MutableList<String>>()
        var seq = 0

        fun node(type: String, vararg params: Pair<String, String?>): String {
            val id = "n${++seq}"
            nodes += LcncNode(id, type, params.filter { it.second != null }.associate { it.first to it.second!! })
            typeOf[id] = type
            return id
        }

        /** [child]'s output into [parent]'s input [port]. */
        fun feed(child: String, parent: String, port: String) {
            wires += LcncWire(child, OUT.getValue(typeOf.getValue(child)), parent, port)
            feeds.getOrPut(parent) { ArrayList() } += child
        }

        fun select(stmt: SelectStmt): String {
            val from = stmt.from ?: throw SqlGraphError("", "no-from", "SELECT without FROM")
            val hub = node(SELECT, "limit" to stmt.limit?.toString(), "offset" to stmt.offset?.toString())
            val columns = (0 until stmt.columns.size).map { stmt.columns[it] }
            val star = columns.size == 1 && columns[0].alias == null && isStar(columns[0].expr)
            if (star) feed(node(ITEM_STAR, "ord" to "0"), hub, "items")
            else columns.forEachIndexed { i, column ->
                val item = node(ITEM_EXPR, "alias" to column.alias?.asString(), "ord" to "$i")
                feed(expr(column.expr, EXPR), item, "expr")
                feed(item, hub, "items")
            }
            var table = tableRef(from)
            for (i in 0 until stmt.joins.size) {
                val clause = stmt.joins[i]
                val join = node(JOIN, "join" to if (clause.outer) "LEFT" else "INNER")
                feed(table, join, "left")
                feed(tableRef(clause.table), join, "right")
                clause.on?.let { feed(expr(it, COND), join, "on") }
                table = join
            }
            feed(table, hub, "from")
            stmt.where?.let { feed(expr(it, COND), hub, "where") }
            for (i in 0 until stmt.groupBy.size) feed(expr(stmt.groupBy[i], EXPR), hub, "groupBy")
            stmt.having?.let { feed(expr(it, COND), hub, "having") }
            for (i in 0 until stmt.orderBy.size) {
                val order = stmt.orderBy[i]
                val key = node(SORT_KEY, "direction" to if (order.descending) "DESC" else "ASC", "ord" to "$i")
                feed(expr(order.expr, EXPR), key, "expr")
                feed(key, hub, "orderBy")
            }
            return hub
        }

        private fun tableRef(ref: TableRef): String =
            node(TABLE_NAME, "name" to ref.name.asString(), "alias" to ref.alias?.asString())

        /** The node for [e] in a slot that wants [want]; a bridge is added where the natural kind differs. */
        fun expr(e: Expr, want: String): String {
            val have = if (e is BinaryExpr && e.op.uppercase() in CONDITION_OPS) COND else EXPR
            val id = build(e)
            if (have == want) return id
            val bridge = node(if (want == COND) AS_CONDITION else AS_VALUE)
            feed(id, bridge, if (want == COND) "value" else "cond")
            return bridge
        }

        private fun build(e: Expr): String = when (e) {
            is ColumnRef -> node(COL, "name" to e.id.asString())
            is LitExpr -> when (val lit = e.lit) {
                is StringLiteral -> node(LIT, "kind" to "string", "value" to lit.asString())
                is NumericLiteral -> node(LIT, "kind" to if (isIntegral(lit.value)) "int" else "double", "value" to lit.value.toString())
                else -> throw SqlGraphError("", "unsupported-in-ast", "literal ${lit::class.simpleName}")
            }
            is BinaryExpr -> binary(e)
            is Call -> call(e)
            Star -> throw SqlGraphError("", "unsupported-in-ast", "* outside COUNT(*)")
            is Cast -> node(CAST, "type" to e.type).also { feed(expr(e.expr, EXPR), it, "value") }
            is Path -> node(PATH, "steps" to stepsJson(e.path), "text" to e.text.toString()).also { feed(expr(e.cell, EXPR), it, "cell") }
        }

        private fun binary(e: BinaryExpr): String {
            val op = e.op.uppercase()
            return when {
                op in LOGIC_OPS -> node(LOGIC, "op" to op).also { feed(expr(e.left, COND), it, "left"); feed(expr(e.right, COND), it, "right") }
                op == "LIKE" -> node(LIKE).also { feed(expr(e.left, EXPR), it, "match"); feed(expr(e.right, EXPR), it, "pattern") }
                e.op in CMP_OPS -> node(CMP, "op" to e.op).also { feed(expr(e.left, EXPR), it, "left"); feed(expr(e.right, EXPR), it, "right") }
                e.op in ARITH_OPS -> node(ARITH, "op" to e.op).also { feed(expr(e.left, EXPR), it, "left"); feed(expr(e.right, EXPR), it, "right") }
                else -> throw SqlGraphError("", "unsupported-in-ast", "operator ${e.op}")
            }
        }

        private fun call(e: Call): String {
            val args = (0 until e.args.size).map { e.args[it] }
            if (args.size == 1 && args[0] === Star) return node(CALL, "name" to e.name, "star" to "true")
            if (args.size > 1) throw SqlGraphError("", "unsupported-in-ast", "${e.name}() with ${args.size} arguments: call arguments carry no order of their own")
            val call = node(CALL, "name" to e.name)
            args.forEach { feed(expr(it, EXPR), call, "args") }
            return call
        }

        /** Tree layout: the statement on the right, its operands to the left, one row per leaf. */
        fun placed(root: String): List<LcncNode> {
            val depth = HashMap<String, Int>()
            val row = HashMap<String, Double>()
            var nextRow = 0
            fun visit(id: String, d: Int) {
                depth[id] = d
                val below = feeds[id].orEmpty()
                if (below.isEmpty()) row[id] = (nextRow++).toDouble()
                else { below.forEach { visit(it, d + 1) }; row[id] = below.sumOf { row.getValue(it) } / below.size }
            }
            visit(root, 0)
            val deepest = depth.values.max()
            return nodes.map { it.copy(x = (deepest - depth.getValue(it.id)) * 240.0, y = row.getValue(it.id) * 96.0) }
        }
    }

    private val CONDITION_OPS: Set<String> = (CMP_OPS + LOGIC_OPS + "LIKE").toSet()

    /** The sql.* contracts by type, and the whole vocabulary the type check reads, built once. */
    private val CONTRACTS: Map<String, LcncPortContract> by lazy { contracts().associateBy { it.type } }
    private val VOCABULARY: Map<String, LcncPortContract> by lazy { LcncContracts.all().associateBy { it.type } }

    /** Deeper nesting than any statement a person writes; the recursive lowering stops here instead of overflowing. */
    const val MAX_DEPTH = 1_000

    private fun isStar(e: Expr): Boolean = e is LitExpr && (e.lit as? StringLiteral)?.asString() == "*"
    private fun isIntegral(n: Number): Boolean = n is Int || n is Long || n is Short || n is Byte

    // ── what the strict parser silently misreads ───────────────────────────────────────────────

    private val KEYWORDS: Set<String> = SqlParser.RESERVED + setOf(
        "ALL", "NOT", "NULL", "TRUE", "FALSE", "IS", "IN", "BETWEEN", "CASE", "EXISTS", "NATURAL", "USING", "PRECISION",
        "VARYING", "WHEN", "THEN", "ELSE", "END", "INTERSECT", "EXCEPT", "WITH",
    )
    private val SYNTAX_CALLS = setOf("not", "exists", "in", "cast", "case", "between")

    private fun guard(stmt: SelectStmt) {
        fun identifier(name: String, what: String) {
            if (name.split('.').any { it.isEmpty() }) throw SqlGraphError("", "bad-identifier", "$what '$name' starts or ends with '.'")
            if ('.' !in name && name.uppercase() in KEYWORDS) throw SqlGraphError("", "reserved-word", "$what '$name' is an SQL keyword the strict parser would read as a name")
        }
        fun visit(e: Expr) {
            when (e) {
                is ColumnRef -> identifier(e.id.asString(), "column")
                is BinaryExpr -> {
                    visit(e.left); visit(e.right)
                    val l = e.left as? BinaryExpr
                    val r = e.right as? BinaryExpr
                    // `a || b + c` and `(a || b) + c` parse alike, but pg reads the first as `a || (b + c)`;
                    // likewise `a <=> b || c`. The AST drops parentheses, so the shape is refused until
                    // dialect precedence tables exist.
                    if (e.op in setOf("+", "-") && l != null && l.op == "||")
                        throw SqlGraphError("", "ambiguous-precedence", "`||` under `${e.op}`: split the expression")
                    if (e.op in VECTOR_OPS && r != null && r.op == "||")
                        throw SqlGraphError("", "ambiguous-precedence", "`||` under `${e.op}`: split the expression")
                }
                is Call -> {
                    if (e.name in SYNTAX_CALLS) throw SqlGraphError("", "reserved-word", "${e.name}(...) is SQL syntax the strict parser reads as a function call")
                    for (i in 0 until e.args.size) visit(e.args[i])
                }
                is Cast -> visit(e.expr)
                is Path -> {
                    visit(e.cell)
                    if (e.path.size == 0) throw SqlGraphError("", "unsupported-in-ast", "an empty path")
                    for (i in 0 until e.path.size) {
                        val key = e.path[i].key
                        val idx = e.path[i].idx
                        if (key.isNullOrEmpty() && (idx == null || idx < 0))
                            throw SqlGraphError("", "unsupported-in-ast", "path step $i is neither a key nor a non-negative index")
                    }
                }
                is LitExpr, Star -> Unit
            }
        }
        fun table(ref: TableRef) {
            identifier(ref.name.asString(), "table")
            ref.alias?.let { identifier(it.asString(), "alias") }
        }
        stmt.from?.let(::table)
        for (i in 0 until stmt.columns.size) {
            stmt.columns[i].alias?.let { identifier(it.asString(), "alias") }
            visit(stmt.columns[i].expr)
        }
        for (i in 0 until stmt.joins.size) { table(stmt.joins[i].table); stmt.joins[i].on?.let(::visit) }
        stmt.where?.let(::visit)
        for (i in 0 until stmt.groupBy.size) visit(stmt.groupBy[i])
        stmt.having?.let(::visit)
        for (i in 0 until stmt.orderBy.size) visit(stmt.orderBy[i].expr)
        for ((what, v) in listOf("LIMIT" to stmt.limit, "OFFSET" to stmt.offset)) if (v != null && v < 0)
            throw SqlGraphError("", "bad-param", "$what $v is negative")
    }

    // ── graph -> statement ──────────────────────────────────────────────────────────────────────

    /** The statement [program] draws, or a [SqlGraphError] naming the node that does not say one. */
    fun lower(program: LcncProgram): SelectStmt = Lower(program).statement()

    private class Lower(val program: LcncProgram) {
        private val byId = LinkedHashMap<String, LcncNode>()
        private val into = HashMap<String, MutableList<Pair<Int, LcncWire>>>()
        private val reached = HashSet<String>()
        private val visiting = HashSet<String>()
        /** sql.* node id → its wires into other sql.* nodes; a syntax tree feeds each node to one consumer. */
        private val consumers = HashMap<String, Int>()

        init {
            for (i in 0 until program.nodes.size) {
                val n = program.nodes[i]
                if (n.children.size > 0) throw SqlGraphError(n.id, "unsupported-in-ast", "rings (sub-queries) are not part of this vocabulary yet")
                if (n.subprogram != null) throw SqlGraphError(n.id, "unsupported-in-ast", "named rings are not part of this vocabulary yet")
                byId[n.id] = n
            }
            for (i in 0 until program.wires.size) {
                val w = program.wires[i]
                into.getOrPut(w.toNode + "\u0000" + w.toPort.removeSuffix("?")) { ArrayList() } += i to w
                if (isSql(w.fromNode) && isSql(w.toNode)) consumers[w.fromNode] = (consumers[w.fromNode] ?: 0) + 1
            }
        }

        /** Only sql.* nodes say the statement; anything else on the canvas (a display, a note) is presentation. */
        private fun isSql(id: String): Boolean = byId[id]?.type?.startsWith("sql.") == true

        fun statement(): SelectStmt {
            LcncTypeCheck.check(program, VOCABULARY, strict = true).firstOrNull()?.let {
                throw SqlGraphError(it.toNode, "type-check", it.render())
            }
            consumers.entries.firstOrNull { it.value > 1 }?.let {
                throw SqlGraphError(it.key, "shared-node", "${byId.getValue(it.key).type} feeds ${it.value} inputs; a syntax tree feeds one")
            }
            val roots = byId.values.filter { it.type == SELECT && it.id !in consumers }
            if (roots.isEmpty()) throw SqlGraphError("", "no-root", "no sql.select without a consumer")
            if (roots.size > 1) throw SqlGraphError(roots[1].id, "multiple-roots", "${roots.size} statements; a graph says one")
            val stmt = select(roots[0])
            byId.values.firstOrNull { it.type.startsWith("sql.") && it.id !in reached }
                ?.let { throw SqlGraphError(it.id, "orphan-node", "${it.type} is not reachable from the statement") }
            return stmt
        }

        private fun node(id: String): LcncNode {
            val n = byId.getValue(id)
            if (!visiting.add(id)) throw SqlGraphError(id, "cycle", "${n.type} feeds itself")
            if (visiting.size > MAX_DEPTH) throw SqlGraphError(id, "depth", "nesting deeper than $MAX_DEPTH")
            reached += id
            return n
        }

        private fun done(id: String) { visiting.remove(id) }

        private fun wiresInto(n: LcncNode, port: String): List<Pair<Int, LcncWire>> = into[n.id + "\u0000" + port].orEmpty()

        private fun one(n: LcncNode, port: String): String {
            val ws = wiresInto(n, port)
            if (ws.isEmpty()) throw SqlGraphError(n.id, "missing-input", "${n.type}.$port is unwired")
            if (ws.size > 1) throw SqlGraphError(n.id, "multiple-inputs", "${n.type}.$port has ${ws.size} wires")
            return ws[0].second.fromNode
        }

        private fun optional(n: LcncNode, port: String): String? =
            if (wiresInto(n, port).isEmpty()) null else one(n, port)

        /** Sources into a MANY port, by wire order. */
        private fun many(n: LcncNode, port: String): List<String> = wiresInto(n, port).map { it.second.fromNode }

        /**
         * Sources into a MANY port ordered by their `ord` param, an absent one after every numbered one, then
         * wire order. A present `ord` must be an integer and unique among the port's sources.
         */
        private fun ordered(n: LcncNode, port: String): List<String> {
            val sources = wiresInto(n, port)
            val seen = HashSet<Int>()
            val ords = sources.map { (_, w) ->
                val source = byId.getValue(w.fromNode)
                val text = source.params["ord"]?.trim().orEmpty()
                if (text.isEmpty()) Int.MAX_VALUE else {
                    val ord = text.toIntOrNull() ?: throw SqlGraphError(source.id, "bad-param", "ord '$text' is not an integer")
                    if (!seen.add(ord)) throw SqlGraphError(source.id, "bad-param", "ord $ord repeats in ${n.type}.$port")
                    ord
                }
            }
            return sources.indices.sortedWith(compareBy({ ords[it] }, { sources[it].first })).map { sources[it].second.fromNode }
        }

        private fun text(n: LcncNode, key: String): String = n.params[key].orEmpty()

        /** A picklist param: blank is the contract default, anything else must be one of its options. */
        private fun choice(n: LcncNode, key: String): String {
            val spec = CONTRACTS.getValue(n.type).params.getValue(key)
            val value = text(n, key).trim()
            if (value.isEmpty()) return spec.v
            return spec.opts.firstOrNull { it.equals(value, ignoreCase = true) }
                ?: throw SqlGraphError(n.id, "bad-param", "${n.type}.$key '$value' is not one of ${spec.opts}")
        }

        private fun required(n: LcncNode, key: String): String =
            text(n, key).takeIf { it.isNotBlank() } ?: throw SqlGraphError(n.id, "bad-param", "${n.type} needs $key")

        private fun nonNegative(n: LcncNode, key: String): Int? {
            val t = text(n, key).trim()
            if (t.isEmpty()) return null
            val v = t.toIntOrNull() ?: throw SqlGraphError(n.id, "bad-param", "$key '$t' is not an integer")
            if (v < 0) throw SqlGraphError(n.id, "bad-param", "$key $v is negative")
            return v
        }

        fun select(hub: LcncNode): SelectStmt {
            node(hub.id)
            val itemIds = ordered(hub, "items")
            if (itemIds.isEmpty()) throw SqlGraphError(hub.id, "missing-input", "sql.select.items is unwired")
            val columns = itemIds.map { id ->
                val item = node(id)
                val column = when (item.type) {
                    ITEM_STAR -> {
                        if (itemIds.size > 1) throw SqlGraphError(id, "ambiguous-star", "sql.itemStar beside other items; the planner reads * only as the sole item")
                        Column(LitExpr(StringLiteral("*".toSeries())), null)
                    }
                    ITEM_EXPR -> Column(expr(one(item, "expr")), name(item, "alias"))
                    else -> throw SqlGraphError(id, "unexpected-node", "${item.type} where a select item is expected")
                }
                done(id)
                column
            }
            val joins = ArrayList<JoinClause>()
            val from = spine(one(hub, "from"), joins)
            val where = optional(hub, "where")?.let(::expr)
            val groupBy = many(hub, "groupBy").map(::expr)
            val having = optional(hub, "having")?.let(::expr)
            val orderBy = ordered(hub, "orderBy").map { id ->
                val key = node(id)
                if (key.type != SORT_KEY) throw SqlGraphError(id, "unexpected-node", "${key.type} where a sort key is expected")
                val order = OrderItem(expr(one(key, "expr")), choice(key, "direction") == "DESC")
                done(id)
                order
            }
            val stmt = SelectStmt(
                columns.toSeries(), from, where, joins.toSeries(), groupBy.toSeries(), having, orderBy.toSeries(),
                nonNegative(hub, "limit"), nonNegative(hub, "offset"),
            )
            done(hub.id)
            return stmt
        }

        /** The left spine of a joined-table: the first table-reference, its joins in order. */
        private fun spine(id: String, joins: MutableList<JoinClause>): TableRef {
            val n = node(id)
            val ref = when (n.type) {
                TABLE_NAME -> tableRef(n)
                JOIN -> {
                    val right = byId.getValue(one(n, "right"))
                    if (right.type != TABLE_NAME) throw SqlGraphError(right.id, "unsupported-in-ast", "a join's right side must be a table; the AST joins left to right")
                    node(right.id)
                    val left = spine(one(n, "left"), joins)
                    joins += JoinClause(tableRef(right), optional(n, "on")?.let(::expr), choice(n, "join") == "LEFT")
                    done(right.id)
                    left
                }
                else -> throw SqlGraphError(id, "unexpected-node", "${n.type} where a table is expected")
            }
            done(id)
            return ref
        }

        private fun tableRef(n: LcncNode) = TableRef(Identifier(required(n, "name").trim().toSeries()), name(n, "alias"))

        private fun name(n: LcncNode, key: String): Identifier? = text(n, key).trim().takeIf { it.isNotEmpty() }?.let { Identifier(it.toSeries()) }

        private fun expr(id: String): Expr {
            val n = node(id)
            val e: Expr = when (n.type) {
                COL -> ColumnRef(Identifier(required(n, "name").trim().toSeries()))
                LIT -> literal(n)
                CALL -> {
                    // The parser lowercases function names and cast types; the planner matches only that spelling.
                    val name = required(n, "name").trim().lowercase()
                    if (choice(n, "star") == "true") {
                        if (wiresInto(n, "args").isNotEmpty()) throw SqlGraphError(id, "bad-param", "COUNT(*) form with argument wires")
                        Call(name, listOf<Expr>(Star).toSeries())
                    } else {
                        val args = many(n, "args")
                        if (args.size > 1) throw SqlGraphError(id, "unsupported-in-ast", "$name() with ${args.size} arguments: call arguments carry no order of their own")
                        Call(name, args.map(::expr).toSeries())
                    }
                }
                CAST -> Cast(expr(one(n, "value")), required(n, "type").trim().lowercase())
                PATH -> Path(expr(one(n, "cell")), steps(n), choice(n, "text") == "true")
                ARITH -> BinaryExpr(expr(one(n, "left")), operator(n, ARITH_OPS), expr(one(n, "right")))
                CMP -> BinaryExpr(expr(one(n, "left")), operator(n, CMP_OPS), expr(one(n, "right")))
                LIKE -> BinaryExpr(expr(one(n, "match")), "LIKE", expr(one(n, "pattern")))
                LOGIC -> BinaryExpr(expr(one(n, "left")), operator(n, LOGIC_OPS), expr(one(n, "right")))
                AS_CONDITION -> expr(one(n, "value"))
                AS_VALUE -> expr(one(n, "cond"))
                else -> throw SqlGraphError(id, "unexpected-node", "${n.type} where an expression is expected")
            }
            done(id)
            return e
        }

        private fun operator(n: LcncNode, allowed: List<String>): String = choice(n, "op").also { check(it in allowed) }

        private fun literal(n: LcncNode): Expr {
            val value = text(n, "value")
            return when (val kind = choice(n, "kind")) {
                "string" -> LitExpr(StringLiteral(value.toSeries()))
                "int" -> {
                    val v = value.trim().toLongOrNull() ?: throw SqlGraphError(n.id, "bad-param", "int literal '$value'")
                    LitExpr(NumericLiteral(if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v))
                }
                "double" -> LitExpr(NumericLiteral(value.trim().toDoubleOrNull() ?: throw SqlGraphError(n.id, "bad-param", "double literal '$value'")))
                else -> throw SqlGraphError(n.id, "bad-param", "literal kind '$kind'")
            }
        }

        private fun steps(n: LcncNode): JsPath {
            val json = text(n, "steps").trim()
            val cell = json.takeIf { it.startsWith("[") }?.let(::confixCellOf)?.takeIf { it.row.tag == IOMemento.IoArray }
                ?: throw SqlGraphError(n.id, "bad-param", "steps is not a JSON array")
            val kids = cell.row.kids
            if (kids.size == 0) throw SqlGraphError(n.id, "bad-param", "steps is empty")
            val out = ArrayList<JsPathElement>()
            for (i in 0 until kids.size) {
                val v = kids[i].reify(cell.src)
                out += when {
                    v is String && v.isNotEmpty() -> keyOf(v)
                    v is Number && v.toDouble() % 1.0 == 0.0 && v.toDouble() in 0.0..Int.MAX_VALUE.toDouble() -> idxOf(v.toInt())
                    else -> throw SqlGraphError(n.id, "bad-param", "step $i is neither a key nor a non-negative index")
                }
            }
            return out.toSeries()
        }
    }

    // ── identity ────────────────────────────────────────────────────────────────────────────────

    /**
     * The statement's structural identity: every node, literal kind and path step, so two ASTs with
     * the same signature plan identically. [render] prints `x->'a'->'b'` and `x#>'{a,b}'` alike and
     * does not escape path keys; this does not.
     */
    fun signature(stmt: SelectStmt): String = buildString {
        append("select ")
        for (i in 0 until stmt.columns.size) {
            if (i > 0) append(", ")
            append(sig(stmt.columns[i].expr))
            stmt.columns[i].alias?.let { append(" as ").append(jsonString(it.asString())) }
        }
        stmt.from?.let { append(" from ").append(sig(it)) }
        for (i in 0 until stmt.joins.size) {
            val j = stmt.joins[i]
            append(if (j.outer) " left join " else " join ").append(sig(j.table))
            j.on?.let { append(" on ").append(sig(it)) }
        }
        stmt.where?.let { append(" where ").append(sig(it)) }
        if (stmt.groupBy.size > 0) append(" group by ").append((0 until stmt.groupBy.size).joinToString(", ") { sig(stmt.groupBy[it]) })
        stmt.having?.let { append(" having ").append(sig(it)) }
        if (stmt.orderBy.size > 0) append(" order by ").append((0 until stmt.orderBy.size).joinToString(", ") {
            sig(stmt.orderBy[it].expr) + if (stmt.orderBy[it].descending) " desc" else " asc"
        })
        stmt.limit?.let { append(" limit ").append(it) }
        stmt.offset?.let { append(" offset ").append(it) }
    }

    private fun sig(t: TableRef): String = jsonString(t.name.asString()) + (t.alias?.let { " " + jsonString(it.asString()) } ?: "")

    private fun sig(e: Expr): String = when (e) {
        is ColumnRef -> "col(" + jsonString(e.id.asString()) + ")"
        is LitExpr -> when (val lit = e.lit) {
            is StringLiteral -> "str(" + jsonString(lit.asString()) + ")"
            is NumericLiteral -> (if (isIntegral(lit.value)) "int(" else "double(") + lit.value + ")"
            else -> "lit(${lit::class.simpleName})"
        }
        is BinaryExpr -> "(" + sig(e.left) + " " + e.op + " " + sig(e.right) + ")"
        is Call -> e.name + "(" + (0 until e.args.size).joinToString(",") { sig(e.args[it]) } + ")"
        Star -> "*"
        is Cast -> "cast(" + sig(e.expr) + " as " + e.type + ")"
        is Path -> "path(" + sig(e.cell) + "," + stepsJson(e.path) + "," + e.text + ")"
    }

    private fun stepsJson(path: JsPath): String = buildString {
        append('[')
        for (i in 0 until path.size) {
            if (i > 0) append(',')
            val step = path[i]
            val key = step.key
            if (key != null) append(jsonString(key)) else append(step.idx)
        }
        append(']')
    }

    /** RFC 8259 string: quotes, backslashes and control characters escaped, everything else as is. */
    private fun jsonString(raw: String): String = buildString {
        append('"')
        for (c in raw) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}
