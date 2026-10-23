package borg.trikeshed.forge.server

import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.SparqlQueryTest
import borg.trikeshed.rdf.SparqlSurface
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The /sparql wire end to end, with no daemon: the raw request text in, a negotiated reply out, the live rows beside the store. */
class SparqlWireTest {
    private val store = QuadStore().load(SparqlQueryTest.FIXTURE)
    private val wire = SparqlWire(SparqlSurface(store), live = { QuadStore().add("<urn:ex:live>", "<urn:ex:p>", "<urn:ex:a>").scan() })

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

    @Test fun otherPathsAreNotThisWires() {
        assertNull(request("GET", "/sparql/other"))
        assertNull(request("GET", "/api/health"))
    }
}
