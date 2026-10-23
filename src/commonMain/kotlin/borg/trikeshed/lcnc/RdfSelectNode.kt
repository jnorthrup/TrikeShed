package borg.trikeshed.lcnc

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.*

/**
 * `rdf.select`: a SPARQL query over Turtle, planned by [SparqlQuery] over a [QuadStore] built from the
 * cable's (or the param's) Turtle. Rows are variable → term in the shape the canvas has always read:
 * a literal's lexical form bare, an IRI in its `<…>` spelling, a blank node as `_:id`, an aggregate as
 * its number. commonMain, so the node runs wherever the LCNC runner does.
 */
object RdfSelectNode {
    const val TYPE = "rdf.select"

    val runner = LcncNodeRunner { node, inputs ->
        val turtle = ((inputs["turtle"] ?: inputs["turtle?"])?.toString() ?: node.params["turtle"]).orEmpty()
        val sparql = ((inputs["sparql"] ?: inputs["sparql?"])?.toString() ?: node.params["sparql"])?.takeIf { it.isNotBlank() }
            ?: error("rdf.select: no sparql")
        val rows = select(turtle, sparql)
        mapOf("rows" to rows, "count" to rows.size)
    }

    /** The solutions of [sparql] over [turtle], each variable bound in a row (an unbound variable is absent). */
    fun select(turtle: String, sparql: String): List<Map<String, String>> {
        val query = SparqlQuery.parse(sparql)
        val result = query.plan(QuadStore().load(turtle).scan())
        return List(result.size) { r ->
            val cells = result[r].values
            query.variables.indices.mapNotNull { c -> cells[c]?.let { query.variables[c] to text(it) } }.toMap()
        }
    }

    /** A cell in the row shape: a literal bare, an IRI as `<iri>`, a blank node as `_:id`. */
    fun text(cell: Any): String = SparqlResults.termOf(cell)!!.let { term ->
        when (term.type) {
            "uri" -> "<${term.value}>"
            "bnode" -> "_:${term.value}"
            else -> term.value
        }
    }
}
