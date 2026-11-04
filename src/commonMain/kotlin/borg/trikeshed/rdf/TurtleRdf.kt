package borg.trikeshed.rdf

/**
 * Turtle RDF emitter/parser — light commonMain subset.
 *
 * Emits: @prefix lines + triples as "s p o .". Parser handles prefix expansion,
 * <> IRIs, "" literals, and blank nodes. Full Turtle (collections, ; , shorthands)
 * is out of scope for light effort — we aim for *Turtle-closest node serialization*:
 * every Cursor row / Series element can round-trip via this Turtle form.
 *
 * Design choice (node serialization strategy): Turtle is the closest RDF syntax to
 * a *node* (row) — one subject block with many predicate-object pairs maps 1:1 to a
 * RowVec (columns → predicates, cells → objects). Quads add graph as TrikeShed subject
 * domain (forge board, jules surface, etc.).
 * [parseStrict] is the separately bounded KG data profile; the default APIs remain permissive.
 */
object TurtleRdf {

    /**
     * Bounded KG data profile, not full Turtle: absolute IRIs, simple blank labels, quoted
     * literals, ;/, lists and leading own-line @prefix declarations. Relative/base IRIs,
     * graphs, collections, variables and bare numeric tokens refuse. No query is executed.
     * The existing permissive [parse] and [parseTerm] entry points retain their behavior.
     */
    fun parseStrict(turtle: String): RdfGraph {
        require(turtle.length <= 65536) { "Strict Turtle input exceeds 65536 characters" }
        turtle.encodeToByteArray(throwOnInvalidSequence = true)
        val source = structuralLines(turtle)
        val prefixes = defaultPrefixes().toMutableMap()
        val directive = Regex("""@prefix\s+([A-Za-z_][A-Za-z0-9_-]*)?:\s*<([^<>]*)>\s*\.\s*(?:#.*)?""")
        var at = 0
        var declarations = 0
        // Only header lines are removed: @prefix-looking text inside later literals is data.
        while (at < source.length) {
            val end = source.indexOf('\n', at).let { if (it < 0) source.length else it }
            val line = source.substring(at, end).trim()
            if (line.isNotEmpty() && !line.startsWith('#') && !line.startsWith("@prefix")) break
            if (line.startsWith("@prefix")) {
                require(++declarations <= 128) { "Strict Turtle prefix limit" }
                val match = requireNotNull(directive.matchEntire(line)) { "Use a complete own-line @prefix declaration" }
                val namespace = requireNotNull(readTerm("<${match.groupValues[2]}>", prefixes, true) as? RdfTerm.Iri) {
                    "Prefix namespace must be an absolute IRI"
                }
                prefixes[match.groupValues[1]] = namespace.iri
            }
            at = if (end == source.length) end else end + 1
        }
        val parser = SparqlParser(source.substring(at))
        require(parser.tokens.size <= 8192) { "Strict Turtle token limit" }
        parser.prefixes.putAll(prefixes)
        fun constant(): RdfTerm {
            val token = parser.peek()
            require(token.kind != SparqlTokenKind.VAR && token.kind != SparqlTokenKind.NUMBER) {
                "Variables and bare numeric tokens are outside strict Turtle data"
            }
            require(token.kind != SparqlTokenKind.WORD || token.text == "true" || token.text == "false") {
                "Expected an RDF constant, not ${token.text}"
            }
            val value = requireNotNull(parser.term()) { "Expected a complete RDF term" }
            return requireNotNull(readTerm(value.text, parser.prefixes, true)) { "Invalid RDF term" }
        }
        val triples = mutableListOf<RdfTriple>()
        while (parser.peek().kind != SparqlTokenKind.EOF) {
            val subject = constant()
            require(subject is RdfTerm.Iri || subject is RdfTerm.BlankNode) { "RDF subject cannot be a literal" }
            while (true) {
                val predicate = if (parser.peek().kind == SparqlTokenKind.WORD && parser.peek().text == "a") {
                    parser.next(); RdfVocab.rdf("type")
                } else requireNotNull(constant() as? RdfTerm.Iri) { "RDF predicate must be an IRI" }
                do {
                    require(triples.size < 2048) { "Strict Turtle triple limit" }
                    triples.add(RdfTriple(subject, predicate, constant()))
                } while (parser.take(","))
                if (!parser.take(";")) break
                if (parser.punct(".")) break
            }
            parser.expectPunct(".")
        }
        return RdfGraph(triples)
    }

