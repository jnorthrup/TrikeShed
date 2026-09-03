package borg.trikeshed.kif

/**
 * SUO-KIF — Standard Upper Ontology Knowledge Interchange Format (LISP-like S-expressions)
 *
 * Light parser/emitter for the SUMO/OpenCyc interchange. Full SUO-KIF is
 * first-order logic with arithmetic; this covers the fragment needed for
 * ontology projection: constants, variables (?X), relations, (and/or/not/implies),
 * quantifiers (forall/exists), and documentation axioms.
 *
 * Every KIF form is a `KifExpr` — the node serialization that Turtle/RDF also targets.
 */
sealed class KifExpr {
    data class Atom(val token: String) : KifExpr()
    data class Var(val name: String) : KifExpr() // ?X
    data class ListExpr(val elements: List<KifExpr>) : KifExpr()
    data class Quoted(val expr: KifExpr) : KifExpr()

    fun toKifString(): String = when (this) {
        is Atom -> token
        is Var -> name
        is Quoted -> "'${expr.toKifString()}"
        is ListExpr -> "(" + elements.joinToString(" ") { it.toKifString() } + ")"
    }

    companion object {
        /** Parse one KIF S-expression (balance parens, respect quoted strings). */
        fun parse(kif: String): KifExpr {
            val tokens = tokenize(kif.trim())
            val (expr, _) = parseTokens(tokens, 0)
            return expr
        }

        /** Parse all top-level forms in a .kif file string. */
        fun parseAll(kifFile: String): List<KifExpr> {
            val tokens = tokenize(kifFile)
            val out = mutableListOf<KifExpr>()
            var idx = 0
            while (idx < tokens.size) {
                if (tokens[idx].isBlank()) { idx++; continue }
                val (expr, next) = parseTokens(tokens, idx)
                out.add(expr)
                idx = next
            }
            return out
        }

        private fun tokenize(s: String): List<String> {
            val out = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                when (val c = s[i]) {
                    ' ', '\n', '\r', '\t' -> i++
                    '(' , ')' -> { out.add(c.toString()); i++ }
                    '"' -> {
                        val sb = StringBuilder("\"")
                        i++
                        while (i < s.length) {
                            val ch = s[i]
                            sb.append(ch)
                            if (ch == '\\') { if (i + 1 < s.length) sb.append(s[i + 1]); i += 2; continue }
                            if (ch == '"') { i++; break }
                            i++
                        }
                        out.add(sb.toString())
                    }
                    ';' -> { while (i < s.length && s[i] != '\n') i++ } // comment to EOL
                    else -> {
                        val sb = StringBuilder()
                        while (i < s.length && s[i] !in setOf(' ', '\n', '\r', '\t', '(', ')', '"', ';')) { sb.append(s[i]); i++ }
                        if (sb.isNotEmpty()) out.add(sb.toString())
                    }
                }
            }
            return out
        }

        private fun parseTokens(tokens: List<String>, start: Int): Pair<KifExpr, Int> {
            if (start >= tokens.size) return Atom("") to start
            val t = tokens[start]
            return when {
                t == "(" -> {
                    val elems = mutableListOf<KifExpr>()
                    var idx = start + 1
                    while (idx < tokens.size && tokens[idx] != ")") {
                        val (e, n) = parseTokens(tokens, idx)
                        elems.add(e)
                        idx = n
                    }
                    ListExpr(elems) to (idx + 1) // skip )
                }
                t.startsWith("?") -> Var(t) to (start + 1)
                t.startsWith("'") -> {
                    val inner = t.removePrefix("'")
                    val atom = if (inner.isEmpty() && start + 1 < tokens.size) {
                        val (e, n) = parseTokens(tokens, start + 1)
                        return Quoted(e) to n
                    } else Atom(inner)
                    Quoted(atom) to (start + 1)
                }
                else -> Atom(t) to (start + 1)
            }
        }
    }
}

/** Convenience: build (pred arg1 arg2 ...) */
fun kif(pred: String, vararg args: KifExpr): KifExpr.ListExpr =
    KifExpr.ListExpr(listOf(KifExpr.Atom(pred)) + args)

/** Light KIF knowledge base — asserts + simple forward chain for subclass/instance.
 *  Thread-safe: ONE bank is shared by the curator feeder (5s loop), HTTP teach,
 *  every kifSink lego (nal.mint/legal.ingest/read.construct), state.freeze's
 *  toKifFile, and the evidence/SPARQL readers. Exact-duplicate assertions are
 *  dropped (the SUMO spine re-asserts on every thaw otherwise). */
class KifKnowledgeBase {
    private val gate = Any()
    private val asserts: MutableList<KifExpr> = mutableListOf()
    private val seen = HashSet<String>()

