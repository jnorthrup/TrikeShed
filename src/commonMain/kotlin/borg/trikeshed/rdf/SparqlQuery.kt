package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations

/** A SPARQL query the front end refuses: a syntax error, an unknown prefix, or a feature outside the subset. */
class SparqlError(message: String, val line: Int = 0) : IllegalArgumentException(if (line > 0) "line $line: $message" else message)

/**
 * SPARQL 1.1 query, the subset that lowers to the one plan: `PREFIX`/`BASE`, `SELECT [DISTINCT] ?v…|*`
 * with `(aggregate AS ?v)` items, `ASK`, `FROM <g>` / `FROM NAMED <g>`, `WHERE { }` holding triple
 * patterns (`;` `,` `a`), `GRAPH ?g|<g> { }`, nested groups and `FILTER` (comparisons, `&&` `||` `!`,
 * arithmetic, `BOUND`, `CONTAINS`, `STRSTARTS`, `STRENDS`), `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`,
 * `OFFSET`. `OPTIONAL`, `UNION`, `MINUS`, `BIND`, `VALUES`, property paths, `CONSTRUCT` and `DESCRIBE`
 * are refused by name. The basic graph pattern becomes one [QueryK.Graph] stage; everything after it
 * is the SQL planner's own work over a [SelectStmt] whose columns are the variables, so a numeric
 * comparison casts the lexeme (`"0.9"^^<xsd:float>` reads as 0.9) and `COUNT`/`SUM`/`MIN`/`MAX`/`AVG`
 * are the one `Aggregate` stage. Term comparison is lexeme equality (a typed literal equals itself).
 */
class SparqlQuery(
    val text: String,
    val ask: Boolean,
    val graph: QueryK.Graph,
    val select: SelectStmt,
    /** The projected variable names, in order: the result head even with no rows. */
    val variables: List<String>,
) {
    fun plan(
        source: Cursor,
        ops: ColumnOperations = KotlinColumnOperations,
        compiler: ExprCompiler = ClosureExprCompiler,
        provenance: (String) -> borg.trikeshed.job.ContentId? = { null },
    ): QueryCursor = select.plan({ source }, ops, compiler, provenance, prelude = listOf(graph), statement = text)

    companion object {
        /**
         * Parses [text]; a non-empty protocol dataset ([defaultGraphUris], [namedGraphUris], bare IRIs)
         * replaces the query's own `FROM` / `FROM NAMED`, as the SPARQL protocol says.
         */
        fun parse(text: String, defaultGraphUris: List<String> = emptyList(), namedGraphUris: List<String> = emptyList()): SparqlQuery =
            SparqlParser(text.trim()).query(defaultGraphUris.filter { it.isNotBlank() }, namedGraphUris.filter { it.isNotBlank() })

        val XSD_INTEGER = "<${RdfVocab.XSD}integer>"
        val XSD_DECIMAL = "<${RdfVocab.XSD}decimal>"
        val XSD_DOUBLE = "<${RdfVocab.XSD}double>"
        val XSD_BOOLEAN = "<${RdfVocab.XSD}boolean>"
        val RDF_TYPE = "<${RdfVocab.RDF}type>"
    }
}

/**
 * SPARQL 1.1 Update, the data subset: `INSERT DATA { … }` and `DELETE DATA { … }` (quads in `GRAPH <g> { }`
 * groups or the default graph, `;` between operations), applied to a [QuadStore]. Pattern updates
 * (`INSERT … WHERE`, `DELETE … WHERE`, `WITH`, `LOAD`, `CLEAR`) are refused by name.
 */
class SparqlUpdate(val text: String, val operations: List<Operation>) {
    sealed class Operation(val quads: List<TriplePattern>) {
        class Insert(quads: List<TriplePattern>) : Operation(quads)
        class Delete(quads: List<TriplePattern>) : Operation(quads)
    }