    /** Check original short spelling, preserve long literal bytes, normalize only structural EOLs. */
    private fun structuralLines(text: String): String = buildString(text.length) {
        var quote: Char? = null
        var long = false
        var escaped = false
        var iri = false
        var comment = false
        var at = 0
        while (at < text.length) {
            val ch = text[at]
            val q = quote
            when {
                comment -> {
                    if (ch == '\n' || ch == '\r') {
                        comment = false; append('\n')
                        at += if (ch == '\r' && at + 1 < text.length && text[at + 1] == '\n') 2 else 1
                    } else { append(ch); at++ }
                }
                iri -> { append(ch); if (ch == '>') iri = false; at++ }
                q != null -> {
                    require(long || ch != '\r' && ch != '\n') { "Raw CR/LF is invalid in a short RDF literal" }
                    when {
                        escaped -> { append(ch); escaped = false; at++ }
                        ch == '\\' -> { append(ch); escaped = true; at++ }
                        long && text.startsWith("$q$q$q", at) -> { append("$q$q$q"); quote = null; at += 3 }
                        !long && ch == q -> { append(ch); quote = null; at++ }
                        else -> { append(ch); at++ }
                    }
                }
                ch == '\r' || ch == '\n' -> {
                    append('\n')
                    at += if (ch == '\r' && at + 1 < text.length && text[at + 1] == '\n') 2 else 1
                }
                ch == '#' -> { append(ch); comment = true; at++ }
                ch == '<' -> { append(ch); iri = true; at++ }
                ch == '\'' || ch == '"' -> {
                    quote = ch; long = text.startsWith("$ch$ch$ch", at)
                    append(if (long) "$ch$ch$ch" else ch.toString())
                    at += if (long) 3 else 1
                }
                else -> { append(ch); at++ }
            }
        }
    }

    /** Emit graph as Turtle. */
    fun emit(graph: RdfGraph, prefixes: Map<String, String> = defaultPrefixes()): String = buildString {
        for ((pfx, ns) in prefixes) appendLine("@prefix $pfx: <$ns> .")
        if (prefixes.isNotEmpty()) appendLine()
        for (t in graph.triples) appendLine(t.toTurtle())
        for (q in graph.quads) {
            if (q.g != null) appendLine("${q.g.toTurtle()} { ${RdfTriple(q.s, q.p, q.o).toTurtle()} }")
            else appendLine(RdfTriple(q.s, q.p, q.o).toTurtle())
        }
    }

    fun defaultPrefixes(): Map<String, String> = mapOf(
        "rdf" to RdfVocab.RDF,
        "rdfs" to RdfVocab.RDFS,
        "xsd" to RdfVocab.XSD,
        "owl" to RdfVocab.OWL,
        "sumo" to RdfVocab.SUMO,
        "cyc" to RdfVocab.OPENCYC,
        "forge" to RdfVocab.FORGE,
    )

    /** Parse light Turtle (ignores @prefix, parses <>, "" , _:). */
    fun parse(turtle: String): RdfGraph {
        val prefixes = mutableMapOf<String, String>()
        prefixes.putAll(defaultPrefixes())
        val triples = mutableListOf<RdfTriple>()
        val quads = mutableListOf<RdfQuad>()
        // collect prefix decls
        val prefixRe = Regex("""@prefix\s+(\w+):\s+<([^>]+)>\s*\.""")
        for (m in prefixRe.findAll(turtle)) prefixes[m.groupValues[1]] = m.groupValues[2]
        // strip prefix lines for triple parse
        val body = turtle.lines().filter { !it.trim().startsWith("@prefix") }.joinToString("\n")
        // very light triple regex: <s> <p> <o> . or _:b <p> "..." .
        // A literal may contain ESCAPED quotes and backslashes — the emitter
        // writes `\"` and `\\` (RdfTerm.toTurtle) — so the literal token is
        // "any run of non-quote non-backslash chars or backslash-anything".
        // `"[^"]*"` stopped at the first escaped quote and silently lost the
        // triple, which is how a prompt containing a quote vanished on the
        // canvas's "apply" round trip.
        val tripleRe = Regex("""([<][^>]+>|[_\w:][^\s]*)\s+([<][^>]+>|[_\w:][^\s]*)\s+([<][^>]+>|"(?:[^"\\]|\\.)*"(@\w+|\^\^<[^>]+>)?|_:\w+)\s*\.""")
        // also handle quads: GRAPH { s p o . }
        val quadRe = Regex("""([<][^>]+>)\s*\{\s*([^}]+)\}""")
        for (qm in quadRe.findAll(body)) {
            val g = parseTerm(qm.groupValues[1], prefixes) as? RdfTerm.Iri ?: continue
            val inner = qm.groupValues[2]
            for (tm in tripleRe.findAll(inner)) {
                val s = parseTerm(tm.groupValues[1], prefixes)
                val p = parseTerm(tm.groupValues[2], prefixes)
                val o = parseTerm(tm.groupValues[3], prefixes)
                if (s != null && p is RdfTerm.Iri && o != null) quads.add(RdfQuad(s, p, o, g))
            }
        }
        // default-graph triples (outside quads)
        val bodyNoQuads = quadRe.replace(body, "")
        for (tm in tripleRe.findAll(bodyNoQuads)) {
            val s = parseTerm(tm.groupValues[1], prefixes) ?: continue
            val p = parseTerm(tm.groupValues[2], prefixes) as? RdfTerm.Iri ?: continue
            val o = parseTerm(tm.groupValues[3], prefixes) ?: continue
            triples.add(RdfTriple(s, p, o))
        }
        return RdfGraph(triples, quads)
    }

