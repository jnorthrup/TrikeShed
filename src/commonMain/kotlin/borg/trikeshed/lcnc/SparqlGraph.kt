package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.rdf.SparqlParser
import borg.trikeshed.rdf.SparqlQuery
import borg.trikeshed.rdf.SparqlTokenKind

/** A malformed visual query, naming the node responsible when one exists. */
class SparqlGraphError(val nodeId: String, val code: String, detail: String) :
    IllegalArgumentException("$code${if (nodeId.isEmpty()) "" else " at $nodeId"}: $detail")

/**
 * Typed authoring vocabulary for a deliberately small SPARQL SELECT: a list of projected
 * variables and a basic graph pattern. The result is a parsed query, not an LCNC procedure.
 * A missing graph on a pattern uses the engine's default dataset (currently the union of
 * graphs); a graph variable ranges over named graphs. No external data is read here.
 */
object SparqlGraph {
    const val SELECT = "sparql.select"
    const val VARIABLE = "sparql.variable"
    const val PATTERN = "sparql.pattern"
    const val QUERY = "SparqlQuery"
    const val PROJECTION = "SparqlProjection"
    const val TRIPLE = "SparqlPattern"

    private fun param(optional: Boolean = false) = LcncPortContract.LcncParamSpec(optional = optional)

    fun contracts(): List<LcncPortContract> = _l[
        LcncPortContract(SELECT, "SPARQL SELECT", _l["variables", "patterns"], _l["query"],
            inputKinds = _m["variables" j PROJECTION, "patterns" j TRIPLE], outputKinds = _m["query" j QUERY],
            cardinality = _m["variables" j LcncCardinality.MANY, "patterns" j LcncCardinality.MANY]),
        LcncPortContract(VARIABLE, "Projected variable", emptyList(), _l["variable"],
            outputKinds = _m["variable" j PROJECTION], params = _m["name" j param(), "ord" j param(optional = true)]),
        LcncPortContract(PATTERN, "Graph pattern", emptyList(), _l["pattern"],
            outputKinds = _m["pattern" j TRIPLE], params = _m["subject" j param(), "predicate" j param(),
                "object" j param(), "graph" j param(optional = true)])
    ]

    /**
     * Validate and compile a syntax-only graph. Projection order follows `ord` when given,
     * otherwise wire order. Every syntax node must feed the one SELECT exactly once.
     */
    fun lower(program: LcncProgram): SparqlQuery {
        if (!program.controls.inspectionOnly)
            throw SparqlGraphError("", "inspection-only", "a SPARQL syntax graph must not be dispatched as a procedure")
        val vocabulary = contracts().associateBy { it.type }
        LcncTypeCheck.check(program, vocabulary, strict = true).firstOrNull()?.let {
            throw SparqlGraphError(it.toNode, "type-check", it.render())
        }
        val nodes = program.nodes.view.toList()
        val wires = program.wires.view.toList()
        nodes.firstOrNull { it.children.size > 0 || it.subprogram != null }?.let {
            throw SparqlGraphError(it.id, "unsupported", "nested programs are not query syntax")
        }
        val roots = nodes.filter { it.type == SELECT }
        if (roots.size != 1) throw SparqlGraphError("", "root", "exactly one SELECT is required")
        val root = roots.single()
        if (wires.any { it.toNode != root.id }) throw SparqlGraphError(root.id, "wire", "every syntax wire must feed SELECT")
        val byId = nodes.associateBy { it.id }
        val incoming = wires.groupBy { it.toPort.removeSuffix("?") }
        val used = wires.map { it.fromNode }
        if (used.size != used.distinct().size) throw SparqlGraphError(root.id, "shared-node", "each syntax node feeds SELECT once")
        nodes.firstOrNull { it.id != root.id && it.id !in used }?.let {
            throw SparqlGraphError(it.id, "orphan-node", "node is not connected to SELECT")
        }
        val projection = incoming["variables"].orEmpty().map { byId.getValue(it.fromNode) }
        val patterns = incoming["patterns"].orEmpty().map { byId.getValue(it.fromNode) }
        if (projection.isEmpty() || patterns.isEmpty()) throw SparqlGraphError(root.id, "missing-input", "at least one variable and pattern are required")
        val ords = HashSet<Int>()
        val variables = projection.mapIndexed { i, n ->
            val name = n.params["name"].orEmpty().trim()
            val value = term(n, "name", name, variableOnly = true)
            val order = n.params["ord"]?.trim().orEmpty().let { raw ->
                if (raw.isEmpty()) Int.MAX_VALUE else raw.toIntOrNull()?.takeIf { it >= 0 && ords.add(it) }
                    ?: throw SparqlGraphError(n.id, "bad-order", "ord must be a unique nonnegative integer")
            }
            Triple(order, i, value)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        if (variables.distinct().size != variables.size) throw SparqlGraphError(root.id, "duplicate-variable", "project each variable once")
        val clauses = patterns.map { n ->
            fun slot(key: String, iriOrVariable: Boolean = false) = term(n, key, n.params[key].orEmpty().trim(), iriOrVariable = iriOrVariable)
            val s = slot("subject", true); val p = slot("predicate", true); val o = slot("object")
            val g = n.params["graph"]?.trim().orEmpty()
            val body = "$s $p $o ."
            if (g.isEmpty()) body else "GRAPH ${term(n, "graph", g, iriOrVariable = true)} { $body }"
        }
        val text = "SELECT ${variables.joinToString(" ")} WHERE { ${clauses.joinToString(" ")} }"
        return try { SparqlQuery.parse(text) } catch (e: IllegalArgumentException) {
            throw SparqlGraphError(root.id, "query", e.message ?: "invalid query")
        }
    }

    /** Parse one term independently so a value cannot inject extra syntax into the query. */
    private fun term(n: LcncNode, key: String, input: String, variableOnly: Boolean = false, iriOrVariable: Boolean = false): String {
        val value = try {
            val parser = SparqlParser(input)
            val value = parser.term() ?: throw IllegalArgumentException("expected one term")
            if (parser.peek().kind != SparqlTokenKind.EOF) throw IllegalArgumentException("extra syntax")
            value.text
        } catch (e: IllegalArgumentException) {
            throw SparqlGraphError(n.id, "bad-term", "$key: ${e.message ?: "invalid term"}")
        }
        // Query blank-node labels have existential scoping, but the engine currently
        // reads a blank label as a constant. Refuse them until that semantics exists.
        if (value.startsWith("_:")) throw SparqlGraphError(n.id, "bad-term", "$key cannot be a blank-node label")
        if (variableOnly && !value.startsWith("?")) throw SparqlGraphError(n.id, "bad-term", "$key must be a variable")
        if (iriOrVariable && !value.startsWith("?") && !value.startsWith("<"))
            throw SparqlGraphError(n.id, "bad-term", "$key must be an IRI or variable")
        return value
    }
}
