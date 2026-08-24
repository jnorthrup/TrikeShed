package borg.trikeshed.rdf

/**
 * Confix CSV ↔ RDF (triples, quads, Turtle)
 *
 * Confix is TrikeShed's content-addressed tab-sep / CSV-like row store.
 * This adaptor projects CSV rows → RDF via Turtle node serialization.
 * Quads: graph IRI partitions by dataset (Confix doc id / CSV path).
 */
object ConfixRdfAdapter {

    fun csvToGraph(
        csvText: String,
        graphIri: String? = null,
        delimiter: Char = ',',
    ): RdfGraph {
        val lines = csvText.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return RdfGraph(emptyList())
        val header = lines[0].split(delimiter).map { it.trim().trim('"') }
        val triples = mutableListOf<RdfTriple>()
        val quads = mutableListOf<RdfQuad>()
        val graph = graphIri?.let { RdfTerm.Iri(it) }
        for ((ri, line) in lines.drop(1).withIndex()) {
            val cells = line.split(delimiter).map { it.trim().trim('"') }
            val subj = RdfTerm.BlankNode("r$ri")
            for ((ci, col) in header.withIndex()) {
                val pred = RdfTerm.Iri(RdfVocab.FORGE + col.ifEmpty { "col$ci" })
                val obj = RdfTerm.Literal(cells.getOrNull(ci) ?: "")
                if (graph != null) quads.add(RdfQuad(subj, pred, obj, graph)) else triples.add(RdfTriple(subj, pred, obj))
            }
        }
        return RdfGraph(triples, quads)
    }

    fun graphToCsv(graph: RdfGraph): String {
        val all = graph.allTriples()
        val preds = all.map { it.p.iri.substringAfterLast('/').substringAfterLast('#') }.distinct().sorted()
        val bySubject = all.groupBy { it.s }
        val sb = StringBuilder()
        sb.appendLine(preds.joinToString(","))
        for ((_, triples) in bySubject) {
            val byPred = triples.associate { it.p.iri.substringAfterLast('/').substringAfterLast('#') to (it.o as? RdfTerm.Literal)?.lexical.orEmpty() }
            sb.appendLine(preds.joinToString(",") { "\"${byPred[it]?.replace("\"", "\"\"") ?: ""}\"" })
        }
        return sb.toString()
    }

    fun confixToTurtle(confixBytes: ByteArray, graphIri: String? = null): String {
        return csvToGraph(confixBytes.decodeToString(), graphIri = graphIri).toTurtle()
    }

    fun turtleToCsv(turtle: String): String = graphToCsv(TurtleRdf.parse(turtle))
}

private fun RdfGraph.toTurtle(): String = TurtleRdf.emit(this)