    /**
     * SPARQL basic graph pattern over [graph]: `PREFIX p: <ns>` declarations, then `WHERE { … }`
     * holding `s p o` patterns ended by `.`. Terms are `?var`, `<iri>`, `prefix:local`, `a`, or a
     * literal. Patterns join on shared variables, left to right. Rows bind only `SELECT ?v …`
     * variables (every variable for `SELECT *` or no SELECT).
     */
    fun select(graph: RdfGraph, sparql: String): List<Map<String, RdfTerm>> {
        val prefixes = defaultPrefixes().toMutableMap()
        for (m in Regex("""PREFIX\s+(\w*):\s*<([^>]+)>""", RegexOption.IGNORE_CASE).findAll(sparql)) prefixes[m.groupValues[1]] = m.groupValues[2]
        val where = Regex("""\{([\s\S]*)\}""").find(sparql)?.groupValues?.get(1) ?: sparql
        val token = Regex("""<[^>]*>|"(?:[^"\\]|\\.)*"(?:@\w+|\^\^(?:<[^>]+>|\w*:\w+))?|\?\w+|[^\s.;]+(?:\.[^\s.;]+)*|\.""")
        val patterns = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (m in token.findAll(where)) {
            if (m.value == ".") { if (cur.isNotEmpty()) patterns.add(cur); cur = ArrayList(); continue }
            cur.add(m.value)
            if (cur.size == 3) { patterns.add(cur); cur = ArrayList() }
        }
        fun term(t: String): RdfTerm? = when {
            t.startsWith("?") -> null
            t == "a" -> RdfVocab.rdf("type")
            else -> parseTerm(t, prefixes)
        }
        val compiled = patterns.filter { it.size == 3 }.map { p -> p.map { if (it.startsWith("?")) it else null } to p.map(::term) }
        val triples = graph.allTriples()
        var rows: List<Map<String, RdfTerm>> = listOf(emptyMap())
        for ((vars, consts) in compiled) {
            rows = rows.flatMap { row ->
                triples.mapNotNull { t ->
                    val parts = arrayOf(t.s, t.p, t.o)
                    val out = HashMap(row)
                    for (i in 0..2) {
                        val v = vars[i]
                        if (v == null) { if (consts[i] != parts[i]) return@mapNotNull null }
                        else { val b = out[v]; if (b == null) out[v] = parts[i] else if (b != parts[i]) return@mapNotNull null }
                    }
                    out
                }
            }
        }
        val head = Regex("""SELECT\s+([\s\S]*?)\s+WHERE""", RegexOption.IGNORE_CASE).find(sparql)?.groupValues?.get(1)
            ?.split(Regex("\\s+"))?.filter { it.startsWith("?") }?.takeIf { it.isNotEmpty() }
        return if (head == null) rows else rows.map { r -> head.mapNotNull { v -> r[v]?.let { v to it } }.toMap() }
    }

    /**
     * One term from its Turtle / SPARQL spelling: `<iri>`, `_:b`, `"lex"`, `"lex"@lang`, `"lex"^^<dt>` or
     * `"lex"^^prefix:local`, `prefix:local` (expanded through [prefixes], kept as written when the
     * prefix is unknown), else a bare IRI. Null for an unterminated literal.
     */
    fun parseTerm(raw: String, prefixes: Map<String, String> = emptyMap()): RdfTerm? = readTerm(raw, prefixes, false)

