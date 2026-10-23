package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations

/** A Cypher statement the front end refuses: a syntax error or a construct outside the subset. */
class CypherError(message: String) : IllegalArgumentException(message)

/**
 * Cypher's MATCH subset over the quad store, read the way a property graph lands in RDF: a node is an
 * IRI, a label is an `rdf:type`, a relationship type is a predicate between two nodes, a property is a
 * predicate to a literal — every name under one [namespace] (`RdfVocab.FORGE` unless told otherwise).
 *
 *   MATCH (a:Label {k: v})-[:REL]->(b), (b)<-[:OTHER]-(c)   WHERE a.k > 1 AND b.name STARTS WITH "x"
 *   RETURN [DISTINCT] a.k [AS alias], b, count(*)   ORDER BY a.k DESC   SKIP n   LIMIT m
 *
 * Each node, label, relationship and property becomes a triple pattern of one [QueryK.Graph]; a property
 * read (`a.k`) joins its own pattern, so a node without the property drops (Cypher would answer null —
 * there is no OPTIONAL here yet); a node with no constraint at all is any subject, once per triple about it, so
 * `RETURN DISTINCT` names nodes. `-->` without a type is a wildcard predicate. Relationship variables,
 * undirected or alternative relationships and `IS NULL` are refused by name. Aggregates group by the
 * other items as Cypher's implicit grouping does. The rest is the SQL planner's own stages.
 */
class CypherQuery(val text: String, val graph: QueryK.Graph, val select: SelectStmt, val columns: List<String>) {
    fun plan(
        source: Cursor,
        ops: ColumnOperations = KotlinColumnOperations,
        compiler: ExprCompiler = ClosureExprCompiler,
        provenance: (String) -> borg.trikeshed.job.ContentId? = { null },
    ): QueryCursor = select.plan({ source }, ops, compiler, provenance, prelude = listOf(graph), statement = text)

    companion object {
        fun parse(text: String, namespace: String = RdfVocab.FORGE): CypherQuery = CypherParser(text.trim(), namespace).query()
    }
}

/**
 * Neo4j's HTTP API shape over [store]: `POST /db/{db}/tx/commit` with `{"statements":[{"statement":"…"}]}`
 * answers `{"results":[{"columns":[…],"data":[{"row":[…]}]}],"errors":[…]}` — one result per statement,
 * cells as JSON (an IRI as its text, a typed numeric literal as a number, a boolean as a boolean, any other
 * literal as its lexical form). A refused statement is an error object, as Neo4j reports it, with 200.
 */
class CypherSurface(val store: QuadStore, val namespace: String = RdfVocab.FORGE, val compiler: ExprCompiler = ClosureExprCompiler) {
    fun handle(method: String, rawPath: String, headers: Map<String, String>, body: String, live: Cursor? = null): PostgrestReply {
        if (method != "POST") return PostgrestReply(405, "{\"results\":[],\"errors\":[{\"code\":\"Neo.ClientError.Request.Invalid\",\"message\":\"$method is not allowed; POST a transaction\"}]}", mapOf("Allow" to "POST"))
        val statements = (runCatching { reify(body) }.getOrNull() as? Map<*, *>)?.get("statements") as? List<*>
            ?: return PostgrestReply(400, "{\"results\":[],\"errors\":[{\"code\":\"Neo.ClientError.Request.InvalidFormat\",\"message\":\"a transaction body is {statements:[{statement}]}\"}]}")
        val quads = if (live == null || live.size == 0) store.scan() else combine(store.scan(), live)
        val results = ArrayList<String>()
        val errors = ArrayList<String>()
        for (s in statements) {
            val statement = (s as? Map<*, *>)?.get("statement")?.toString()
            if (statement == null) { errors += "{\"code\":\"Neo.ClientError.Request.InvalidFormat\",\"message\":\"a statement is {statement: text}\"}"; continue }
            try {
                val query = CypherQuery.parse(statement, namespace)
                val rows = query.plan(quads, compiler = compiler)
                results += result(query.columns, rows)
            } catch (e: CypherError) {
                errors += "{\"code\":\"Neo.ClientError.Statement.SyntaxError\",\"message\":${jsonOf(e.message)}}"
            } catch (e: Exception) {
                errors += "{\"code\":\"Neo.ClientError.Statement.SemanticError\",\"message\":${jsonOf(e.message ?: e::class.simpleName)}}"
            }
        }
        return PostgrestReply(200, "{\"results\":[${results.joinToString(",")}],\"errors\":[${errors.joinToString(",")}]}")
    }

