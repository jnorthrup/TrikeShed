package borg.trikeshed.forge.server

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.SparqlSurface
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class VisualSqlWireTest {
    private fun body(program: LcncProgram, dataset: String = "quad-store"): String =
        jsonOf(_m["dataset" j dataset, "program" j reifyStrict(LcncProgramConfix.toJson(program))])

    private suspend fun send(wire: SparqlWire, body: String, method: String = "POST") =
        wire.route(method, "/sql/visual", "$method /sql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!

    @Test fun queriesOnlyQuadSnapshotAndPreservesColumnsAndAllRows() = runBlocking {
        val store = QuadStore().add("<urn:a>", "<urn:p>", "\"one\"").add("<urn:b>", "<urn:p>", "\"two\"")
        var liveReads = 0
        val wire = SparqlWire(SparqlSurface(store), live = { liveReads++; QuadStore().add("<urn:live>", "<urn:p>", "\"no\"").scan() })
        val program = SqlGraph.parse("SELECT S, O FROM DB.DBA.RDF_QUAD")
        val reply = send(wire, body(program))
        assertEquals(200, reply.status, reply.body)
        val value = reifyStrict(reply.body) as Map<*, *>
        assertEquals("quad-store", value["dataset"])
        assertEquals(_l["S", "O"], value["columns"])
        assertEquals(_l[_l["<urn:a>", "\"one\""], _l["<urn:b>", "\"two\""]], value["rows"])
        assertEquals(0, liveReads)
        assertEquals(2, store.size)
        val filtered = send(wire, body(SqlGraph.parse("SELECT S, S FROM DB.DBA.RDF_QUAD WHERE O = '\"one\"'")))
        assertEquals(200, filtered.status, filtered.body)
        val filteredValue = reifyStrict(filtered.body) as Map<*, *>
        assertEquals(_l["S", "S"], filteredValue["columns"])
        assertEquals(_l[_l["<urn:a>", "<urn:a>"]], filteredValue["rows"])
        val empty = send(SparqlWire(SparqlSurface(QuadStore())), body(program))
        assertEquals(200, empty.status, empty.body)
        val emptyValue = reifyStrict(empty.body) as Map<*, *>
        assertEquals(_l["S", "O"], emptyValue["columns"])
        assertEquals(emptyList<Any>(), emptyValue["rows"])
    }

    @Test fun refusesExecutableOtherDatasetAndUnsupportedSqlWithoutWrites() = runBlocking {
        val store = QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")
        val wire = SparqlWire(SparqlSurface(store))
        val program = SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD")
        assertEquals(405, send(wire, body(program), "GET").status)
        assertEquals(400, send(wire, body(program, "quad-store+rete")).status)
        assertEquals(400, send(wire, body(program.copy(controls = LcncConfixControls()))).status)
        val other = SqlGraph.parse("SELECT id FROM breadcrumbs")
        assertEquals(400, send(wire, body(other)).status)
        val mixed = program.copy(nodes = (program.nodes.view.toList() + LcncNode("other", "json.value")).toSeries())
        assertEquals(400, send(wire, body(mixed)).status)
        val ordered = SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD ORDER BY S")
        assertEquals(400, send(wire, body(ordered)).status)
        val limited = SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD LIMIT 1")
        assertEquals(400, send(wire, body(limited)).status)
        assertEquals(400, send(wire, "{not json}").status)
        assertEquals(1, store.size)
        assertTrue(store.contains("<urn:a>", "<urn:p>", "\"one\""))
    }

    @Test fun rejectsOversizedSourceAndResultsRatherThanTruncating() = runBlocking {
        val program = SqlGraph.parse("SELECT S, O FROM DB.DBA.RDF_QUAD")
        val wide = QuadStore().add("<urn:a>", "<urn:p>", "x".repeat(513))
        assertEquals(413, send(SparqlWire(SparqlSurface(wide)), body(program)).status)
        val many = QuadStore()
        for (i in 0..1_000) many.add("<urn:$i>", "<urn:p>", "\"one\"")
        assertEquals(413, send(SparqlWire(SparqlSurface(many)), body(program)).status)
        val responses = QuadStore()
        for (i in 0 until 200) responses.add("<urn:$i>", "<urn:p>", "\"" + "a".repeat(400) + "\"")
        assertEquals(413, send(SparqlWire(SparqlSurface(responses)), body(program)).status)
        assertEquals(200, responses.size)
    }

    @Test fun readsAfterTheSharedQuadStoreGateIsReleased() = runBlocking {
        val store = QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")
        val wire = SparqlWire(SparqlSurface(store))
        val program = SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD")
        wire.gate.lock()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { send(wire, body(program)) }
        try {
            assertFalse(pending.isCompleted)
            store.add("<urn:b>", "<urn:p>", "\"two\"")
        } finally { wire.gate.unlock() }
        val reply = pending.await()
        assertEquals(200, reply.status, reply.body)
        assertEquals(_l[_l["<urn:a>"], _l["<urn:b>"]], (reifyStrict(reply.body) as Map<*, *>)["rows"])
    }

    @Test fun refusesDuplicateJsonKeysAtAnyDepthIncludingEscapedNames() = runBlocking {
        val wire = SparqlWire(SparqlSurface(QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")))
        val clean = body(SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD"))
        val duplicateDataset = clean.replaceFirst("\"dataset\":\"quad-store\"",
            "\"dataset\":\"quad-store\",\"dataset\":\"other\"")
        val duplicateParam = clean.replaceFirst("\"name\":\"DB.DBA.RDF_QUAD\"",
            "\"name\":\"DB.DBA.RDF_QUAD\",\"\\u006eame\":\"other\"")
        assertNotEquals(clean, duplicateDataset)
        assertNotEquals(clean, duplicateParam)
        assertEquals(400, send(wire, duplicateDataset).status)
        assertEquals(400, send(wire, duplicateParam).status)
        assertEquals(200, send(wire, clean).status)
    }

    @Test fun soleAsteriskLiteralIsRejectedOnEmptyAndNonemptyStores() = runBlocking {
        val parsed = SqlGraph.parse("SELECT 'x' FROM DB.DBA.RDF_QUAD")
        val literal = parsed.copy(nodes = parsed.nodes.view.map { node ->
            if (node.type == SqlGraph.LIT) node.copy(params = node.params + ("value" to "*")) else node
        }.toSeries())
        for (store in listOf(QuadStore(), QuadStore().add("<urn:a>", "<urn:p>", "\"one\""))) {
            val wire = SparqlWire(SparqlSurface(store))
            val refused = send(wire, body(literal))
            assertEquals(400, refused.status, refused.body)
            assertTrue(refused.body.contains("ambiguous"))
            assertEquals(200, send(wire, body(SqlGraph.parse("SELECT * FROM DB.DBA.RDF_QUAD"))).status)
        }
    }

    @Test fun columnQualifiersMustNameTheSelectedTableOrItsAlias() = runBlocking {
        for (store in listOf(QuadStore(), QuadStore().add("<urn:a>", "<urn:p>", "\"one\""))) {
            val wire = SparqlWire(SparqlSurface(store))
            for (query in listOf("SELECT bad.S FROM DB.DBA.RDF_QUAD",
                "SELECT S FROM DB.DBA.RDF_QUAD WHERE bad.O = 'x'",
                "SELECT nope FROM DB.DBA.RDF_QUAD",
                "SELECT RDF_QUAD.S FROM DB.DBA.RDF_QUAD AS q")) {
                val reply = send(wire, body(SqlGraph.parse(query)))
                assertEquals(400, reply.status, "$query: ${reply.body}")
            }
            for (query in listOf("SELECT RDF_QUAD.S FROM DB.DBA.RDF_QUAD",
                "SELECT q.S FROM DB.DBA.RDF_QUAD AS q")) {
                val reply = send(wire, body(SqlGraph.parse(query)))
                assertEquals(200, reply.status, "$query: ${reply.body}")
            }
        }
    }

    @Test fun integersOutsideTheExactJsonRangeAreRefused() = runBlocking {
        val wire = SparqlWire(SparqlSurface(QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")))
        val parsed = SqlGraph.parse("SELECT 1 FROM DB.DBA.RDF_QUAD")
        fun integer(value: String) = parsed.copy(nodes = parsed.nodes.view.map { node ->
            if (node.type == SqlGraph.LIT) node.copy(params = node.params + ("value" to value)) else node
        }.toSeries())
        for (safe in listOf("9007199254740991", "-9007199254740991")) {
            val response = send(wire, body(integer(safe)))
            assertEquals(200, response.status, response.body)
            val data = reifyStrict(response.body) as Map<*, *>
            val rows = data["rows"] as List<*>
            assertEquals(safe.toLong(), (((rows.single() as List<*>).single()) as Number).toLong())
        }
        for (unsafe in listOf("9007199254740992", "-9007199254740992", "9223372036854775807")) {
            val response = send(wire, body(integer(unsafe)))
            assertEquals(400, response.status, response.body)
        }
    }

    @Test fun paddedDoubleKindsAreRejectedBeforeExecution() = runBlocking {
        val parsed = SqlGraph.parse("SELECT 1 FROM DB.DBA.RDF_QUAD")
        for (kind in listOf(" double ", " DoUbLe ")) {
            val program = parsed.copy(nodes = parsed.nodes.view.map { node ->
                if (node.type == SqlGraph.LIT) node.copy(params = node.params + ("kind" to kind)) else node
            }.toSeries())
            for (store in listOf(QuadStore(), QuadStore().add("<urn:a>", "<urn:p>", "\"one\""))) {
                val result = send(SparqlWire(SparqlSurface(store)), body(program))
                assertEquals(400, result.status, result.body)
            }
        }
    }

    @Test fun refusesInvalidUnicodeWithoutChangingTextAndPreservesValidPairs() = runBlocking {
        val program = SqlGraph.parse("SELECT 'x' FROM DB.DBA.RDF_QUAD")
        val clean = body(program)
        val wire = SparqlWire(SparqlSurface(QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")))
        for (bad in listOf("\\ud800", "\\udc00", "\uD800", "\uDC00")) {
            val changed = clean.replaceFirst("\"value\":\"x\"", "\"value\":\"$bad\"")
            assertNotEquals(clean, changed)
            assertEquals(400, send(wire, changed).status)
        }
        val brokenStore = QuadStore().add("<urn:\uD800>", "<urn:p>", "\"one\"")
        assertEquals(400, send(SparqlWire(SparqlSurface(brokenStore)), body(SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD"))).status)
        val valid = "\uD83D\uDE00"
        val exact = program.copy(nodes = program.nodes.view.map { node ->
            if (node.type == SqlGraph.LIT) node.copy(params = node.params + ("value" to valid)) else node
        }.toSeries())
        val result = send(wire, body(exact))
        assertEquals(200, result.status, result.body)
        assertEquals(_l[_l[valid]], (reifyStrict(result.body) as Map<*, *>)["rows"])
    }

    @Test fun nodeParameterValuesMustBeJsonStrings() = runBlocking {
        val clean = body(SqlGraph.parse("SELECT 'x' FROM DB.DBA.RDF_QUAD"))
        val wire = SparqlWire(SparqlSurface(QuadStore().add("<urn:a>", "<urn:p>", "\"one\"")))
        for (raw in listOf("9007199254740993", "null", "{\"x\":1}", "[1]", "true")) {
            val changed = clean.replaceFirst("\"value\":\"x\"", "\"value\":$raw")
            assertNotEquals(clean, changed)
            val result = send(wire, changed)
            assertEquals(400, result.status, result.body)
            assertTrue(result.body.contains("must be strings"))
        }
        assertEquals(200, send(wire, clean).status)
    }

    @Test fun supplementaryCharactersSurviveLiteralFilterAliasAndSourceTerm() = runBlocking {
        val symbol = "\uD83D\uDE00"
        val store = QuadStore().add("<urn:$symbol>", "<urn:p>", "\"$symbol\"")
        val parsed = SqlGraph.parse("SELECT S FROM DB.DBA.RDF_QUAD WHERE O = 'x'")
        val program = parsed.copy(nodes = parsed.nodes.view.map { node -> when (node.type) {
            SqlGraph.LIT -> node.copy(params = node.params + ("value" to "\"$symbol\""))
            SqlGraph.ITEM_EXPR -> node.copy(params = node.params + ("alias" to "party$symbol"))
            else -> node
        } }.toSeries())
        val reply = send(SparqlWire(SparqlSurface(store)), body(program))
        assertEquals(200, reply.status, reply.body)
        val result = reifyStrict(reply.body) as Map<*, *>
        assertEquals(_l["party$symbol"], result["columns"])
        assertEquals(_l[_l["<urn:$symbol>"]], result["rows"])
    }
}
