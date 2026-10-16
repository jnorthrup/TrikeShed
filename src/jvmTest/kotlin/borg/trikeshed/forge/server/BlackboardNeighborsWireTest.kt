package borg.trikeshed.forge.server

import borg.trikeshed.parse.reify

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoClassifier
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BlackboardNeighborsWireTest {
    @Test fun aDeletedKeyFailsTheLcncInvocationInsteadOfPublishingEmptySuccess() = runTest {
        val board = ConfixBlackboard.empty()
        board.put("case/a&b", "Engineer", "source")
        board.put("case/c", "Developer", "peer")
        val middle = SumoClassifier.parse("(subclass Engineer SoftwareRole) (subclass Developer SoftwareRole) (subclass SoftwareRole Entity)")
        val wire = BlackboardWire(board, backgroundScope) { middle }
        val ledger = LcncInvocationLedger()
        val runner = LcncRunner(SurfaceNodes.registry { method, path, body ->
            assertNull(body)
            reify(assertNotNull(wire.route(method, path, "")).body)
        }).apply { invocations = ledger }
        val program = LcncProgram("neighbors", s_[
            LcncNode("neighbors", "blackboard.neighbors", params = mapOf("key" to "case/a&b")),
        ], emptySeriesOf())
        val output = runner.runAll(program).getValue("neighbors")
        assertEquals("case/c", ((output["neighbors"] as List<*>).single() as Map<*, *>)["key"])
        val projection = output["projection"] as Map<*, *>
        assertEquals(board.snapshot().revision, (projection["revision"] as Number).toLong())

        board.remove("case/a&b")
        val before = board.snapshot()
        val failure = assertFailsWith<IllegalStateException> { runner.runAll(program) }
        assertEquals("blackboard.neighbors: no_such_fact", failure.message)
        val invocation = (ledger.snapshot()["entries"] as List<*>).single() as Map<*, *>
        assertEquals("failed", invocation["status"])
        assertEquals(failure.message, invocation["reason"])
        assertFalse(invocation.containsKey("outputs"), "the refused lookup must not publish a successful projection")
        assertEquals(before, board.snapshot())
    }

    @Test fun resultCapsAndRevisionSelectionNeverMutateTheBoard() = runTest {
        val board = ConfixBlackboard.empty()
        board.put("source", "shared evidence", "source")
        repeat(260) { board.put("peer/$it", "shared evidence", "peer") }
        val before = board.snapshot()
        val wire = BlackboardWire(board, backgroundScope) { SumoClassifier.parse("(subclass Evidence Entity)") }
        suspend fun query(suffix: String): Map<*, *> {
            val response = assertNotNull(wire.route("GET", "/blackboard/neighbors?key=source$suffix", ""))
            assertEquals(200, response.status)
            val projection = reify(response.body) as Map<*, *>
            assertEquals(before.revision, (projection["revision"] as Number).toLong())
            assertEquals(before.values.size, (projection["indexedKeys"] as Number).toInt())
            return projection
        }
        for ((suffix, count) in listOf("" to 32, "&limit=2" to 2, "&limit=0" to 1,
            "&limit=999" to 256, "&limit=bad" to 32)) {
            assertEquals(count, (query("&revision=${before.revision}$suffix")["neighbors"] as List<*>).size)
        }
        val current = query("")
        val pinned = query("&revision=${before.revision}&corpus=middle")
        assertEquals(current, pinned)
        assertEquals(before, board.snapshot(), "neighbor projections must not assert facts or change provenance")
    }

    @Test fun routeUsesTheWholeRevisionAndKeepsTheMiddleCorpus() = runTest {
        val board = ConfixBlackboard.empty()
        board.put("listing/a&b", "Engineer", "source")
        board.put("profile/c", "Developer", "user")
        val middle = SumoClassifier.parse("(subclass Engineer SoftwareRole) (subclass Developer SoftwareRole) (subclass SoftwareRole Entity)")
        val wire = BlackboardWire(board, backgroundScope) { middle }
        suspend fun query(): Map<*, *> {
            val response = wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b", "")!!
            assertEquals(200, response.status)
            return reify(response.body) as Map<*, *>
        }
        val first = query()
        assertEquals("middle", first["corpus"])
        assertEquals(2, (first["indexedKeys"] as Number).toInt())
        assertEquals(board.snapshot().revision, (first["revision"] as Number).toLong())
        assertEquals("profile/c", ((first["neighbors"] as List<*>).single() as Map<*, *>)["key"])
        val cached = wire.neighborIndex
        query()
        assertSame(cached, wire.neighborIndex)
        board.remove("profile/c")
        assertEquals(0, (query()["neighbors"] as List<*>).size)
        assertNotSame(cached, wire.neighborIndex)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors", "")?.status)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&corpus=other", "")?.status)
        assertEquals(400, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&corpus=full", "")?.status)
        assertEquals(404, wire.route("GET", "/blackboard/neighbors?key=profile%2Fc", "")?.status)
        assertEquals(409, wire.route("GET", "/blackboard/neighbors?key=listing%2Fa%26b&revision=0", "")?.status)
    }
}
