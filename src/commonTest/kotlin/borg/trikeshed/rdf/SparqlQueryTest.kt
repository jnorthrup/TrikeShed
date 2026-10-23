package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

/** Two named graphs and a default graph, queried as Virtuoso would: quads mode, triples mode, FROM, GRAPH, FILTER, aggregates. */
class SparqlQueryTest {
    private val store = QuadStore().load(FIXTURE)
    private fun rows(c: Cursor): List<List<Any?>> = List(c.size) { r -> c[r].values.let { v -> List(v.size) { v[it] } } }
    private fun ask(sparql: String, source: Cursor = store.scan()): List<List<Any?>> = rows(SparqlQuery.parse(sparql).plan(source))
    private fun iri(local: String) = "<urn:ex:$local>"

    @Test fun aBasicGraphPatternJoinsOnSharedVariablesAcrossEveryGraph() {
        assertEquals(listOf(listOf(iri("a"), iri("b")), listOf(iri("b"), iri("c")), listOf(iri("a"), iri("d")), listOf(iri("d"), iri("e"))), ask("$PREFIX SELECT ?x ?y WHERE { ?x ex:p ?y }"))
        assertEquals(listOf(listOf(iri("a"), iri("c")), listOf(iri("a"), iri("e"))), ask("$PREFIX SELECT ?x ?z WHERE { ?x ex:p ?y . ?y ex:p ?z }"))
        // a star join: the subject of three patterns, read across every graph (ex:p in g1 and g2, ex:q in g1, the type in the default graph)
        assertEquals(listOf(listOf(iri("a"), iri("b"), "\"x\""), listOf(iri("a"), iri("d"), "\"x\"")), ask("$PREFIX SELECT ?s ?o ?q WHERE { ?s ex:p ?o ; ex:q ?q ; a ex:T }"))
        // a non-numeric term in a numeric filter is a type error: the row drops, the query answers
        assertEquals(listOf(listOf(iri("a"))), ask("$PREFIX SELECT ?x WHERE { ?x ?p ?v FILTER(?v > 0.5) }"))
        assertEquals(listOf(listOf("\"x\"")), ask("$PREFIX SELECT ?v WHERE { ex:a ex:q ?v FILTER(CONTAINS(?v, \"x\")) }"))
        // the same variable twice in one pattern binds equal terms
        assertEquals(listOf(listOf(iri("e"))), ask("$PREFIX SELECT ?x WHERE { ?x ex:self ?x }"))
        assertEquals(emptyList(), ask("$PREFIX SELECT ?x WHERE { ?x ex:p <urn:ex:nowhere> }"))
    }

    @Test fun graphsAreReadAsVirtuosoReadsThem() {
        // GRAPH <g>: one graph; GRAPH ?g: every named graph, never the default graph
        assertEquals(listOf(listOf(iri("a")), listOf(iri("d"))), ask("$PREFIX SELECT ?x WHERE { GRAPH ex:g2 { ?x ex:p ?y } }"))
        assertEquals(listOf(listOf(iri("g1"), iri("a")), listOf(iri("g2"), iri("d"))), ask("$PREFIX SELECT ?g ?x WHERE { GRAPH ?g { ?x ex:score ?s } }"))
        assertEquals(emptyList(), ask("$PREFIX SELECT ?g ?n WHERE { GRAPH ?g { ?x ex:name ?n } }"))
        // FROM narrows the default graph; the protocol's dataset overrides the query's
        assertEquals(listOf(listOf(iri("a")), listOf(iri("b"))), ask("$PREFIX SELECT ?x FROM ex:g1 WHERE { ?x ex:p ?y }"))
        assertEquals(listOf(listOf(iri("a")), listOf(iri("d"))), rows(SparqlQuery.parse("$PREFIX SELECT ?x FROM ex:g1 WHERE { ?x ex:p ?y }", defaultGraphUris = listOf("urn:ex:g2")).plan(store.scan())))
        // FROM NAMED limits GRAPH ?g
        assertEquals(listOf(listOf(iri("g2"))), ask("$PREFIX SELECT ?g FROM NAMED ex:g2 WHERE { GRAPH ?g { ?x ex:score ?s } }"))
    }

