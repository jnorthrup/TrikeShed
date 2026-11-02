package borg.trikeshed.forge.server

import borg.trikeshed.rdf.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.*
import kotlinx.coroutines.runBlocking
import java.net.URLEncoder
import kotlin.test.*

class SparqlSourceWireTest {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test fun ordinarySourceSelectionPreservesDefaultUnionAndSeparatesInputs(): Unit = runBlocking {
        val stored = QuadStore().add("<urn:stored>", "<urn:p>", "<urn:o>")
        var samples = 0
        val wire = SparqlWire(SparqlSurface(stored), live = {
            samples++; QuadStore().add("<urn:live>", "<urn:p>", "<urn:o>").scan()
        })
        val query = enc("SELECT ?s WHERE { ?s <urn:p> <urn:o> } ORDER BY ?s")
        suspend fun read(source: String?) = wire.route("GET", "/sparql?query=$query" +
            (source?.let { "&source=${it.replace("+", "%2B")}" } ?: ""), "GET /sparql HTTP/1.1\r\nAccept: text/csv\r\n\r\n", null)!!
        val default = read(null)
        assertEquals("s\r\nurn:live\r\nurn:stored\r\n", default.body)
        assertEquals("quad-store+rete", default.headers["X-TrikeShed-Source"])
        assertEquals("separate-store-and-live-snapshots", default.headers["X-TrikeShed-Sampling"])
        val count = samples
        val store = read("quad-store")
        assertEquals("s\r\nurn:stored\r\n", store.body)
        assertEquals(count, samples, "Store-only requests must not sample live facts")
        assertEquals("s\r\nurn:live\r\n", read("rete-projection").body)
        assertEquals(default.body, read("quad-store+rete").body)
        for (source in listOf(null, "quad-store", "rete-projection", "quad-store+rete")) {
            val result = read(source)
            assertEquals(200, result.status)
            assertEquals("not-established", result.headers["X-TrikeShed-Review-Authority"])
            assertEquals("not-established", result.headers["X-TrikeShed-Binding-Provenance"])
            assertEquals("raw-matched-data", result.headers["X-TrikeShed-Scope"])
        }
        assertEquals(400, read("approved").status)
        assertEquals(400, read("quad-store&source=rete-projection").status)
        val absent = SparqlWire(SparqlSurface(stored))
        assertEquals(400, absent.route("GET", "/sparql?source=rete-projection&query=$query", "", null)!!.status)
        assertEquals(200, absent.route("GET", "/sparql?query=$query", "", null)!!.status)
    }

    @Test fun sourceSelectorDoesNotReplaceGraphDatasetAndFormSelectionWorks(): Unit = runBlocking {
        val stored = QuadStore().add("<urn:stored>", "<urn:p>", "<urn:o>", "<urn:g>")
        val wire = SparqlWire(SparqlSurface(stored), live = {
            QuadStore().add("<urn:live>", "<urn:p>", "<urn:o>", "<urn:h>").scan()
        })
        val query = enc("SELECT ?s FROM <urn:h> WHERE { ?s <urn:p> <urn:o> }")
        val body = "query=$query&source=rete-projection&default-graph-uri=urn%3Ag"
        val reply = wire.route("POST", "/sparql", "POST /sparql HTTP/1.1\r\nContent-Type: application/x-www-form-urlencoded\r\nAccept: text/csv\r\n\r\n$body", null)!!
        assertEquals(200, reply.status)
        assertEquals("s\r\n", reply.body, "Protocol graph replaces FROM, but cannot re-add the excluded store")
        assertEquals("rete-projection", reply.headers["X-TrikeShed-Source"])
    }

    @Test fun updatesRemainStoreOnlyAndRejectLiveSelectors(): Unit = runBlocking {
        val stored = QuadStore()
        val live = QuadStore().add("<urn:live>", "<urn:p>", "<urn:o>")
        val wire = SparqlWire(SparqlSurface(stored), live = { live.scan() })
        val body = "INSERT DATA { <urn:new> <urn:p> <urn:o> }"
        suspend fun update(source: String?) = wire.route("POST", "/sparql" + (source?.let { "?source=${enc(it)}" } ?: ""),
            "POST /sparql HTTP/1.1\r\nContent-Type: application/sparql-update\r\n\r\n$body", null)!!
        assertEquals(400, update("rete-projection").status)
        assertEquals(400, update("quad-store+rete").status)
        assertEquals(0, stored.size)
        val result = update(null)
        assertEquals(200, result.status)
        assertEquals("quad-store", result.headers["X-TrikeShed-Source"])
        assertEquals(1, stored.size)
        assertEquals(1, live.size)
        assertEquals(200, update("quad-store").status)
    }

    @Test fun approvedLookingRawClaimsNeverChangeResponseAuthority(): Unit = runBlocking {
        val stored = QuadStore().add("<urn:candidate>", "<urn:reviewStatus>", "\"approved\"")
        val wire = SparqlWire(SparqlSurface(stored))
        val query = enc("ASK { <urn:candidate> <urn:reviewStatus> \"approved\" }")
        val result = wire.route("GET", "/sparql?source=quad-store&query=$query", "", null)!!
        assertEquals("{\"head\":{},\"boolean\":true}", result.body)
        assertEquals("not-established", result.headers["X-TrikeShed-Review-Authority"])
    }

    @Test fun visualReteOnlySourceExcludesStoreAndReturnsDescriptor(): Unit = runBlocking {
        val stored = QuadStore().add("<urn:stored>", "<urn:p>", "<urn:o>")
        val wire = SparqlWire(SparqlSurface(stored), live = { QuadStore().add("<urn:live>", "<urn:p>", "<urn:o>").scan() })
        val program = LcncProgram("visual", _l[
            LcncNode("q", SparqlGraph.SELECT), LcncNode("x", SparqlGraph.VARIABLE, _m["name" j "?x"]),
            LcncNode("p", SparqlGraph.PATTERN, _m["subject" j "?x", "predicate" j "<urn:p>", "object" j "<urn:o>"])
        ].toSeries(), _l[LcncWire("x", "variable", "q", "variables"), LcncWire("p", "pattern", "q", "patterns")].toSeries(),
            controls = LcncConfixControls(inspectionOnly = true))
        val body = """{"dataset":"rete-projection","program":${LcncProgramConfix.toJson(program)}}"""
        val response = wire.route("POST", "/sparql/visual", "POST /sparql/visual HTTP/1.1\r\n\r\n$body", null)!!
        assertEquals(200, response.status, response.body)
        val output = assertIs<Map<*, *>>(reifyStrict(response.body))
        val source = assertIs<Map<*, *>>(output["source"])
        assertEquals("rete-projection", source["selected"])
        assertEquals(listOf("rete-projection"), source["sources"])
        assertEquals("not-established", source["reviewAuthority"])
        assertTrue(response.body.contains("urn:live"))
        assertFalse(response.body.contains("urn:stored"))
    }
}