    /** One statement's `{"columns":[…],"data":[{"row":[…]}]}`. */
    fun result(columns: List<String>, rows: Cursor): String = buildString {
        append("{\"columns\":[").append(columns.joinToString(",") { jsonOf(it) }).append("],\"data\":[")
        for (r in 0 until rows.size) {
            if (r > 0) append(',')
            val cells = rows[r].values
            append("{\"row\":[")
            for (c in columns.indices) { if (c > 0) append(','); append(cellJson(cells[c])) }
            append("]}")
        }
        append("]}")
    }

    companion object {
        val PATH = Regex("^/db/[^/]+/tx/commit$")

        /** A cell as Neo4j would hand it: numbers and booleans as such, IRIs and other literals as text. */
        fun cellJson(cell: Any?): String {
            val term = SparqlResults.termOf(cell) ?: return "null"
            val numeric = term.datatype?.let { it.startsWith(RdfVocab.XSD) && it.substringAfter('#') in NUMERIC } == true
            return when {
                term.type == "bnode" -> jsonOf("_:" + term.value)
                term.datatype == RdfVocab.XSD + "boolean" -> if (term.value == "true") "true" else "false"
                numeric -> term.value.toLongOrNull()?.toString() ?: term.value.toDoubleOrNull()?.takeIf { it.isFinite() }?.toString() ?: jsonOf(term.value)
                else -> jsonOf(term.value)
            }
        }

        val NUMERIC = setOf("integer", "decimal", "double", "float", "int", "long", "short", "byte", "nonNegativeInteger", "positiveInteger")
    }
}

// ── the parser ───────────────────────────────────────────────────────────────────────────────────

enum class CypherTokenKind { WORD, NUMBER, STRING, PUNCT, EOF }
class CypherToken(val kind: CypherTokenKind, val text: String, val quoted: Boolean = false) {
    override fun toString(): String = if (kind == CypherTokenKind.EOF) "end of statement" else "'$text'"
}

val CYPHER_KEYWORDS = setOf("MATCH", "WHERE", "RETURN", "DISTINCT", "ORDER", "BY", "ASC", "DESC", "SKIP", "LIMIT", "AND", "OR", "NOT", "AS", "IN", "STARTS", "ENDS", "WITH", "CONTAINS", "IS", "NULL", "TRUE", "FALSE", "OPTIONAL", "CREATE", "MERGE", "DELETE", "SET", "UNWIND", "UNION", "CALL")
val CYPHER_AGGREGATES = setOf("COUNT", "SUM", "MIN", "MAX", "AVG")

class CypherParser(val src: String, val namespace: String) {
    val tokens = cypherTokens(src)
    var pos = 0
    val patterns = ArrayList<TriplePattern>()
    val filters = ArrayList<Expr>()
    /** Property reads already joined, `a.k` → its pattern's object variable. */
    val properties = LinkedHashSet<String>()
    /** Node variables MATCH declared, in order. */
    val nodes = LinkedHashSet<String>()
    var anonymous = 0

    fun peek(offset: Int = 0): CypherToken = tokens[minOf(pos + offset, tokens.size - 1)]
    fun next(): CypherToken = tokens[pos].also { if (pos < tokens.size - 1) pos++ }
    fun fail(message: String): Nothing = throw CypherError(message)
    fun word(vararg words: String): Boolean = peek().kind == CypherTokenKind.WORD && peek().text.uppercase() in words
    fun punct(text: String): Boolean = peek().kind == CypherTokenKind.PUNCT && peek().text == text
    fun expectWord(w: String) { if (!word(w)) fail("expected $w, found ${peek()}"); next() }
    fun expectPunct(p: String) { if (!punct(p)) fail("expected '$p', found ${peek()}"); next() }
    fun take(p: String): Boolean = punct(p).also { if (it) next() }
    fun iri(local: String): String = "<$namespace$local>"

