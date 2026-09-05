package borg.trikeshed.lcnc

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(22, served.size, "the family is twenty-two node types: the nine verbs plus the thirteen the decomposition scan found unreached")
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

    @Test
    fun theRestOfTheEngineIsProgrammableMemberByMember() = runTest {
        val store = InMemoryCcekStore()
        val reg = registry(store)
        suspend fun run(type: String, params: Map<String, String> = emptyMap(), inputs: Map<String, Any?> = emptyMap()) =
            reg.getValue(type).run(LcncNode("x-$type", type, params), inputs)

        // a node's liveness, before and after the drain
        run("ccek.incarnate", mapOf("title" to "t"))
        run("ccek.signal", mapOf("verb" to "append", "text" to "one"), mapOf("handle" to "t"))
        val alive = run("ccek.vitals", inputs = mapOf("handle" to "t"))
        assertEquals(true, alive["active"])
        assertEquals(1, alive["markdownProjections"])
        run("ccek.drain", inputs = mapOf("handle" to "t"))
        assertEquals(false, run("ccek.vitals", inputs = mapOf("handle" to "t"))["active"])
        assertEquals(false, run("ccek.vitals", inputs = mapOf("handle" to "nobody"))["active"])

        // a context: activation, its standing document, and the rete query
        val ctx = run("ccek.context", mapOf("role" to "operator"))["contextId"].toString()
        assertEquals(false, run("ccek.lineage", inputs = mapOf("contextId" to ctx))["active"])
        val on = run("ccek.activate", mapOf("mode" to "activate"), mapOf("contextId" to ctx))
        assertEquals(true, on["active"]); assertEquals(true, on["known"])
        assertEquals(false, run("ccek.activate", mapOf("mode" to "deactivate"), mapOf("contextId" to ctx))["active"])
        assertEquals(false, run("ccek.activate", mapOf("mode" to "activate"), mapOf("contextId" to "ghost"))["known"])
        run("ccek.fact", mapOf("kind" to "block:appended"), mapOf("contextId" to ctx, "fields" to mapOf("text" to "one", "lane" to "a")))
        run("ccek.fact", mapOf("kind" to "block:deleted"), mapOf("contextId" to ctx, "fields" to mapOf("lane" to "b")))
        run("ccek.fact", mapOf("kind" to "card:moved"), mapOf("contextId" to ctx, "fields" to mapOf("lane" to "a")))
        val lineage = run("ccek.lineage", inputs = mapOf("contextId" to ctx))
        assertEquals(3, lineage["factCount"])
        assertEquals("", lineage["parentId"], "a root context has no parent")
        val blocks = run("ccek.query", mapOf("kind" to "block:"), mapOf("contextId" to ctx))
        assertEquals(2, blocks["count"], "a prefix query, as CausalReteTable.query defines it")
        assertEquals(true, blocks["contains"])
        assertEquals(false, run("ccek.query", mapOf("kind" to "nothing"), mapOf("contextId" to ctx))["contains"])

        // the spreadsheet veneer facets the same facts by field
        val laneA = run("ccek.veneer", mapOf("column" to "lane", "value" to "a"), mapOf("contextId" to ctx))
        assertEquals(2, laneA["count"])

        // polyglot facts load and query
        val loaded = run("ccek.polyglot.load", inputs = mapOf("contextId" to ctx, "facts" to listOf(
            mapOf("language" to "kotlin", "opcode" to "call", "target" to "sendSignal", "kind" to "verb"),
            mapOf("language" to "js", "opcode" to "call", "target" to "fetch", "kind" to "verb"),
        )))
        assertEquals(2, loaded["loaded"])
        assertEquals(1, run("ccek.polyglot.query", mapOf("language" to "kotlin", "kind" to "verb"), mapOf("contextId" to ctx))["count"])

        // prediction and its table test, as UserContext defines them
        val prediction = run("ccek.predict", mapOf("model" to "m"), mapOf("contextId" to ctx, "inputs" to mapOf("method" to "appendBlock", "count" to 1)))
        @Suppress("UNCHECKED_CAST")
        val p = prediction["prediction"] as Map<String, Any?>
        assertEquals(2, p["expectedBlocks"])
        val test = run("ccek.table.test", inputs = mapOf("contextId" to ctx, "prediction" to p))
        assertEquals(true, test["passed"], "two block facts satisfy expectedBlocks=2: ${test["evidence"]}")
        val untestable = run("ccek.table.test", inputs = mapOf("contextId" to ctx, "prediction" to mapOf("model" to "m")))
        assertEquals(false, untestable["passed"])
        assertTrue(untestable["evidence"].toString().contains("expectedBlocks"))

        // a graphical flow is a cursor over its blocks
        val flow = run("ccek.flow", mapOf("name" to "f"), mapOf("contextId" to ctx,
            "blocks" to listOf(mapOf("id" to "a", "label" to "A"), mapOf("id" to "b", "label" to "B", "properties" to mapOf("x" to "1"))),
            "edges" to listOf(mapOf("from" to "a", "to" to "b"))))
        assertEquals(2, flow["size"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(1, ((flow["flow"] as Map<String, Any?>)["edges"] as List<*>).size)

        // a paradigm's rules land as facts the context can be queried for
        val paradigm = run("ccek.paradigm", mapOf("name" to "p"), mapOf("contextId" to ctx,
            "rules" to listOf(mapOf("name" to "r1", "expression" to "a -> b"), mapOf("name" to "r2", "expression" to "b -> c"))))
        assertEquals(5, paradigm["factCount"])
        assertEquals(2, run("ccek.query", mapOf("kind" to "rule:p:"), mapOf("contextId" to ctx))["count"])

        // choreograph: a node the context asserts against, idempotent by title
        val bound = run("ccek.choreograph", mapOf("title" to "stage"), mapOf("contextId" to ctx))
        assertEquals("stage", bound["handle"]); assertEquals(true, bound["bound"])
        assertEquals("stage", run("ccek.choreograph", mapOf("title" to "stage"), mapOf("contextId" to ctx))["handle"])
        assertEquals(2, store.nodes.size, "t and stage")
        assertEquals(ctx, store.nodes.getValue("stage").choreographedBy)
        assertEquals(false, run("ccek.choreograph", mapOf("title" to "orphan"), mapOf("contextId" to "ghost"))["bound"])
        assertEquals(true, run("ccek.signal", mapOf("verb" to "append", "text" to "x"), mapOf("handle" to "stage"))["sent"])
    }

    @Test
    fun scopeValidationNamesTheKeysThisContextLacksAndRefusesUnknownNames() = runTest {
        val reg = registry(InMemoryCcekStore())
        // runTest's own root is a TestScopeImpl, not a CompletableJob: the validator
        // says so, and the node carries that verdict instead of throwing.
        val unsupervised = reg.getValue("ccek.validate").run(LcncNode("v", "ccek.validate", mapOf("requiredKeys" to "")), emptyMap())
        assertEquals(false, unsupervised["valid"])
        assertTrue(unsupervised["error"].toString().contains("CompletableJob"), "the donor's own message: $unsupervised")
        val supervisor = SupervisorJob(currentCoroutineContext()[Job])
        try {
            withContext(supervisor) {
                val bare = reg.getValue("ccek.validate").run(LcncNode("v", "ccek.validate", mapOf("requiredKeys" to "")), emptyMap())
                assertEquals(true, bare["valid"], "a supervised scope with nothing required: $bare")
                assertEquals("", bare["error"])
                val reactor = reg.getValue("ccek.validate").run(LcncNode("v", "ccek.validate", mapOf("requiredKeys" to "MuxReactorElement, HtxElement")), emptyMap())
                assertEquals(false, reactor["valid"])
                assertEquals(listOf("MuxReactorElement", "HtxElement"), reactor["missingKeys"], "a test scope carries neither")
                val spi = reg.getValue("ccek.validate").run(LcncNode("v", "ccek.validate", mapOf("requiredKeys" to "", "minimumSpis" to "FileOperations")), emptyMap())
                assertEquals(false, spi["valid"])
                assertEquals(listOf("FileOperations"), spi["missingSpis"])
            }
        } finally {
            supervisor.complete() // a SupervisorJob never finishes on its own; runTest would wait on it
        }
        val e = assertFailsWith<IllegalArgumentException> {
            reg.getValue("ccek.validate").run(LcncNode("v", "ccek.validate", mapOf("requiredKeys" to "Teleporter")), emptyMap())
        }
        assertTrue(e.message!!.contains("Teleporter"), "the message names the bad key")
    }
}