    @Test fun filtersCompareLexemesAndNumbersAndModifiersAreThePlannersOwn() {
        assertEquals(listOf(listOf(iri("a"))), ask("$PREFIX SELECT ?x WHERE { ?x ex:score ?s FILTER(?s > 0.5) }"))
        assertEquals(listOf(listOf(iri("d"))), ask("$PREFIX SELECT ?x WHERE { ?x ex:score ?s . FILTER(?s <= 0.4 && ?x != ex:a) }"))
        assertEquals(listOf(listOf(iri("b"))), ask("$PREFIX SELECT ?y WHERE { ?x ex:p ?y FILTER(?x = ex:a && !(?y = ex:d)) }"))
        assertEquals(listOf(listOf("\"A\"@en")), ask("$PREFIX SELECT ?n WHERE { ?x ex:name ?n FILTER(CONTAINS(?n, \"A\")) }"))
        assertEquals(listOf(listOf(iri("a"), 2L)), ask("$PREFIX SELECT ?x (COUNT(?y) AS ?n) WHERE { ?x ex:p ?y } GROUP BY ?x ORDER BY DESC(?n) LIMIT 1"))
        assertEquals(listOf(listOf(1.3)), ask("$PREFIX SELECT (SUM(?s) AS ?total) WHERE { ?x ex:score ?s }").map { r -> r.map { (it as Double).let { d -> kotlin.math.round(d * 10) / 10 } } })
        assertEquals(listOf(listOf(iri("a")), listOf(iri("b")), listOf(iri("d"))), ask("$PREFIX SELECT DISTINCT ?x WHERE { ?x ex:p ?y }"))
        assertEquals(listOf(listOf(iri("b")), listOf(iri("a"))), ask("$PREFIX SELECT ?x WHERE { ?x ex:p ?y } ORDER BY DESC(?x) LIMIT 2 OFFSET 1"))
        assertEquals(listOf(listOf(iri("a"), iri("b")), listOf(iri("b"), iri("c")), listOf(iri("a"), iri("d")), listOf(iri("d"), iri("e"))), ask("$PREFIX SELECT * WHERE { ?x ex:p ?y }"))
    }

    @Test fun askAnswersWhetherAnySolutionExists() {
        assertEquals(1, SparqlQuery.parse("$PREFIX ASK { ex:a ex:p ex:b }").plan(store.scan()).size)
        assertEquals(0, SparqlQuery.parse("$PREFIX ASK { ex:a ex:p ex:zzz }").plan(store.scan()).size)
        assertTrue(SparqlQuery.parse("$PREFIX ASK { ex:a ex:p ex:b }").ask)
    }

    @Test fun theReceiptNamesTheSparqlAndTheQuadTable() {
        val q = SparqlQuery.parse("$PREFIX SELECT ?x WHERE { ?x ex:p ?y }").plan(store.scan())
        val receipt = q.facet(QueryK.Receipt)
        assertTrue(receipt.statement.startsWith("PREFIX ex:"), receipt.statement)
        assertEquals(listOf("Graph", "Project"), receipt.stages)
        assertEquals(setOf(QuadStore.RDF_QUAD), receipt.sources.keys)
        assertEquals(receipt.statementCid, q[0].meta(0).provenance)
    }