    /** Applies every operation in order; answers (inserted, deleted) counts. */
    fun apply(store: QuadStore): Pair<Int, Int> {
        var inserted = 0
        var deleted = 0
        for (op in operations) for (q in op.quads) {
            val graph = q.g?.text ?: QuadStore.DEFAULT_GRAPH
            when (op) {
                is Operation.Insert -> { store.add(q.s.text, q.p.text, q.o.text, graph); inserted++ }
                is Operation.Delete -> if (store.remove(q.s.text, q.p.text, q.o.text, graph)) deleted++
            }
        }
        return inserted to deleted
    }

    companion object {
        fun parse(text: String): SparqlUpdate = SparqlParser(text.trim()).update()
    }
}

val SPARQL_UNSUPPORTED = setOf("OPTIONAL", "UNION", "MINUS", "BIND", "VALUES", "SERVICE", "CONSTRUCT", "DESCRIBE", "INSERT", "DELETE", "LOAD", "CLEAR", "CREATE", "DROP")
val SPARQL_AGGREGATES = setOf("COUNT", "SUM", "MIN", "MAX", "AVG")

enum class SparqlTokenKind { IRI, PNAME, VAR, STRING, NUMBER, WORD, PUNCT, EOF }
class SparqlToken(val kind: SparqlTokenKind, val text: String, val line: Int) {
    override fun toString(): String = if (kind == SparqlTokenKind.EOF) "end of query" else "'$text'"
}

class SparqlItem(val expr: Expr, val name: String, val aggregated: Boolean)

class SparqlParser(val src: String) {
    val tokens = sparqlTokens(src)
    var pos = 0
    val prefixes = TurtleRdf.defaultPrefixes().toMutableMap()
    val patterns = ArrayList<TriplePattern>()
    val filters = ArrayList<Expr>()

    fun peek(offset: Int = 0): SparqlToken = tokens[minOf(pos + offset, tokens.size - 1)]
    fun next(): SparqlToken = tokens[pos].also { if (pos < tokens.size - 1) pos++ }
    fun fail(message: String): Nothing = throw SparqlError(message, peek().line)
    fun word(vararg words: String): Boolean = peek().kind == SparqlTokenKind.WORD && peek().text.uppercase() in words
    fun punct(text: String): Boolean = peek().kind == SparqlTokenKind.PUNCT && peek().text == text
    fun expectWord(w: String) { if (!word(w)) fail("expected $w, found ${peek()}"); next() }
    fun expectPunct(p: String) { if (!punct(p)) fail("expected '$p', found ${peek()}"); next() }
    fun take(p: String): Boolean = punct(p).also { if (it) next() }

