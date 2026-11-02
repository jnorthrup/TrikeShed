package borg.trikeshed.forge.server

import borg.trikeshed.rdf.CypherSurface
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.RdfVocab
import borg.trikeshed.rdf.SparqlQueryTest
import borg.trikeshed.rdf.SparqlSurface
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.narsese.DocumentCurationFacts
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** The /sparql wire end to end, with no daemon: the raw request text in, a negotiated reply out, the live rows beside the store. */
class SparqlWireTest {
    private val store = QuadStore().load(SparqlQueryTest.FIXTURE)
    private val wire = SparqlWire(SparqlSurface(store), CypherSurface(store), live = { QuadStore().add("<urn:ex:live>", "<urn:ex:p>", "<urn:ex:a>").scan() })

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun request(method: String, path: String, headers: String = "", body: String = "") = runBlocking {
        wire.route(method, path, "$method $path HTTP/1.1\r\nHost: localhost\r\n$headers\r\n$body", null)
    }

    @Test fun getQueryNegotiatesAndSearchesTheLiveRows() {
        val csv = request("GET", "/sparql?query=${enc("SELECT ?x WHERE { ?x <urn:ex:p> <urn:ex:a> }")}", "Accept: text/csv\r\n")!!
        assertEquals(200, csv.status)
        assertEquals("text/csv; charset=utf-8", csv.contentType)
        assertEquals("x\r\nurn:ex:live\r\n", csv.body)
        val json = request("GET", "/sparql?query=${enc("ASK { <urn:ex:live> <urn:ex:p> <urn:ex:a> }")}")!!
        assertEquals("application/sparql-results+json; charset=utf-8", json.contentType)
        assertEquals("{\"head\":{},\"boolean\":true}", json.body)
        assertNull(json.headers["Content-Type"], "the listener writes Content-Type itself")
    }

    @Test fun postFormUpdatesThenQueries() {
        val update = request("POST", "/sparql", "Content-Type: application/x-www-form-urlencoded\r\n", "update=${enc("INSERT DATA { <urn:ex:new> <urn:ex:p> <urn:ex:a> }")}")!!
        assertEquals(200, update.status, update.body)
        val rows = request("POST", "/sparql", "Content-Type: application/sparql-query\r\nAccept: text/tab-separated-values\r\n", "SELECT ?x WHERE { ?x <urn:ex:p> <urn:ex:a> } ORDER BY ?x")!!
        assertEquals("?x\n<urn:ex:live>\n<urn:ex:new>\n", rows.body)
        val bad = request("GET", "/sparql?query=${enc("SELECT ?x WHERE { ?x ex:p ?o }")}")!!
        assertEquals(400, bad.status)
        assertEquals("text/plain; charset=utf-8", bad.contentType)
    }

    @Test fun cypherPostsATransactionAndReadsRows() {
        val ns = RdfVocab.FORGE
        store.add("<${ns}ann>", "<${ns}name>", "\"Ann\"").add("<${ns}ann>", "<${ns}KNOWS>", "<${ns}bob>").add("<${ns}bob>", "<${ns}name>", "\"Bob\"")
        val reply = request("POST", "/db/neo4j/tx/commit", "Content-Type: application/json\r\n", """{"statements":[{"statement":"MATCH (a)-[:KNOWS]->(b) RETURN a.name, b.name"},{"statement":"MATCH (a)-[r]->(b) RETURN r"}]}""")!!
        assertEquals(200, reply.status)
        assertEquals("""{"results":[{"columns":["a.name","b.name"],"data":[{"row":["Ann","Bob"]}]}],"errors":[{"code":"Neo.ClientError.Statement.SyntaxError","message":"relationship variables have no identity in RDF; name the type: [:TYPE]"}]}""", reply.body)
        assertEquals(405, request("GET", "/db/neo4j/tx/commit")!!.status)
    }

    @Test fun otherPathsAreNotThisWires() {
        assertNull(request("GET", "/sparql/other"))
        assertNull(request("GET", "/api/health"))
    }