    fun query(): CypherQuery {
        if (word("OPTIONAL", "CREATE", "MERGE", "DELETE", "SET", "UNWIND", "CALL")) fail("${peek().text.uppercase()} is not supported; the subset is MATCH … WHERE … RETURN")
        expectWord("MATCH")
        do pattern() while (take(","))
        // a node with no label, property, relationship or property read is any subject, once per triple about it
        for (n in nodes) if (patterns.none { p -> p.slots.any { it?.isVariable == true && it.name == n } }) patterns += TriplePattern(PatternTerm("?$n"), PatternTerm("?_p${anonymous++}"), PatternTerm("?_o${anonymous++}"))
        if (word("WHERE")) { next(); filters += expression() }
        if (word("OPTIONAL", "UNION", "WITH")) fail("${peek().text.uppercase()} is not supported; the subset is MATCH … WHERE … RETURN")
        expectWord("RETURN")
        val distinct = word("DISTINCT").also { if (it) next() }
        val items = ArrayList<Triple<Expr, String, Boolean>>() // expr, column name, aggregated
        do {
            val start = pos
            val e = expression()
            val name = if (word("AS")) { next(); identifier("a column alias") } else spelled(start, pos)
            items += Triple(e, name, e.aggregated())
        } while (take(","))
        val orderBy = ArrayList<OrderItem>()
        var skip: Int? = null
        var limit: Int? = null
        while (peek().kind != CypherTokenKind.EOF) {
            when {
                word("ORDER") -> {
                    next(); expectWord("BY")
                    do {
                        val e = expression()
                        val desc = word("DESC", "DESCENDING").also { if (it || word("ASC", "ASCENDING")) next() }
                        orderBy += OrderItem(e, desc)
                    } while (take(","))
                }
                word("SKIP") -> { next(); skip = integer("SKIP") }
                word("LIMIT") -> { next(); limit = integer("LIMIT") }
                else -> fail("unexpected ${peek()} after RETURN")
            }
        }
        val graph = QueryK.Graph(patterns.toSeries())
        val bound = graph.variables()
        for ((e, name, _) in items) if (e is ColumnRef && e.id.asString() !in bound) fail("$name is not bound by the pattern")
        // ORDER BY a property: numbers before text, numerically, then the lexeme — the key pair sorts both kinds
        val ordering = orderBy.flatMap { o -> if (o.expr is ColumnRef && '.' in o.expr.id.asString() && o.expr.id.asString() in bound) listOf(OrderItem(Cast(o.expr, "float8"), o.descending), o) else listOf(o) }
        val aggregated = items.any { it.third }
        val groupBy = when {
            aggregated -> items.filter { !it.third }.map { it.first }
            distinct -> items.map { it.first }
            else -> emptyList()
        }
        val select = SelectStmt(
            items.map { (e, name, _) -> Column(e, Identifier(name.toSeries())) }.toSeries(),
            TableRef(Identifier(QuadStore.RDF_QUAD.toSeries())),
            filters.reduceOrNull { a, b -> BinaryExpr(a, "AND", b) },
            groupBy = groupBy.toSeries(), orderBy = ordering.toSeries(), limit = limit, offset = skip,
        )
        return CypherQuery(src, graph, select, items.map { it.second })
    }

    fun integer(clause: String): Int {
        val t = next()
        return t.text.toIntOrNull()?.takeIf { t.kind == CypherTokenKind.NUMBER && it >= 0 } ?: fail("$clause needs a non-negative integer, found $t")
    }

    fun identifier(what: String): String {
        val t = next()
        if (t.kind != CypherTokenKind.WORD || (!t.quoted && t.text.uppercase() in CYPHER_KEYWORDS)) fail("expected $what, found $t")
        return t.text
    }

    /** The next token is a name a variable can take: a word that is not a keyword, or any backtick-quoted word. */
    fun nameAhead(): Boolean = peek().kind == CypherTokenKind.WORD && (peek().quoted || peek().text.uppercase() !in CYPHER_KEYWORDS)