    fun query(defaultGraphUris: List<String>, namedGraphUris: List<String>): SparqlQuery {
        while (word("PREFIX", "BASE")) {
            val kw = next().text.uppercase()
            if (kw == "BASE") { if (peek().kind != SparqlTokenKind.IRI) fail("expected an IRI after BASE"); next(); continue }
            val ns = next()
            if (ns.kind != SparqlTokenKind.PNAME || !ns.text.endsWith(":")) fail("expected a prefix name after PREFIX, found $ns")
            val iri = next()
            if (iri.kind != SparqlTokenKind.IRI) fail("expected an IRI for prefix ${ns.text}, found $iri")
            prefixes[ns.text.dropLast(1)] = iri.text.removeSurrounding("<", ">")
        }
        val form = next()
        if (form.kind != SparqlTokenKind.WORD) fail("expected SELECT or ASK, found $form")
        val ask = when (form.text.uppercase()) {
            "SELECT" -> false
            "ASK" -> true
            in SPARQL_UNSUPPORTED -> fail("${form.text.uppercase()} is not supported; the subset is SELECT and ASK")
            else -> fail("expected SELECT or ASK, found $form")
        }
        var distinct = false
        val items = ArrayList<SparqlItem>()
        var star = false
        if (!ask) {
            if (word("DISTINCT", "REDUCED")) { distinct = true; next() }
            while (!word("FROM", "WHERE") && !punct("{")) {
                when {
                    take("*") -> star = true
                    peek().kind == SparqlTokenKind.VAR -> next().text.substring(1).let { items += SparqlItem(ColumnRef(Identifier(it.toSeries())), it, false) }
                    take("(") -> {
                        val e = expression()
                        expectWord("AS")
                        val v = next()
                        if (v.kind != SparqlTokenKind.VAR) fail("expected a variable after AS, found $v")
                        expectPunct(")")
                        items += SparqlItem(e, v.text.substring(1), e.aggregated())
                    }
                    else -> fail("expected a variable, (expression AS ?name) or * in the select list, found ${peek()}")
                }
            }
            if (!star && items.isEmpty()) fail("empty select list")
        }
        val from = ArrayList<String>()
        val named = ArrayList<String>()
        while (word("FROM")) {
            next()
            val isNamed = word("NAMED").also { if (it) next() }
            val g = term() ?: fail("expected a graph IRI after FROM")
            if (g.isVariable) fail("a FROM graph must be an IRI")
            (if (isNamed) named else from) += g.text
        }
        if (word("WHERE")) next()
        group(null)
        var groupBy = ArrayList<Expr>()
        var having: Expr? = null
        val orderBy = ArrayList<OrderItem>()
        var limit: Int? = null
        var offset: Int? = null
        while (peek().kind != SparqlTokenKind.EOF) {
            when {
                word("GROUP") -> {
                    next(); expectWord("BY")
                    while (peek().kind == SparqlTokenKind.VAR || punct("(")) {
                        if (take("(")) {
                            val e = expression()
                            if (word("AS")) fail("GROUP BY (expression AS ?v) is not supported; group by a variable")
                            expectPunct(")")
                            groupBy += e
                        } else groupBy += ColumnRef(Identifier(next().text.substring(1).toSeries()))
                    }
                    if (groupBy.isEmpty()) fail("GROUP BY needs a variable")
                }
                word("HAVING") -> { next(); having = expression() }
                word("ORDER") -> {
                    next(); expectWord("BY")
                    while (true) {
                        val desc = word("DESC").also { if (it || word("ASC")) next() }
                        val e = when {
                            take("(") -> expression().also { expectPunct(")") }
                            peek().kind == SparqlTokenKind.VAR -> ColumnRef(Identifier(next().text.substring(1).toSeries()))
                            else -> break
                        }
                        orderBy += OrderItem(e, desc)
                    }
                    if (orderBy.isEmpty()) fail("ORDER BY needs a sort key")
                }
                word("LIMIT") -> { next(); limit = integer("LIMIT") }
                word("OFFSET") -> { next(); offset = integer("OFFSET") }
                else -> fail("unexpected ${peek()} after the WHERE clause")
            }
        }
        // A protocol dataset replaces the query's own, whole (SPARQL 1.1 Protocol 2.1.4); the query's missing FROM is Virtuoso's union.
        val protocol = defaultGraphUris.isNotEmpty() || namedGraphUris.isNotEmpty()
        val graphStage = QueryK.Graph(
            patterns.toSeries(),
            if (protocol) defaultGraphUris.map { "<$it>" } else from.ifEmpty { null },
            if (protocol) namedGraphUris.map { "<$it>" } else named.ifEmpty { null },
        )
        val bound = graphStage.variables()
        if (star && (groupBy.isNotEmpty() || items.any { it.aggregated })) fail("SELECT * with GROUP BY or an aggregate is not supported; name the variables")
        for (item in items) if (item.expr is ColumnRef && item.name !in bound) fail("variable ?${item.name} is not bound by the pattern")
        val variables = if (star || ask) bound else items.map { it.name }
        val where = filters.reduceOrNull { a, b -> BinaryExpr(a, "AND", b) }
        val columns: List<Column> = when {
            ask || star -> listOf(Column(LitExpr(StringLiteral("*".toSeries()))))
            else -> items.map { Column(it.expr, Identifier(it.name.toSeries())) }
        }
        if (distinct && groupBy.isEmpty() && items.none { it.aggregated }) {
            groupBy = ArrayList(variables.map { ColumnRef(Identifier(it.toSeries())) })
            if (star) fail("SELECT DISTINCT * is not supported; name the variables")
        }
        val select = SelectStmt(
            columns.toSeries(), TableRef(Identifier(QuadStore.RDF_QUAD.toSeries())), where,
            groupBy = groupBy.toSeries(), having = having, orderBy = orderBy.toSeries(),
            limit = if (ask) 1 else limit, offset = offset,
        )
        return SparqlQuery(src, ask, graphStage, select, variables)
    }