    fun assert(expr: KifExpr) {
        borg.trikeshed.isam.synchronizedLock(gate) {
            if (seen.add(expr.toKifString())) asserts.add(expr)
        }
    }
    fun assertKif(kif: String) { assert(KifExpr.parse(kif)) }

    /**
     * Forget one assertion — the exact string [assert] deduped on — so a
     * projection that is retracted upstream (a Rete fact retracted or modified,
     * see [borg.trikeshed.dag.KifTee]) leaves the bank instead of accumulating
     * forever. Returns true when the expression was present. [query] semantics
     * are untouched: the subclass closure is recomputed from what remains.
     */
    fun retract(expr: KifExpr): Boolean = borg.trikeshed.isam.synchronizedLock(gate) {
        val key = expr.toKifString()
        if (!seen.remove(key)) return@synchronizedLock false
        val at = asserts.indexOfFirst { it.toKifString() == key }
        if (at >= 0) asserts.removeAt(at)
        true
    }
    fun retractKif(kif: String): Boolean = retract(KifExpr.parse(kif))
    fun asserts(): List<KifExpr> = borg.trikeshed.isam.synchronizedLock(gate) { asserts.toList() }
    /** Distinct assertions currently held. */
    fun size(): Int = borg.trikeshed.isam.synchronizedLock(gate) { asserts.size }

    fun toKifFile(): String = asserts().joinToString("\n") { it.toKifString() }

    /** Minimal solver: subclass/instance closure + (and ...) grounding. Light effort — not a full FOL prover. */
    fun query(pattern: KifExpr): List<Map<String, String>> {
        // pattern is a KIF list with Vars, e.g. (subclass ?X Physical)
        // brute-force unify against asserts
        val bindings = mutableListOf<Map<String, String>>()
        for (a in asserts()) {
            unify(pattern, a)?.let { bindings.add(it) }
        }
        // also transitively chase subclass
        if (pattern is KifExpr.ListExpr && pattern.elements.firstOrNull() is KifExpr.Atom) {
            val pred = (pattern.elements[0] as KifExpr.Atom).token
            if (pred == "subclass") {
                val closure = subclassClosure()
                for ((child, parent) in closure) {
                    val cand = kif("subclass", KifExpr.Atom(child), KifExpr.Atom(parent))
                    unify(pattern, cand)?.let { bindings.add(it) }
                }
            }
        }
        return bindings
    }

    private fun subclassClosure(): Set<Pair<String, String>> {
        val edges = asserts().mapNotNull { e ->
            (e as? KifExpr.ListExpr)?.let {
                if (it.elements.size == 3 && (it.elements[0] as? KifExpr.Atom)?.token == "subclass") {
                    val a = (it.elements[1] as? KifExpr.Atom)?.token ?: return@mapNotNull null
                    val b = (it.elements[2] as? KifExpr.Atom)?.token ?: return@mapNotNull null
                    a to b
                } else null
            }
        }
        val closure = edges.toMutableSet()
        var changed = true
        while (changed) {
            changed = false
            for ((a, b) in closure.toList()) for ((c, d) in closure.toList()) if (b == c && a to d !in closure) { closure.add(a to d); changed = true }
        }
        return closure
    }

    private fun unify(pattern: KifExpr, fact: KifExpr): Map<String, String>? {
        val map = mutableMapOf<String, String>()
        fun go(p: KifExpr, f: KifExpr): Boolean = when {
            p is KifExpr.Var -> {
                val v = p.name
                val fv = when (f) { is KifExpr.Atom -> f.token; is KifExpr.Var -> f.name; else -> f.toKifString() }
                val prev = map[v]
                if (prev != null) prev == fv else { map[v] = fv; true }
            }
            p is KifExpr.Atom && f is KifExpr.Atom -> p.token == f.token
            p is KifExpr.ListExpr && f is KifExpr.ListExpr -> p.elements.size == f.elements.size && p.elements.zip(f.elements).all { (a, b) -> go(a, b) }
            else -> false
        }
        return if (go(pattern, fact)) map else null
    }

    fun sparqlSelectSparqlLike(pattern: String): String {
        // light SPARQL-ish: pattern is KIF var pattern string; return bindings as JSON
        val expr = runCatching { KifExpr.parse(pattern) }.getOrElse { return """{"error":"parse_failed"}""" }
        val rows = query(expr)
        return buildString {
            append("{\"bindings\":[")
            rows.forEachIndexed { i, m -> if (i > 0) append(","); append("{"); m.entries.forEachIndexed { j, (k, v) -> if (j > 0) append(","); append("\"$k\":\"$v\"") }; append("}") }
            append("]}")
        }
    }
}
