package borg.trikeshed.rdf

import borg.trikeshed.cursor.query.*
import kotlin.test.*

/** The SPARQL protocol as a client drives it: query by GET and POST, the dataset parameters, negotiation, Update, refusals. */
class SparqlSurfaceTest {
    private val store = QuadStore().load(SparqlQueryTest.FIXTURE)
    private val api = SparqlSurface(store)
    private val select = "SELECT ?s ?o WHERE { GRAPH <urn:ex:g1> { ?s <urn:ex:p> ?o } } ORDER BY ?s"
    private val rows = """{"head":{"vars":["s","o"]},"results":{"bindings":[{"s":{"type":"uri","value":"urn:ex:a"},"o":{"type":"uri","value":"urn:ex:b"}},{"s":{"type":"uri","value":"urn:ex:b"},"o":{"type":"uri","value":"urn:ex:c"}}]}}"""

    /** Percent-encodes every byte outside the unreserved set, as a browser or curl --data-urlencode does. */
    private fun enc(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~") append(c.toChar()) else append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }

    @Test fun queryByGetAndByBothPostForms() {
        val get = api.handle("GET", "/sparql?query=${enc(select)}", emptyMap(), "")
        assertEquals(200, get.status, get.body)
        assertEquals("application/sparql-results+json; charset=utf-8", get.contentType)
        assertEquals(rows, get.body)
        val form = api.handle("POST", "/sparql", mapOf("Content-Type" to "application/x-www-form-urlencoded"), "query=${enc(select)}")
        assertEquals(rows, form.body)
        val direct = api.handle("POST", "/sparql", mapOf("Content-Type" to "application/sparql-query; charset=utf-8", "Accept" to "application/sparql-results+json"), select)
        assertEquals(rows, direct.body)
    }

    @Test fun theDatasetParametersOverrideTheQuery() {
        val all = api.handle("GET", "/sparql?query=${enc("SELECT ?x WHERE { ?x <urn:ex:p> ?y }")}&format=csv", emptyMap(), "")
        assertEquals("x\r\nurn:ex:a\r\nurn:ex:b\r\nurn:ex:a\r\nurn:ex:d\r\n", all.body)
        // the HTML form always sends an empty default-graph-uri, which means nothing
        val g2 = api.handle("GET", "/sparql?query=${enc("SELECT ?x FROM <urn:ex:g1> WHERE { ?x <urn:ex:p> ?y }")}&default-graph-uri=&default-graph-uri=urn%3Aex%3Ag2&format=csv", emptyMap(), "")
        assertEquals("x\r\nurn:ex:a\r\nurn:ex:d\r\n", g2.body)
        val named = api.handle("GET", "/sparql?query=${enc("SELECT ?g WHERE { GRAPH ?g { ?x <urn:ex:score> ?s } }")}&named-graph-uri=urn%3Aex%3Ag2&output=tsv", emptyMap(), "")
        assertEquals("?g\n<urn:ex:g2>\n", named.body)
    }

    @Test fun negotiationFollowsFormatThenAccept() {
        val q = enc("ASK { <urn:ex:a> <urn:ex:p> <urn:ex:b> }")
        assertEquals("{\"head\":{},\"boolean\":true}", api.handle("GET", "/sparql?query=$q", mapOf("Accept" to "*/*"), "").body)
        assertEquals("true", api.handle("GET", "/sparql?query=$q", mapOf("Accept" to "text/csv"), "").body)
        assertEquals("text/tab-separated-values; charset=utf-8", api.handle("GET", "/sparql?query=$q&format=tsv", mapOf("Accept" to "application/json"), "").contentType)
        val refused = api.handle("GET", "/sparql?query=$q", mapOf("Accept" to "application/rdf+xml"), "")
        assertEquals(406, refused.status)
        assertEquals(406, api.handle("GET", "/sparql?query=$q&format=xml", emptyMap(), "").status)
    }

    @Test fun updateWritesTheStoreAndRefusalsAreVirtuososOwn() {
        val size = store.size
        val insert = api.handle("POST", "/sparql", mapOf("Content-Type" to "application/sparql-update"), "PREFIX ex: <urn:ex:> INSERT DATA { GRAPH ex:g3 { ex:z ex:p ex:a . ex:z ex:q \"new\" } ex:z ex:name \"Z\"@en }")
        assertEquals(200, insert.status, insert.body)
        assertEquals("{\"inserted\":3,\"deleted\":0,\"size\":${size + 3}}", insert.body)
        assertEquals("x\r\nurn:ex:z\r\n", api.handle("GET", "/sparql?query=${enc("SELECT ?x WHERE { GRAPH <urn:ex:g3> { ?x <urn:ex:q> \"new\" } }")}&format=csv", emptyMap(), "").body)
        val delete = api.handle("POST", "/sparql", mapOf("Content-Type" to "application/x-www-form-urlencoded"), "update=${enc("DELETE DATA { GRAPH <urn:ex:g3> { <urn:ex:z> <urn:ex:p> <urn:ex:a> } } ; DELETE DATA { <urn:ex:z> <urn:ex:name> \"Z\"@en . <urn:ex:none> <urn:ex:p> <urn:ex:none> }")}")
        assertEquals("{\"inserted\":0,\"deleted\":2,\"size\":${size + 1}}", delete.body)
        val bad = api.handle("GET", "/sparql?query=${enc("SELECT ?x WHERE { ?x ex:p ?y }")}", emptyMap(), "")
        assertEquals(400, bad.status)
        assertTrue(bad.body.startsWith("Virtuoso 37000 Error SP030: SPARQL compiler, line 1:"), bad.body)
        assertEquals("text/plain; charset=utf-8", bad.contentType)
        assertEquals(400, api.handle("GET", "/sparql", emptyMap(), "").status)
        assertEquals(405, api.handle("PUT", "/sparql?query=x", emptyMap(), "").status)
        assertEquals(400, api.handle("GET", "/sparql?update=${enc("INSERT DATA { <urn:ex:q> <urn:ex:p> <urn:ex:q> }")}", emptyMap(), "").status, "an update is sent by POST")
        val unbound = api.handle("GET", "/sparql?query=${enc("SELECT ?nope WHERE { ?x <urn:ex:p> ?y }")}", emptyMap(), "")
        assertEquals(400, unbound.status); assertTrue("nope" in unbound.body, unbound.body)
        assertEquals(400, api.handle("POST", "/sparql", mapOf("Content-Type" to "application/sparql-update"), "INSERT { ?s ?p ?o } WHERE { ?s ?p ?o }").status)
    }

    @Test fun graphFormsNegotiateTurtleOrNTriples() {
        val construct = enc("PREFIX ex: <urn:ex:> CONSTRUCT { ?x ex:knows ?y } WHERE { GRAPH ex:g1 { ?x ex:p ?y } }")
        val turtle = api.handle("GET", "/sparql?query=$construct", emptyMap(), "")
        assertEquals("text/turtle; charset=utf-8", turtle.contentType)
        assertTrue(turtle.body.startsWith("@prefix") && "<urn:ex:a> <urn:ex:knows> <urn:ex:b> ." in turtle.body, turtle.body)
        val nt = api.handle("GET", "/sparql?query=$construct&format=nt", mapOf("Accept" to "text/turtle"), "")
        assertEquals("application/n-triples; charset=utf-8", nt.contentType)
        assertEquals("<urn:ex:a> <urn:ex:knows> <urn:ex:b> .\n<urn:ex:b> <urn:ex:knows> <urn:ex:c> .\n", nt.body)
        assertEquals(406, api.handle("GET", "/sparql?query=$construct", mapOf("Accept" to "application/sparql-results+json"), "").status)
        val describe = api.handle("GET", "/sparql?query=${enc("DESCRIBE <urn:ex:e>")}", mapOf("Accept" to "application/n-triples"), "")
        assertEquals("<urn:ex:d> <urn:ex:p> <urn:ex:e> .\n<urn:ex:e> <urn:ex:self> <urn:ex:e> .\n", describe.body)
    }

    @Test fun liveRowsAreSearchedBesideTheStore() {
        val live = QuadStore().add("<urn:ex:live>", "<urn:ex:p>", "<urn:ex:a>").scan()
        val reply = api.handle("GET", "/sparql?query=${enc("SELECT ?x WHERE { ?x <urn:ex:p> <urn:ex:a> }")}&format=csv", emptyMap(), "", live)
        assertEquals("x\r\nurn:ex:live\r\n", reply.body)
    }
}