    /** `INSERT DATA { … }` and `DELETE DATA { … }`, `;`-separated, after the prologue. */
    fun update(): SparqlUpdate {
        while (word("PREFIX", "BASE")) {
            val kw = next().text.uppercase()
            if (kw == "BASE") { if (peek().kind != SparqlTokenKind.IRI) fail("expected an IRI after BASE"); next(); continue }
            val ns = next()
            if (ns.kind != SparqlTokenKind.PNAME || !ns.text.endsWith(":")) fail("expected a prefix name after PREFIX, found $ns")
            val iri = next()
            if (iri.kind != SparqlTokenKind.IRI) fail("expected an IRI for prefix ${ns.text}, found $iri")
            prefixes[ns.text.dropLast(1)] = iri.text.removeSurrounding("<", ">")
        }
        val operations = ArrayList<SparqlUpdate.Operation>()
        while (peek().kind != SparqlTokenKind.EOF) {
            val verb = next()
            val kind = verb.text.uppercase()
            if (verb.kind != SparqlTokenKind.WORD || kind !in setOf("INSERT", "DELETE")) {
                if (verb.kind == SparqlTokenKind.WORD && kind in setOf("WITH", "LOAD", "CLEAR", "CREATE", "DROP", "COPY", "MOVE", "ADD")) fail("$kind is not supported; the update subset is INSERT DATA and DELETE DATA")
                fail("expected INSERT DATA or DELETE DATA, found $verb")
            }
            if (!word("DATA")) fail("$kind … WHERE is not supported; the update subset is INSERT DATA and DELETE DATA")
            next()
            patterns.clear()
            group(null)
            if (filters.isNotEmpty()) fail("$kind DATA takes no FILTER")
            for (q in patterns) if (q.slots.any { it?.isVariable == true }) fail("$kind DATA takes no variables")
            operations += if (kind == "INSERT") SparqlUpdate.Operation.Insert(patterns.toList()) else SparqlUpdate.Operation.Delete(patterns.toList())
            if (!take(";")) break
        }
        if (peek().kind != SparqlTokenKind.EOF) fail("unexpected ${peek()} after the update")
        if (operations.isEmpty()) fail("empty update")
        return SparqlUpdate(src, operations)
    }

    fun integer(clause: String): Int {
        val t = next()
        return t.text.toIntOrNull()?.takeIf { t.kind == SparqlTokenKind.NUMBER && it >= 0 } ?: fail("$clause needs a non-negative integer, found $t")
    }

    /** `{ … }`: triple patterns, `GRAPH`, `FILTER` and nested groups, all under [g]. */
    fun group(g: PatternTerm?) {
        expectPunct("{")
        while (!punct("}")) {
            when {
                peek().kind == SparqlTokenKind.EOF -> fail("unterminated group: expected '}'")
                take(".") -> Unit
                word("GRAPH") -> {
                    next()
                    val slot = term() ?: fail("expected a variable or IRI after GRAPH")
                    group(slot)
                }
                word("FILTER") -> { next(); filters += if (punct("(")) { next(); expression().also { expectPunct(")") } } else primary() }
                punct("{") -> { group(g); if (word("UNION")) fail("UNION is not supported") }
                peek().kind == SparqlTokenKind.WORD && peek().text.uppercase() in SPARQL_UNSUPPORTED -> fail("${peek().text.uppercase()} is not supported")
                else -> triples(g)
            }
        }
        next()
    }

    /** `subject verb object (, object)* (; verb object…)*`. */
    fun triples(g: PatternTerm?) {
        val s = term() ?: fail("expected a subject, found ${peek()}")
        while (true) {
            val p = if (word("A")) { next(); PatternTerm(SparqlQuery.RDF_TYPE) } else term() ?: fail("expected a predicate, found ${peek()}")
            if (!p.isVariable && !p.text.startsWith("<")) fail("a predicate must be an IRI or a variable, found ${p.text}")
            do {
                val o = term() ?: fail("expected an object, found ${peek()}")
                patterns += TriplePattern(s, p, o, g)
            } while (take(","))
            if (!take(";")) break
            if (punct(".") || punct("}")) break
        }
    }