    /** The statement's own spelling of tokens [from] until [until]: the column name Cypher would print. */
    fun spelled(from: Int, until: Int): String = buildString {
        for (i in from until until) {
            val t = tokens[i]
            if (i > from && t.kind != CypherTokenKind.PUNCT && tokens[i - 1].kind != CypherTokenKind.PUNCT) append(' ')
            append(if (t.kind == CypherTokenKind.STRING) "\"" + lexicalOf(t.text) + "\"" else t.text)
        }
    }

    // ── patterns ────────────────────────────────────────────────────────────────────────────────

    fun pattern() {
        var left = node()
        while (punct("-") || punct("<-")) {
            val inbound = take("<-")
            if (!inbound) expectPunct("-")
            var predicate: PatternTerm? = null
            if (take("[")) {
                if (nameAhead()) fail("relationship variables have no identity in RDF; name the type: [:TYPE]")
                if (take(":")) {
                    predicate = PatternTerm(iri(identifier("a relationship type")))
                    if (take("|")) fail("alternative relationship types are not supported")
                }
                if (punct("*")) fail("variable-length paths are not supported")
                expectPunct("]")
            }
            val outbound = take("->")
            if (!inbound && !outbound) { if (take("-")) fail("undirected relationships are not supported; use -> or <-") else fail("expected -> after the relationship") }
            if (inbound && outbound) fail("a relationship has one direction")
            if (inbound) expectPunct("-")
            val right = node()
            val p = predicate ?: PatternTerm("?_p${anonymous++}").also { wildcard ->
                // an untyped edge is any predicate but rdf:type whose object is a node, never a property's literal
                filters += BinaryExpr(ColumnRef(Identifier(wildcard.name.toSeries())), "<>", LitExpr(StringLiteral(SparqlQuery.RDF_TYPE.toSeries())))
                filters += BinaryExpr(ColumnRef(Identifier((if (inbound) left else right).name.toSeries())), "LIKE", LitExpr(StringLiteral("<%".toSeries())))
            }
            patterns += if (inbound) TriplePattern(right, p, left) else TriplePattern(left, p, right)
            left = right
        }
    }

    fun node(): PatternTerm {
        expectPunct("(")
        val variable = if (nameAhead()) identifier("a node variable") else "_n${anonymous++}"
        nodes += variable
        val term = PatternTerm("?$variable")
        while (take(":")) patterns += TriplePattern(term, PatternTerm(SparqlQuery.RDF_TYPE), PatternTerm(iri(identifier("a label"))))
        if (take("{")) {
            if (!punct("}")) do {
                val key = identifier("a property key")
                expectPunct(":")
                patterns += TriplePattern(term, PatternTerm(iri(key)), PatternTerm(lexeme(literalToken())))
            } while (take(","))
            expectPunct("}")
        }
        expectPunct(")")
        return term
    }

    fun literalToken(): CypherToken {
        val negative = take("-")
        val t = next()
        if (t.kind != CypherTokenKind.STRING && t.kind != CypherTokenKind.NUMBER && !(t.kind == CypherTokenKind.WORD && t.text.uppercase() in setOf("TRUE", "FALSE"))) fail("expected a literal, found $t")
        if (negative && t.kind != CypherTokenKind.NUMBER) fail("expected a number after '-', found $t")
        return if (negative) CypherToken(CypherTokenKind.NUMBER, "-" + t.text) else t
    }

    /** A literal token as the lexeme the store holds, spelled as SPARQL spells it: strings plain, integers xsd:integer, decimals xsd:decimal, exponents xsd:double, booleans xsd:boolean. */
    fun lexeme(t: CypherToken): String = when (t.kind) {
        CypherTokenKind.STRING -> t.text
        CypherTokenKind.NUMBER -> "\"${t.text}\"^^" + when { 'e' in t.text || 'E' in t.text -> SparqlQuery.XSD_DOUBLE; '.' in t.text -> SparqlQuery.XSD_DECIMAL; else -> SparqlQuery.XSD_INTEGER }
        else -> "\"${t.text.lowercase()}\"^^${SparqlQuery.XSD_BOOLEAN}"
    }

