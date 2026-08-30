package borg.trikeshed.lcnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CCEK family is the showcase made programmable, so this gate holds it to
 * the same bar as every other vocabulary: every served type has a contract,
 * every ForgeSignal verb is constructible from a program, incarnation is
 * idempotent (a sweep re-runs it every tick), and the ports the preset wires
 * are the ports the contracts declare.
 */
class CcekNodesTest {

    private fun registry(store: InMemoryCcekStore) =
        CcekNodes.registry(CcekSeams.inMemory(store))

    @Test
    fun everyServedTypeHasAContractAndEveryContractHasARunner() {
        val contracts = LcncContracts.all().map { it.type }.toSet()
        val served = CcekNodes.servedTypes()
        assertEquals(9, served.size, "the family is nine node types")
        for (t in served) assertTrue(t in contracts, "$t is served but has no contract")
        val ccekContracts = contracts.filter { it.startsWith("ccek.") }.toSet()
        assertEquals(served, ccekContracts, "no ccek.* contract without a runner (dead vocabulary)")
    }

    @Test
    fun everyForgeSignalVerbIsConstructibleFromAProgram() = runTest {
        val store = InMemoryCcekStore()
        val reg = registry(store)
        reg.getValue("ccek.incarnate").run(LcncNode("n1", "ccek.incarnate", mapOf("title" to "t")), emptyMap())
        // Each verb carries the fields its case requires; none may throw.
        val fields = mapOf(
            "append" to mapOf("text" to "hello", "blockKind" to "TEXT"),
            "update" to mapOf("blockId" to "b1", "text" to "x"),
            "delete" to mapOf("blockId" to "b1"),
            "move" to mapOf("cardId" to "c1", "toColumnId" to "done"),
            "continue" to mapOf("cardId" to "c1"),
            "repeat" to mapOf("cardId" to "c1", "edgeId" to "e1"),
            "abort" to mapOf("cardId" to "c1", "reason" to "stop"),
            "fork" to mapOf("cardId" to "c1", "targetLane" to "review"),
            "join" to mapOf("cardId" to "c1", "group" to "g", "requiredBranches" to "2"),
            "vote" to mapOf("cardId" to "c1", "verdict" to "aye"),
        )
        assertEquals(CcekNodes.VERBS.toSet(), fields.keys, "the test covers every declared verb")
        for ((verb, params) in fields) {
            val node = LcncNode("s-$verb", "ccek.signal", params + mapOf("verb" to verb))
            val out = reg.getValue("ccek.signal").run(node, mapOf("handle" to "t"))
            assertEquals(true, out["sent"], "$verb reached the node")
            @Suppress("UNCHECKED_CAST")
            val signal = out["signal"] as Map<String, Any?>
            assertEquals(verb, signal["verb"])
            assertTrue((signal["describe"] as String).isNotBlank(), "$verb renders on the record")
        }
        assertEquals(10, store.nodes.getValue("t").recorded.size, "all ten verbs recorded")
    }

    @Test
    fun incarnationIsIdempotentByTitleSoASweepDoesNotDiscardTheNode() = runTest {
        val store = InMemoryCcekStore()
        val reg = registry(store)
        val node = LcncNode("n1", "ccek.incarnate", mapOf("title" to "same"))
        val a = reg.getValue("ccek.incarnate").run(node, emptyMap())
        reg.getValue("ccek.signal").run(
            LcncNode("s", "ccek.signal", mapOf("verb" to "append", "text" to "first")),
            mapOf("handle" to "same"),
        )
        val b = reg.getValue("ccek.incarnate").run(node, emptyMap())
        assertEquals(a["handle"], b["handle"], "same title, same node")
        assertEquals(1, store.nodes.size, "re-running the sweep did not incarnate a second node")
        assertEquals(1, store.nodes.getValue("same").recorded.size, "the recording survived the re-run")
    }

    @Test
    fun agentDrainRecordingAndContextLineageAreProgrammable() = runTest {
        val store = InMemoryCcekStore()
        val reg = registry(store)
        reg.getValue("ccek.incarnate").run(LcncNode("n1", "ccek.incarnate", mapOf("title" to "t")), emptyMap())
        reg.getValue("ccek.signal").run(
            LcncNode("s", "ccek.signal", mapOf("verb" to "move", "cardId" to "c1", "toColumnId" to "done")),
            mapOf("handle" to "t"),
        )
        val agent = reg.getValue("ccek.agent").run(LcncNode("a", "ccek.agent", mapOf("name" to "w")), mapOf("handle" to "t"))
        assertEquals(1, agent["count"], "the agent drained the fan-out")
        val replay = reg.getValue("ccek.recording").run(LcncNode("r", "ccek.recording"), mapOf("handle" to "t"))
        assertEquals(1, replay["count"], "the recording is the program's own data")

        val ctx = reg.getValue("ccek.context").run(LcncNode("c", "ccek.context", mapOf("role" to "operator")), emptyMap())
        val id = ctx["contextId"].toString()
        assertTrue(id.isNotBlank(), "a context was forked")
        val fact = reg.getValue("ccek.fact").run(
            LcncNode("f", "ccek.fact", mapOf("kind" to "observation")),
            mapOf("contextId" to id, "fields" to mapOf("card" to "c1")),
        )
        assertEquals(1, fact["factCount"], "the fact landed in the forked context")
        assertEquals(true, fact["asserted"])

        assertEquals(true, reg.getValue("ccek.drain").run(LcncNode("d", "ccek.drain"), mapOf("handle" to "t"))["drained"])
        assertTrue(store.nodes.getValue("t").drained, "drain reached the plane")
    }

    @Test
    fun anUnknownVerbFailsLoudlyRatherThanSendingGarbage() = runTest {
        val reg = registry(InMemoryCcekStore())
        reg.getValue("ccek.incarnate").run(LcncNode("n1", "ccek.incarnate", mapOf("title" to "t")), emptyMap())
        var threw = false
        try {
            reg.getValue("ccek.signal").run(
                LcncNode("s", "ccek.signal", mapOf("verb" to "teleport")),
                mapOf("handle" to "t"),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("teleport"), "the message names the bad verb")
        }
        assertTrue(threw, "an unknown verb must not be silently dropped")
    }
}