    /** A variable or constant term as a [PatternTerm]; null when the next token is not a term. */
    fun term(): PatternTerm? {
        val t = peek()
        val lexeme = when (t.kind) {
            SparqlTokenKind.VAR -> t.text
            SparqlTokenKind.IRI -> t.text
            SparqlTokenKind.PNAME -> expand(t.text)
            SparqlTokenKind.STRING -> literal(t.text)
            SparqlTokenKind.NUMBER -> number(t.text)
            SparqlTokenKind.WORD -> when (t.text.lowercase()) { "true", "false" -> "\"${t.text.lowercase()}\"^^${SparqlQuery.XSD_BOOLEAN}"; else -> return null }
            else -> return null
        }
        next()
        return PatternTerm(lexeme)
    }

    fun expand(pname: String): String {
        if (pname.startsWith("_:")) return pname
        val colon = pname.indexOf(':')
        if (colon < 0) fail("expected an IRI or a prefixed name, found '$pname'")
        val prefix = pname.substring(0, colon)
        val ns = prefixes[prefix] ?: fail("unknown prefix '$prefix:'")
        return "<" + ns + pname.substring(colon + 1) + ">"
    }

    /** A string token (`"lex"`, `"lex"@en`, `"lex"^^dt`) as its canonical lexeme, the datatype expanded. */
    fun literal(raw: String): String {
        val close = lexicalOf(raw).length + 1 // the closing quote; a `^^` inside the quotes is text
        val i = raw.indexOf("^^", close)
        if (i < 0) return raw
        val dt = raw.substring(i + 2)
        return raw.substring(0, i) + "^^" + (if (dt.startsWith("<")) dt else expand(dt))
    }

    fun number(text: String): String = "\"$text\"^^" + when {
        text.any { it == 'e' || it == 'E' } -> SparqlQuery.XSD_DOUBLE
        '.' in text -> SparqlQuery.XSD_DECIMAL
        else -> SparqlQuery.XSD_INTEGER
    }

    // ── expressions, lowered to the SQL AST ──────────────────────────────────────────────

    fun expression(): Expr = orExpr()

    fun orExpr(): Expr {
        var e = andExpr()
        while (take("||")) e = BinaryExpr(e, "OR", andExpr())
        return e
    }

    fun andExpr(): Expr {
        var e = relational()
        while (take("&&")) e = BinaryExpr(e, "AND", relational())
        return e
    }

    fun relational(): Expr {
        val left = additive()
        val op = peek().takeIf { it.kind == SparqlTokenKind.PUNCT && it.text in setOf("=", "!=", "<", "<=", ">", ">=") }?.text ?: return left
        next()
        val right = additive()
        val numeric = left.isNumeric() || right.isNumeric()
        return BinaryExpr(if (numeric) numeric(left) else left, if (op == "!=") "<>" else op, if (numeric) numeric(right) else right)
    }

    fun additive(): Expr {
        var e = multiplicative()
        while (punct("+") || punct("-")) { val op = next().text; e = BinaryExpr(numeric(e), op, numeric(multiplicative())) }
        return e
    }

    fun multiplicative(): Expr {
        var e = unary()
        while (punct("*") || punct("/")) { val op = next().text; e = BinaryExpr(numeric(e), op, numeric(unary())) }
        return e
    }

    fun unary(): Expr = when {
        take("!") -> Not(unary())
        take("-") -> BinaryExpr(LitExpr(NumericLiteral(0)), "-", numeric(unary()))
        take("+") -> numeric(unary())
        else -> primary()
    }