    /** A boolean literal compares as the lexeme the store holds. */
    fun stored(e: Expr): Expr = if (e is LitExpr && e.lit is BooleanLiteral) LitExpr(StringLiteral("\"${(e.lit as BooleanLiteral).value}\"^^${SparqlQuery.XSD_BOOLEAN}".toSeries())) else e

    /** `v.k` reads property `k` of node `v` through its own pattern, bound to the variable `v.k`. */
    fun property(node: String, key: String): ColumnRef {
        if (node !in nodes) fail("$node is not declared in MATCH")
        val name = "$node.$key"
        if (properties.add(name)) patterns += TriplePattern(PatternTerm("?$node"), PatternTerm(iri(key)), PatternTerm("?$name"))
        return ColumnRef(Identifier(name.toSeries()))
    }

    // ── expressions, lowered to the SQL AST ──────────────────────────────────────────────────

    fun expression(): Expr = orExpr()
    fun orExpr(): Expr { var e = andExpr(); while (word("OR")) { next(); e = BinaryExpr(e, "OR", andExpr()) }; return e }
    fun andExpr(): Expr { var e = notExpr(); while (word("AND")) { next(); e = BinaryExpr(e, "AND", notExpr()) }; return e }
    fun notExpr(): Expr = if (word("NOT")) { next(); Not(notExpr()) } else comparison()

    fun comparison(): Expr {
        val left = additive()
        return when {
            peek().kind == CypherTokenKind.PUNCT && peek().text in setOf("=", "<>", "<", "<=", ">", ">=") -> {
                val op = next().text
                val right = additive()
                val numeric = left.isNumeric() || right.isNumeric()
                BinaryExpr(if (numeric) numeric(left) else stored(left), op, if (numeric) numeric(right) else stored(right))
            }
            word("STARTS") -> { next(); expectWord("WITH"); like(left, "\"${needle()}%") }
            word("ENDS") -> { next(); expectWord("WITH"); like(left, "%${needle()}\"%") }
            word("CONTAINS") -> { next(); like(left, "%${needle()}%") }
            word("IN") -> {
                next(); expectPunct("[")
                val items = ArrayList<Expr>()
                if (!punct("]")) do items += stored(unary()) while (take(","))
                expectPunct("]")
                val numeric = items.any { it.isNumeric() }
                InList(if (numeric) numeric(left) else left, items.toSeries())
            }
            word("IS") -> fail("IS NULL / IS NOT NULL are not supported: a property read joins its pattern, so it is never null")
            else -> left
        }
    }

    fun needle(): String = ((primary() as? LitExpr)?.lit as? StringLiteral)?.asString()?.let { lexicalOf(it).replace("%", "\\%").replace("_", "\\_") } ?: fail("expected a string")
    fun like(subject: Expr, pattern: String): Expr = BinaryExpr(subject, "LIKE", LitExpr(StringLiteral(pattern.toSeries())))

    fun additive(): Expr { var e = multiplicative(); while (punct("+") || punct("-")) { val op = next().text; e = BinaryExpr(numeric(e), op, numeric(multiplicative())) }; return e }
    fun multiplicative(): Expr { var e = unary(); while (punct("*") || punct("/")) { val op = next().text; e = BinaryExpr(numeric(e), op, numeric(unary())) }; return e }
    fun unary(): Expr = if (take("-")) BinaryExpr(LitExpr(NumericLiteral(0)), "-", numeric(unary())) else primary()

    fun primary(): Expr {
        val t = peek()
        return when (t.kind) {
            CypherTokenKind.PUNCT -> if (take("(")) expression().also { expectPunct(")") } else fail("unexpected $t in an expression")
            CypherTokenKind.NUMBER -> { next(); LitExpr(NumericLiteral((if ('.' in t.text || 'e' in t.text || 'E' in t.text) t.text.toDoubleOrNull() else t.text.toLongOrNull()) ?: fail("number out of range: ${t.text}"), numberText(t.text))) }
            CypherTokenKind.STRING -> { next(); LitExpr(StringLiteral(t.text.toSeries())) }
            CypherTokenKind.WORD -> when (t.text.uppercase()) {
                "TRUE", "FALSE" -> { next(); LitExpr(BooleanLiteral(t.text.uppercase() == "TRUE")) }
                in CYPHER_AGGREGATES -> aggregate()
                in CYPHER_KEYWORDS -> fail("unexpected $t in an expression")
                else -> {
                    next()
                    if (take(".")) property(t.text, identifier("a property key"))
                    else { if (t.text !in nodes) fail("${t.text} is not declared in MATCH"); ColumnRef(Identifier(t.text.toSeries())) }
                }
            }
            CypherTokenKind.EOF -> fail("unexpected end of statement in an expression")
        }
    }