    @Test fun resultsTakeTheW3cShapes() {
        val q = SparqlQuery.parse("$PREFIX SELECT ?x ?n WHERE { ?x ex:name ?n }")
        val rows = q.plan(store.scan())
        assertEquals(
            """{"head":{"vars":["x","n"]},"results":{"bindings":[{"x":{"type":"uri","value":"urn:ex:a"},"n":{"type":"literal","value":"A","xml:lang":"en"}}]}}""",
            SparqlResults.json(rows, q.variables),
        )
        assertEquals("x,n\r\nurn:ex:a,A\r\n", SparqlResults.csv(rows, q.variables))
        assertEquals("?x\t?n\n<urn:ex:a>\t\"A\"@en\n", SparqlResults.tsv(rows, q.variables))
        assertEquals("{\"head\":{},\"boolean\":true}", SparqlResults.askJson(true))
        // an aggregate cell is a typed literal; a typed literal keeps its datatype
        val count = SparqlQuery.parse("$PREFIX SELECT (COUNT(*) AS ?n) WHERE { ?x ex:p ?y }")
        assertEquals(
            """{"head":{"vars":["n"]},"results":{"bindings":[{"n":{"type":"literal","value":"4","datatype":"${RdfVocab.XSD}integer"}}]}}""",
            SparqlResults.json(count.plan(store.scan()), count.variables),
        )
        val score = SparqlQuery.parse("$PREFIX SELECT ?s WHERE { ex:a ex:score ?s }")
        assertEquals(
            """{"head":{"vars":["s"]},"results":{"bindings":[{"s":{"type":"literal","value":"0.9","datatype":"${RdfVocab.XSD}float"}}]}}""",
            SparqlResults.json(score.plan(store.scan()), score.variables),
        )
        // no rows: the head still names the variables
        assertEquals("{\"head\":{\"vars\":[\"x\"]},\"results\":{\"bindings\":[]}}", SparqlResults.json(SparqlQuery.parse("$PREFIX SELECT ?x WHERE { ?x ex:p ex:none }").let { it.plan(store.scan()) }, listOf("x")))
    }

    @Test fun theSubsetRefusesByName() {
        assertTrue(assertFailsWith<SparqlError> { SparqlQuery.parse("$PREFIX SELECT ?x WHERE { ?x ex:p ?y OPTIONAL { ?y ex:q ?z } }") }.message!!.contains("OPTIONAL"))
        assertTrue(assertFailsWith<SparqlError> { SparqlQuery.parse("$PREFIX CONSTRUCT { ?x ex:p ?y } WHERE { ?x ex:p ?y }") }.message!!.contains("CONSTRUCT"))
        assertTrue(assertFailsWith<SparqlError> { SparqlQuery.parse("SELECT ?x WHERE { ?x nope:p ?y }") }.message!!.contains("nope"))
        assertTrue(assertFailsWith<SparqlError> { SparqlQuery.parse("$PREFIX SELECT ?x WHERE { ?x ex:p ?y ") }.message!!.contains("'}'"))
        assertTrue(assertFailsWith<SparqlError> { SparqlQuery.parse("$PREFIX SELECT ?x WHERE { ?x ex:p ?y } LIMIT -1") }.message!!.contains("LIMIT"))
    }

    @Test fun theRegexSelectAndTheQuadStoreAgree() {
        val graph = TurtleRdf.parse(FIXTURE)
        val sparql = "$PREFIX SELECT ?x ?y WHERE { ?x ex:p ?y }"
        val legacy = TurtleRdf.select(graph, sparql).map { listOf(it["?x"]!!.toTurtle(), it["?y"]!!.toTurtle()) }
        assertEquals(legacy, ask(sparql))
        // the store round-trips the graph it loaded
        val back = store.toGraph()
        assertEquals(graph.triples.toSet(), back.triples.toSet())
        assertEquals(graph.quads.toSet(), back.quads.toSet())
    }

    companion object {
        const val PREFIX = "PREFIX ex: <urn:ex:> PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>"
        val FIXTURE = """
            @prefix ex: <urn:ex:> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            <urn:ex:g1> { <urn:ex:a> <urn:ex:p> <urn:ex:b> . <urn:ex:a> <urn:ex:q> "x" . <urn:ex:b> <urn:ex:p> <urn:ex:c> . <urn:ex:a> <urn:ex:score> "0.9"^^<http://www.w3.org/2001/XMLSchema#float> . }
            <urn:ex:g2> { <urn:ex:a> <urn:ex:p> <urn:ex:d> . <urn:ex:d> <urn:ex:p> <urn:ex:e> . <urn:ex:d> <urn:ex:score> "0.4"^^<http://www.w3.org/2001/XMLSchema#float> . <urn:ex:e> <urn:ex:self> <urn:ex:e> . }
            <urn:ex:a> <urn:ex:name> "A"@en .
            <urn:ex:a> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <urn:ex:T> .
        """.trimIndent()
    }
}