    @Test fun visualQueryRequiresAnExplicitDatasetAndCannotDispatchUpdates() = runBlocking {
        val qstore = QuadStore().add("<urn:stored>", "<urn:p>", "<urn:o>")
        val qwire = SparqlWire(SparqlSurface(qstore), live = {
            QuadStore().add("<urn:live>", "<urn:p>", "<urn:o>").scan()
        })
        val query = LcncProgram("visual", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?x"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?x", "predicate" j "<urn:p>", "object" j "<urn:o>"])
        ].toSeries(), _l[
            LcncWire("x", "variable", "q", "variables"), LcncWire("p", "pattern", "q", "patterns")
        ].toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        val doc = LcncProgramConfix.toJson(query)
        suspend fun visual(body: String) = qwire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        val stored = visual("""{"dataset":"quad-store","program":$doc}""")
        assertEquals(200, stored.status, stored.body)
        assertTrue(stored.body.contains("urn:stored"))
        assertFalse(stored.body.contains("urn:live"))
        val both = visual("""{"dataset":"quad-store+rete","program":$doc}""")
        assertEquals(200, both.status, both.body)
        assertTrue(both.body.contains("urn:stored") && both.body.contains("urn:live"))
        assertEquals(400, visual("""{"program":$doc}""").status)
        assertEquals(400, visual("""{"dataset":"other","program":$doc}""").status)
        val unsafe = LcncProgramConfix.toJson(query.copy(controls = LcncConfixControls()))
        assertEquals(400, visual("""{"dataset":"quad-store","program":$unsafe}""").status)
        assertEquals(1, qstore.size)
    }

    @Test fun visualCandidateContextRequiresProjectedIdentityAndLiveSnapshot() = runBlocking {
        val fact = PlaneFacts.fact(DocumentCurationFacts.PARTITION, "candidate/0", mapOf(
            PlaneFacts.KIND to DocumentCurationFacts.PROPOSAL, "quote" to "source sentence", "begin" to 0))
        val live = QuadStore().load(RdfGraph(PlaneFacts.toTriples(fact).view.toList())).scan()
        val wire = SparqlWire(SparqlSurface(QuadStore()), live = { live })
        val program = LcncProgram("candidate", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?fact"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?fact",
                "predicate" j "<${PlaneFacts.FIELD_NS}kind>", "object" j "\"document-proposal\""])
        ].toSeries(), _l[
            LcncWire("x", "variable", "q", "variables"), LcncWire("p", "pattern", "q", "patterns")
        ].toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        val doc = LcncProgramConfix.toJson(program)
        suspend fun send(dataset: String, variable: String) = wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" +
                """{"dataset":"$dataset","candidateVariable":"$variable","program":$doc}""", null)!!
        val reply = send("quad-store+rete", "?fact")
        assertEquals(200, reply.status, reply.body)
        assertTrue(reply.body.contains("source sentence"))
        assertTrue(reply.body.contains("not-established"))
        assertTrue(reply.body.contains("not independently verified"))
        assertEquals(400, send("quad-store", "fact").status)
        assertEquals(400, send("quad-store+rete", "other").status)

        // The store can contain a triple using the same IRI without that triple
        // being in the live fact. The context must never claim it witnessed the result.
        val storeOnly = QuadStore().add("<${PlaneFacts.factIri(fact).iri}>", "<urn:only-in-store>", "<urn:o>")
        val mixed = SparqlWire(SparqlSurface(storeOnly), live = { live })
        val mixedProgram = LcncProgram("mixed", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?fact"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?fact", "predicate" j "<urn:only-in-store>", "object" j "<urn:o>"])
        ].toSeries(), _l[
            LcncWire("x", "variable", "q", "variables"), LcncWire("p", "pattern", "q", "patterns")
        ].toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        val mixedBody = """{"dataset":"quad-store+rete","candidateVariable":"fact","program":${LcncProgramConfix.toJson(mixedProgram)}}"""
        val mixedReply = mixed.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$mixedBody", null)!!
        assertEquals(200, mixedReply.status, mixedReply.body)
        assertTrue(mixedReply.body.contains("query witnesses are not established"))
    }

    @Test fun visualQueryBoundsJoinWorkBeforeExecuting() = runBlocking {
        val store = QuadStore()
        for (i in 0 until 320) {
            store.add("<urn:s$i>", "<urn:p>", "<urn:o$i>")
            store.add("<urn:s$i>", "<urn:q>", "<urn:v$i>")
        }
        val wire = SparqlWire(SparqlSurface(store))
        fun graph(subject: String): LcncProgram = LcncProgram("bounded", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?o"]),
            LcncNode("a", SparqlGraph.PATTERN, _m["subject" j subject, "predicate" j "<urn:p>", "object" j "?o"]),
            LcncNode("b", SparqlGraph.PATTERN, _m["subject" j subject, "predicate" j "<urn:q>", "object" j "?v"])
        ].toSeries(), _l[
            LcncWire("x", "variable", "q", "variables"), LcncWire("a", "pattern", "q", "patterns"),
            LcncWire("b", "pattern", "q", "patterns")
        ].toSeries(), controls = LcncConfixControls(inspectionOnly = true))
        suspend fun send(body: String) = wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        fun body(program: LcncProgram) = """{"dataset":"quad-store","program":${LcncProgramConfix.toJson(program)}}"""
        // Even though shared ?s would select 320 rows, the conservative prefix product
        // is 320 * 320, so this is refused before the query engine materializes the join.
        val refused = send(body(graph("?s")))
        assertEquals(413, refused.status, refused.body)
        assertTrue(refused.body.contains("intermediate join limit"))
        val narrow = send(body(graph("<urn:s0>")))
        assertEquals(200, narrow.status, narrow.body)
        assertTrue(narrow.body.contains("urn:o0"))
        assertEquals(640, store.size)
    }

    @Test fun visualQueryRejectsOversizedAndDeepRequestsBeforeParsing() = runBlocking {
        val wire = SparqlWire(SparqlSurface(QuadStore()))
        suspend fun send(body: String) = wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        assertEquals(413, send("x".repeat(65_537)).status)
        assertEquals(413, send("é".repeat(32_769)).status)
        assertEquals(413, send("[".repeat(65) + "]".repeat(65)).status)
        assertEquals(400, send("{\"dataset\":").status)
        val quoted = "[".repeat(100)
        assertEquals(400, send("""{"dataset":"quad-store","program":{"nodes":[],"wires":[],"note":"$quoted"}}""").status)
        val manyNodes = (0 until 129).joinToString(",") { "{}" }
        assertEquals(413, send("""{"dataset":"quad-store","program":{"nodes":[$manyNodes],"wires":[]}}""").status)
    }

    @Test fun visualQueryRejectsTooManyPatternsAndOversizedDataset() = runBlocking {
        val store = QuadStore()
        val wire = SparqlWire(SparqlSurface(store))
        suspend fun send(program: LcncProgram): WireHttpResponse {
            val body = """{"dataset":"quad-store","program":${LcncProgramConfix.toJson(program)}}"""
            return wire.route("POST", "/sparql/visual",
                "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        }
        val base = _l[LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?x"])]
        val patterns = (0 until 9).map { i -> LcncNode("p$i", SparqlGraph.PATTERN,
            _m["subject" j "?x", "predicate" j "<urn:p>", "object" j "<urn:o>"]) }
        val variableWire = LcncWire("x", "variable", "q", "variables")
        val wires = _l[variableWire] + (0 until 9).map { LcncWire("p$it", "pattern", "q", "patterns") }
        val program = LcncProgram("patterns", (base + patterns).toSeries(), wires.toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
        assertEquals(413, send(program).status)
        for (i in 0 until 20_001) store.add("<urn:s$i>", "<urn:p>", "<urn:o>")
        val onePattern = LcncProgram("one", (base + patterns.take(1)).toSeries(),
            (_l[variableWire, LcncWire("p0", "pattern", "q", "patterns")]).toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
        assertEquals(413, send(onePattern).status)
        assertEquals(20_001, store.size)
    }

    @Test fun visualQueryBoundsRepeatedVariableKeysAndResponseBytes() = runBlocking {
        val store = QuadStore()
        for (i in 0 until 1_000) store.add("<urn:s$i>", "<urn:p>", "<urn:o>")
        val wire = SparqlWire(SparqlSurface(store))
        fun program(name: String): LcncProgram {
            val variable = "?$name"
            return LcncProgram("output", _l[
                LcncNode("q", SparqlGraph.SELECT),
                LcncNode("x", SparqlGraph.VARIABLE, _m["name" j variable]),
                LcncNode("p", SparqlGraph.PATTERN,
                    _m["subject" j variable, "predicate" j "<urn:p>", "object" j "<urn:o>"])
            ].toSeries(), _l[LcncWire("x", "variable", "q", "variables"),
                LcncWire("p", "pattern", "q", "patterns")].toSeries(),
                controls = LcncConfixControls(inspectionOnly = true))
        }
        suspend fun send(name: String): WireHttpResponse {
            val body = """{"dataset":"quad-store","program":${LcncProgramConfix.toJson(program(name))}}"""
            return wire.route("POST", "/sparql/visual",
                "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        }
        val short = send("x")
        assertEquals(200, short.status, short.body)
        assertTrue(short.body.encodeToByteArray().size < 1_048_576)
        // A key is repeated for every result row; a long but permitted name can
        // exceed the conservative response budget even with small source terms.
        val wide = send("x".repeat(128))
        assertEquals(413, wide.status, wide.body)
        assertTrue(wide.body.contains("result size limit"))
        val tooLong = send("x".repeat(129))
        assertEquals(413, tooLong.status, tooLong.body)
        assertTrue(tooLong.body.contains("variable name"))
    }

    @Test fun visualQueryChecksTermsOutsideTheSelectedGraph() = runBlocking {
        val store = QuadStore()
        store.add("<urn:s>", "<urn:p>", "<urn:o>", "<urn:selected>")
        store.add("<urn:other>", "<urn:p>", "\"${"x".repeat(4_097)}\"", "<urn:other>")
        val wire = SparqlWire(SparqlSurface(store))
        val program = LcncProgram("term", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?x"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?x", "predicate" j "<urn:p>",
                "object" j "<urn:o>", "graph" j "<urn:selected>"])
        ].toSeries(), _l[LcncWire("x", "variable", "q", "variables"),
            LcncWire("p", "pattern", "q", "patterns")].toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
        val body = """{"dataset":"quad-store","program":${LcncProgramConfix.toJson(program)}}"""
        val reply = wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        assertEquals(413, reply.status, reply.body)
        assertTrue(reply.body.contains("term too large"))
    }

    @Test fun visualQueryBoundsCumulativeSourceTextBeforeFiltering() = runBlocking {
        val store = QuadStore()
        val value = "\"${"x".repeat(3_990)}\""
        for (i in 0 until 1_000) store.add("<urn:s$i>", "<urn:p>", value)
        val wire = SparqlWire(SparqlSurface(store))
        val program = LcncProgram("source-text", _l[
            LcncNode("q", SparqlGraph.SELECT),
            LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?x"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?x", "predicate" j "<urn:p>", "object" j "<urn:no-match>"])
        ].toSeries(), _l[LcncWire("x", "variable", "q", "variables"),
            LcncWire("p", "pattern", "q", "patterns")].toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
        val body = """{"dataset":"quad-store","program":${LcncProgramConfix.toJson(program)}}"""
        val reply = wire.route("POST", "/sparql/visual",
            "POST /sparql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
        assertEquals(413, reply.status, reply.body)
        assertTrue(reply.body.contains("source limit") || reply.body.contains("text limit"))
    }
}