    private fun readTerm(raw: String, prefixes: Map<String, String>, strict: Boolean): RdfTerm? {
        val t = raw.trim()
        return when {
            t.startsWith("<") && t.endsWith(">") -> {
                val iri = t.removePrefix("<").removeSuffix(">")
                if (strict) require(Regex("[A-Za-z][A-Za-z0-9+.-]*:.*").matches(iri) &&
                    iri.none { it.code <= 32 || it in "<>\"{}|^`\\" }) { "Expected an absolute supported IRI" }
                RdfTerm.Iri(iri)
            }
            t.startsWith("_:") -> {
                val id = t.removePrefix("_:")
                if (strict) require(Regex("[A-Za-z0-9_](?:[A-Za-z0-9._-]*[A-Za-z0-9_-])?").matches(id)) { "Unsupported blank-node label" }
                RdfTerm.BlankNode(id)
            }
            t.startsWith("\"") -> {
                // literal with optional lang/datatype: find the closing quote
                // past any escapes, then unescape in ONE pass — sequential
                // replaces turned `\\n` (an escaped backslash then an n) into a
                // newline.
                val sb = StringBuilder()
                var i = 1
                var closed = false
                while (i < t.length) {
                    val ch = t[i]
                    when {
                        ch == '"' -> { closed = true; break }
                        ch == '\\' && i + 1 < t.length -> {
                            when (val e = t[i + 1]) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'b', 'f' -> if (strict) sb.append(if (e == 'b') '\b' else '\u000c') else sb.append(e)
                                'u', 'U' -> if (!strict) {
                                    if (e == 'u' && i + 5 < t.length) { sb.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                                    else sb.append(e)
                                } else {
                                    val count = if (e == 'u') 4 else 8
                                    require(i + count + 1 < t.length) { "Truncated RDF Unicode escape" }
                                    val digits = t.substring(i + 2, i + count + 2)
                                    require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid RDF Unicode escape" }
                                    val code = digits.toLongOrNull(16)
                                    require(code != null && code in 0..0x10ffff && code !in 0xd800..0xdfff) { "RDF escape must name a Unicode scalar" }
                                    if (code <= 0xffff) sb.append(code.toInt().toChar()) else {
                                        val rest = code.toInt() - 0x10000
                                        sb.append((0xd800 + (rest ushr 10)).toChar()).append((0xdc00 + (rest and 1023)).toChar())
                                    }
                                    i += count
                                }
                                else -> { require(!strict || e in "\"'\\") { "Unsupported RDF escape" }; sb.append(e) }
                            }
                            i += 2; continue
                        }
                        else -> { require(!strict || ch.code >= 32) { "Unescaped RDF control character" }; sb.append(ch) }
                    }
                    i++
                }
                if (!closed) return null
                val lex = sb.toString()
                if (strict) lex.encodeToByteArray(throwOnInvalidSequence = true)
                val rest = t.substring(i + 1).trim()
                when {
                    rest.startsWith("@") -> {
                        val language = rest.removePrefix("@")
                        require(!strict || Regex("[A-Za-z]+(?:-[A-Za-z0-9]+)*").matches(language)) { "Invalid RDF language tag" }
                        RdfTerm.Literal(lex, lang = language)
                    }
                    rest.startsWith("^^") -> {
                        val dt = rest.removePrefix("^^")
                        val datatype = if (strict) requireNotNull((readTerm(dt, prefixes, true) as? RdfTerm.Iri)?.iri) {
                            "RDF datatype must be an absolute IRI"
                        }
                        else if (dt.startsWith("<")) dt.removeSurrounding("<", ">")
                        else if (':' in dt) dt.split(":", limit = 2).let { (pfx, local) -> prefixes[pfx]?.let { ns -> ns + local } ?: dt }
                        else dt
                        RdfTerm.Literal(lex, datatype = datatype)
                    }
                    else -> { require(!strict || rest.isEmpty()) { "Invalid RDF literal suffix" }; RdfTerm.Literal(lex) }
                }
            }
            t.contains(":") -> {
                val (pfx, local) = t.split(":", limit = 2)
                val ns = prefixes[pfx] ?: run { require(!strict) { "Undefined RDF prefix" }; return RdfTerm.Iri(t) }
                if (strict) readTerm("<$ns$local>", prefixes, true) else RdfTerm.Iri(ns + local)
            }
            else -> { require(!strict) { "Expected an explicit RDF IRI" }; RdfTerm.Iri(t) }
        }
    }
}
