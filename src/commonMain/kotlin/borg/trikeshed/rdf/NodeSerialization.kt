package borg.trikeshed.rdf

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get

/**
 * Turtle RDF as *node* serialization strategy.
 *
 * Closest RDF syntax to a TrikeShed node is Turtle's subject block:
 *   <row/7> forge:col0 "val" ; forge:col1 "val" .
 * Each logical row → one Turtle subject with predicate-object pairs.
 *
 * Isomorphism: node ↔ RdfGraph ↔ Turtle text ↔ KIF.
 */
object NodeSerialization {

    fun <T> seriesToTurtle(series: Series<T>, base: String = "https://trikeshed.borg/forge/item/"): String = buildString {
        append("@prefix forge: <${RdfVocab.FORGE}> .\n\n")
        for (i in 0 until series.size) {
            val subj = RdfTerm.Iri("$base$i")
            val obj = RdfTerm.Literal(series[i].toString())
            append(RdfTriple(subj, RdfTerm.Iri("${RdfVocab.FORGE}index"), RdfTerm.Literal(i.toString())).toTurtle() + "\n")
            append(RdfTriple(subj, RdfTerm.Iri("${RdfVocab.FORGE}value"), obj).toTurtle() + "\n")
        }
    }

    fun mapRowsToTurtle(rows: List<Map<String, String>>, base: String = "https://trikeshed.borg/forge/row/"): String = buildString {
        append("@prefix forge: <${RdfVocab.FORGE}> .\n\n")
        for ((ri, row) in rows.withIndex()) {
            val subj = RdfTerm.BlankNode("r$ri")
            val pairs = row.entries.map { (k, v) ->
                val pred = RdfTerm.Iri(RdfVocab.FORGE + k)
                val obj = RdfTerm.Literal(v)
                "${pred.toTurtle()} ${obj.toTurtle()}"
            }
            if (pairs.isEmpty()) append("${subj.toTurtle()} .\n")
            else append("${subj.toTurtle()} " + pairs.joinToString(" ;\n  ") + " .\n")
        }
    }

    fun turtleToRows(turtle: String): List<Map<String, String>> {
        val g = TurtleRdf.parse(turtle)
        return g.allTriples().groupBy { it.s }.map { (_, triples) ->
            triples.associate { it.p.iri.substringAfterLast('/').substringAfterLast('#') to (it.o as? RdfTerm.Literal)?.lexical.orEmpty() }
        }
    }
}