    fun primary(): Expr {
        val t = peek()
        return when (t.kind) {
            SparqlTokenKind.PUNCT -> if (take("(")) expression().also { expectPunct(")") } else fail("unexpected $t in an expression")
            SparqlTokenKind.VAR -> { next(); ColumnRef(Identifier(t.text.substring(1).toSeries())) }
            SparqlTokenKind.NUMBER -> {
            next()
            val value: Number = (if ('.' in t.text || 'e' in t.text || 'E' in t.text) t.text.toDoubleOrNull() else t.text.toLongOrNull()) ?: fail("number out of range: ${t.text}")
            LitExpr(NumericLiteral(value, numberText(t.text)))
        }
            SparqlTokenKind.STRING -> { next(); LitExpr(StringLiteral(literal(t.text).toSeries())) }
            SparqlTokenKind.IRI -> { next(); LitExpr(StringLiteral(t.text.toSeries())) }
            SparqlTokenKind.PNAME -> { next(); LitExpr(StringLiteral(expand(t.text).toSeries())) }
            SparqlTokenKind.WORD -> call()
            SparqlTokenKind.EOF -> fail("unexpected end of query in an expression")
        }
    }

    /** `true`, `false`, an aggregate, or one of the supported functions. */
    fun call(): Expr {
        val name = next().text
        val upper = name.uppercase()
        if (upper == "TRUE" || upper == "FALSE") return LitExpr(BooleanLiteral(upper == "TRUE"))
        expectPunct("(")
        val e: Expr = when (upper) {
            in SPARQL_AGGREGATES -> {
                if (word("DISTINCT")) fail("$upper(DISTINCT …) is not supported")
                val arg: Expr = if (take("*")) { if (upper != "COUNT") fail("$upper(*) is not an aggregate"); Star } else expression()
                Call(upper.lowercase(), s_[if (upper == "SUM" || upper == "AVG") numeric(arg) else arg])
            }
            "BOUND" -> Is(expression(), null, negated = true)
            "CONTAINS", "STRSTARTS", "STRENDS" -> {
                val subject = expression()
                expectPunct(",")
                val needle = ((expression() as? LitExpr)?.lit as? StringLiteral)?.asString() ?: fail("$upper needs a string literal")
                val lexical = lexicalOf(needle).replace("%", "\\%").replace("_", "\\_")
                val pattern = when (upper) { "CONTAINS" -> "%$lexical%"; "STRSTARTS" -> "\"$lexical%"; else -> "%$lexical\"%" }
                BinaryExpr(subject, "LIKE", LitExpr(StringLiteral(pattern.toSeries())))
            }
            else -> fail("function $name is not supported")
        }
        expectPunct(")")
        return e
    }

    /** A variable read as a number: the lexeme's lexical form cast to float8. */
    fun numeric(e: Expr): Expr = if (e is ColumnRef) Cast(e, "float8") else e

    fun Expr.isNumeric(): Boolean = when (this) {
        is LitExpr -> lit is NumericLiteral
        is Cast -> type == "float8"
        is BinaryExpr -> op in setOf("+", "-", "*", "/")
        else -> false
    }
}

// ── tokens ───────────────────────────────────────────────────────────────────────────────────