    fun aggregate(): Expr {
        val name = next().text.lowercase()
        expectPunct("(")
        if (word("DISTINCT")) fail("$name(DISTINCT …) is not supported")
        val arg: Expr = if (take("*")) { if (name != "count") fail("$name(*) is not an aggregate"); Star } else expression()
        expectPunct(")")
        return Call(name, s_[if (name == "sum" || name == "avg") numeric(arg) else arg])
    }

    fun numeric(e: Expr): Expr = if (e is ColumnRef) Cast(e, "float8") else e
    fun Expr.isNumeric(): Boolean = when (this) {
        is LitExpr -> lit is NumericLiteral
        is Cast -> type == "float8"
        is BinaryExpr -> op in setOf("+", "-", "*", "/")
        else -> false
    }
}

/** Cypher tokens: words (keywords, variables, labels, keys), numbers, strings (as plain `"…"` lexemes), punctuation. */
fun cypherTokens(src: String): List<CypherToken> {
    val out = ArrayList<CypherToken>()
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c.isWhitespace() -> i++
            c == '/' && src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') i++
            c.isLetter() || c == '_' -> { var j = i + 1; while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++; out += CypherToken(CypherTokenKind.WORD, src.substring(i, j)); i = j }
            c == '`' -> { val close = src.indexOf('`', i + 1); if (close < 0) throw CypherError("unterminated `identifier`"); out += CypherToken(CypherTokenKind.WORD, src.substring(i + 1, close), quoted = true); i = close + 1 }
            c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                var j = i + 1
                while (j < src.length && src[j].isDigit()) j++
                if (j < src.length && src[j] == '.' && j + 1 < src.length && src[j + 1].isDigit()) { j++; while (j < src.length && src[j].isDigit()) j++ }
                if (j < src.length && (src[j] == 'e' || src[j] == 'E') && j + 1 < src.length && (src[j + 1].isDigit() || (src[j + 1] in "+-" && j + 2 < src.length && src[j + 2].isDigit()))) { j += 2; while (j < src.length && src[j].isDigit()) j++ }
                out += CypherToken(CypherTokenKind.NUMBER, src.substring(i, j)); i = j
            }
            c == '"' || c == '\'' -> {
                val sb = StringBuilder("\"")
                var j = i + 1
                var closed = false
                while (j < src.length) {
                    val ch = src[j]
                    when {
                        ch == '\\' && j + 1 < src.length -> { val e = src[j + 1]; sb.append(if (e == '\'') "'" else "\\$e"); j += 2 }
                        ch == c -> { closed = true; j++; break }
                        ch == '"' -> { sb.append("\\\""); j++ }
                        ch == '\n' -> { sb.append("\\n"); j++ }
                        ch == '\t' -> { sb.append("\\t"); j++ }
                        ch == '\r' -> { sb.append("\\r"); j++ }
                        else -> { sb.append(ch); j++ }
                    }
                }
                if (!closed) throw CypherError("unterminated string literal")
                out += CypherToken(CypherTokenKind.STRING, sb.append('"').toString()); i = j
            }
            else -> {
                val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                val arrow = two == "<-" && i + 2 < src.length && (src[i + 2] == '[' || src[i + 2] == '(')
                if (arrow || two in setOf("->", "<>", "<=", ">=")) { out += CypherToken(CypherTokenKind.PUNCT, two); i += 2 }
                else if (c in "()[]{}:,.|=<>-+*/") { out += CypherToken(CypherTokenKind.PUNCT, c.toString()); i++ }
                else throw CypherError("unexpected character '$c'")
            }
        }
    }
    out += CypherToken(CypherTokenKind.EOF, "")
    return out
}
