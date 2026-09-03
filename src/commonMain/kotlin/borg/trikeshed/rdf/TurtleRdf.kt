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
 */
object TurtleRdf {

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

    private fun parseTerm(raw: String, prefixes: Map<String, String>): RdfTerm? {
        val t = raw.trim()
        return when {
            t.startsWith("<") && t.endsWith(">") -> RdfTerm.Iri(t.removePrefix("<").removeSuffix(">"))
            t.startsWith("_:") -> RdfTerm.BlankNode(t.removePrefix("_:"))
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
                                'u' -> if (i + 5 < t.length) { sb.append(t.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else sb.append(e)
                                else -> sb.append(e)
                            }
                            i += 2; continue
                        }
                        else -> sb.append(ch)
                    }
                    i++
                }
                if (!closed) return null
                val lex = sb.toString()
                val rest = t.substring(i + 1).trim()
                when {
                    rest.startsWith("@") -> RdfTerm.Literal(lex, lang = rest.removePrefix("@"))
                    rest.startsWith("^^") -> RdfTerm.Literal(lex, datatype = rest.removePrefix("^^").removeSurrounding("<", ">"))
                    else -> RdfTerm.Literal(lex)
                }
            }
            t.contains(":") -> {
                val (pfx, local) = t.split(":", limit = 2)
                val ns = prefixes[pfx] ?: return RdfTerm.Iri(t)
                RdfTerm.Iri(ns + local)
            }
            else -> RdfTerm.Iri(t)
        }
    }
}
