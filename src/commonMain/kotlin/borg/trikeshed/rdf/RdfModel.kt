package borg.trikeshed.rdf

/**
 * RDF Triple / Quad model — commonMain.
 *
 * Subject always IRI or BlankNode, predicate IRI, object IRI|Literal|BlankNode.
 * Graph name in Quad is IRI (named graph) or null for default.
 */
sealed class RdfTerm {
    data class Iri(val iri: String) : RdfTerm()
    data class Literal(val lexical: String, val datatype: String? = null, val lang: String? = null) : RdfTerm()
    data class BlankNode(val id: String) : RdfTerm()

    fun toTurtle(): String = when (this) {
        is Iri -> "<$iri>"
        is BlankNode -> "_:$id"
        is Literal -> {
            val esc = lexical.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            when {
                lang != null -> "\"$esc\"@$lang"
                datatype != null -> "\"$esc\"^^<$datatype>"
                else -> "\"$esc\""
            }
        }
    }

    companion object {
        fun iri(s: String) = Iri(s)
        fun literal(s: String) = Literal(s)
        fun bnode(id: String) = BlankNode(id)
    }
}

data class RdfTriple(val s: RdfTerm, val p: RdfTerm.Iri, val o: RdfTerm) {
    fun toTurtle(): String = "${s.toTurtle()} ${p.toTurtle()} ${o.toTurtle()} ."
    fun toKif(): String = when {
        // simple heuristic: predicate local name becomes relation
        o is RdfTerm.Literal -> "(${p.iri.substringAfterLast('/') .substringAfterLast('#')} ${s.toKif()} \"${o.lexical}\")"
        else -> "(${p.iri.substringAfterLast('/') .substringAfterLast('#')} ${s.toKif()} ${o.toKif()})"
    }
}

data class RdfQuad(val s: RdfTerm, val p: RdfTerm.Iri, val o: RdfTerm, val g: RdfTerm.Iri?) {
    fun toTriple(): RdfTriple = RdfTriple(s, p, o)
    fun toTurtleWithGraph(): String = if (g != null) "${g.toTurtle()} { ${RdfTriple(s, p, o).toTurtle()} }" else RdfTriple(s, p, o).toTurtle()
}

private fun RdfTerm.toKif(): String = when (this) {
    is RdfTerm.Iri -> iri.substringAfterLast('/').substringAfterLast('#')
    is RdfTerm.Literal -> "\"$lexical\""
    is RdfTerm.BlankNode -> "?$id"
}

data class RdfGraph(val triples: List<RdfTriple>, val quads: List<RdfQuad> = emptyList()) {
    fun allTriples(): List<RdfTriple> = triples + quads.map { it.toTriple() }
    fun toTurtle(prefixes: Map<String, String> = emptyMap()): String = TurtleRdf.emit(this, prefixes)
}

/** Tiny helpers for well-known vocabs */
object RdfVocab {
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    const val XSD = "http://www.w3.org/2001/XMLSchema#"
    const val OWL = "http://www.w3.org/2002/07/owl#"
    const val SUMO = "http://www.ontologyportal.org/SUMO.owl#"
    const val OPENCYC = "http://sw.opencyc.org/concept/"
    const val FORGE = "https://trikeshed.borg/forge#"
    fun rdf(local: String) = RdfTerm.Iri(RDF + local)
    fun sumo(local: String) = RdfTerm.Iri(SUMO + local)
    fun forge(local: String) = RdfTerm.Iri(FORGE + local)
}