fun sparqlTokens(src: String): List<SparqlToken> {
    val out = ArrayList<SparqlToken>()
    var i = 0
    var line = 1
    fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'
    while (i < src.length) {
        val c = src[i]
        when {
            c == '\n' -> { line++; i++ }
            c.isWhitespace() -> i++
            c == '#' -> while (i < src.length && src[i] != '\n') i++
            c == '<' -> {
                val close = src.indexOf('>', i)
                val body = if (close > i) src.substring(i + 1, close) else null
                if (body != null && body.none { it.isWhitespace() || it == '<' || it == '"' || it == '{' || it == '}' || it == '|' || it == '^' || it == '`' || it == '\\' }) {
                    out += SparqlToken(SparqlTokenKind.IRI, src.substring(i, close + 1), line); i = close + 1
                } else if (src.startsWith("<=", i)) { out += SparqlToken(SparqlTokenKind.PUNCT, "<=", line); i += 2 }
                else { out += SparqlToken(SparqlTokenKind.PUNCT, "<", line); i++ }
            }
            c == '?' || c == '$' -> {
                var j = i + 1
                while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                if (j == i + 1) throw SparqlError("a variable needs a name", line)
                out += SparqlToken(SparqlTokenKind.VAR, "?" + src.substring(i + 1, j), line); i = j
            }
            c == '"' || c == '\'' -> {
                val quote = c
                val long = src.startsWith("$quote$quote$quote", i)
                val sb = StringBuilder("\"")
                var j = i + (if (long) 3 else 1)
                var closed = false
                while (j < src.length) {
                    val ch = src[j]
                    when {
                        ch == '\\' && j + 1 < src.length -> { sb.append(ch).append(src[j + 1]); j += 2 }
                        long && src.startsWith("$quote$quote$quote", j) -> { closed = true; j += 3; break }
                        !long && ch == quote -> { closed = true; j++; break }
                        ch == '\n' -> { if (!long) break; line++; sb.append("\\n"); j++ }
                        ch == '\t' -> { sb.append("\\t"); j++ }
                        ch == '\r' -> { sb.append("\\r"); j++ }
                        ch == '"' -> { sb.append("\\\""); j++ }
                        else -> { sb.append(ch); j++ }
                    }
                }
                if (!closed) throw SparqlError("unterminated string literal", line)
                sb.append('"')
                if (j < src.length && src[j] == '@') {
                    var k = j + 1
                    while (k < src.length && (src[k].isLetterOrDigit() || src[k] == '-')) k++
                    sb.append(src, j, k); j = k
                } else if (src.startsWith("^^", j)) {
                    var k = j + 2
                    if (k < src.length && src[k] == '<') { k = src.indexOf('>', k) + 1; if (k == 0) throw SparqlError("unterminated datatype IRI", line) }
                    else while (k < src.length && (isNameChar(src[k]) || src[k] == ':' || (src[k] == '.' && k + 1 < src.length && isNameChar(src[k + 1])))) k++
                    sb.append(src, j, k); j = k
                }
                out += SparqlToken(SparqlTokenKind.STRING, sb.toString(), line); i = j
            }
            c.isDigit() || ((c == '-' || c == '+' || c == '.') && i + 1 < src.length && src[i + 1].isDigit() &&
                (c == '.' || out.lastOrNull().let { it == null || it.kind == SparqlTokenKind.WORD || it.kind == SparqlTokenKind.IRI || it.kind == SparqlTokenKind.PNAME || (it.kind == SparqlTokenKind.PUNCT && it.text != ")") })) -> {
                var j = i + 1
                while (j < src.length && src[j].isDigit()) j++
                if (j < src.length && src[j] == '.' && j + 1 < src.length && src[j + 1].isDigit()) { j++; while (j < src.length && src[j].isDigit()) j++ }
                else if (c == '.') while (j < src.length && src[j].isDigit()) j++
                if (j < src.length && (src[j] == 'e' || src[j] == 'E')) {
                    var k = j + 1
                    if (k < src.length && (src[k] == '+' || src[k] == '-')) k++
                    if (k < src.length && src[k].isDigit()) { while (k < src.length && src[k].isDigit()) k++; j = k }
                }
                out += SparqlToken(SparqlTokenKind.NUMBER, src.substring(i, j).removePrefix("+"), line); i = j
            }
            c.isLetter() || c == '_' || c == ':' -> {
                var j = i
                while (j < src.length && isNameChar(src[j])) j++
                if (j < src.length && src[j] == ':') {
                    j++
                    while (j < src.length && (isNameChar(src[j]) || (src[j] == '.' && j + 1 < src.length && isNameChar(src[j + 1])))) j++
                    out += SparqlToken(SparqlTokenKind.PNAME, src.substring(i, j), line)
                } else out += SparqlToken(SparqlTokenKind.WORD, src.substring(i, j), line)
                i = j
            }
            else -> {
                val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                if (two in setOf("!=", ">=", "&&", "||", "^^")) { out += SparqlToken(SparqlTokenKind.PUNCT, two, line); i += 2 }
                else if (c in "{}().;,*=><!+-/") { out += SparqlToken(SparqlTokenKind.PUNCT, c.toString(), line); i++ }
                else throw SparqlError("unexpected character '$c'", line)
            }
        }
    }
    out += SparqlToken(SparqlTokenKind.EOF, "", line)
    return out
}
