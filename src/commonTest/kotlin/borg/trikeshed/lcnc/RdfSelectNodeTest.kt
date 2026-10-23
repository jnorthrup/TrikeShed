package borg.trikeshed.lcnc

import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.SparqlQueryTest
import borg.trikeshed.rdf.TurtleRdf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** `rdf.select` runs in commonMain over the SPARQL engine and keeps the row shape the canvas reads. */
class RdfSelectNodeTest {
    private val registry = PureNodes.registry { 1L }

    @Test fun theNodeAnswersRowsInTheCanvasShape() = runTest {
        val out = registry.getValue(RdfSelectNode.TYPE).run(
            LcncNode("n1", RdfSelectNode.TYPE, mapOf("sparql" to "PREFIX ex: <urn:ex:> SELECT ?x ?n WHERE { ?x ex:name ?n }")),
            mapOf("turtle" to SparqlQueryTest.FIXTURE),
        )
        assertEquals(1, out["count"])
        assertEquals(listOf(mapOf("x" to "<urn:ex:a>", "n" to "A")), out["rows"])
        assertFailsWith<IllegalStateException> { registry.getValue(RdfSelectNode.TYPE).run(LcncNode("n2", RdfSelectNode.TYPE, emptyMap()), mapOf("turtle" to "")) }
    }

    @Test fun itAgreesWithTheRegexSelectItReplacesAndGoesBeyondIt() {
        val sparql = "PREFIX ex: <urn:ex:> SELECT ?x ?y WHERE { ?x ex:p ?y }"
        val legacy = TurtleRdf.select(TurtleRdf.parse(SparqlQueryTest.FIXTURE), sparql).map { r ->
            r.entries.associate { (k, v) -> k.removePrefix("?") to ((v as? RdfTerm.Literal)?.lexical ?: v.toTurtle()) }
        }
        assertEquals(legacy, RdfSelectNode.select(SparqlQueryTest.FIXTURE, sparql))
        // what the regex never did: a graph, a filter, an aggregate
        assertEquals(listOf(mapOf("x" to "<urn:ex:a>", "n" to "2")),
            RdfSelectNode.select(SparqlQueryTest.FIXTURE, "PREFIX ex: <urn:ex:> SELECT ?x (COUNT(?y) AS ?n) WHERE { ?x ex:p ?y } GROUP BY ?x ORDER BY DESC(?n) LIMIT 1"))
        assertEquals(listOf(mapOf("x" to "<urn:ex:d>")),
            RdfSelectNode.select(SparqlQueryTest.FIXTURE, "PREFIX ex: <urn:ex:> SELECT ?x WHERE { GRAPH ex:g2 { ?x ex:score ?s } FILTER(?s < 0.5) }"))
    }
}
