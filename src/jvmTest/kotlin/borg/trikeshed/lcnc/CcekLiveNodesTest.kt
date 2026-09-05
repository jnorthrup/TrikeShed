package borg.trikeshed.lcnc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The not-theater gate: these runners drive a REAL [borg.trikeshed.ccek.ArticulatedNode],
 * not a fake. A program's `ccek.signal` must reach the live engine, the program's own
 * `ccek.agent` must receive it off CCEK's bounded fan-out, the recording must replay it,
 * and the projection must show the document CCEK actually built.
 *
 * runBlocking (not runTest) because CCEK fans out on Dispatchers.Default — real threads,
 * exactly as CcekIncarnationTest documents.
 */
class CcekLiveNodesTest {

    @Test
    fun aProgramSignalsTheRealEngineAndItsAgentReceivesTheFanOut() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reg = CcekNodes.registry(CcekSeams.live(scope))
            val handle = reg.getValue("ccek.incarnate").run(
                LcncNode("n2", "ccek.incarnate", mapOf("title" to "live-showcase", "record" to "true", "maxConcurrency" to "4")),
                emptyMap(),
            )["handle"].toString()
            assertEquals("live-showcase", handle)

            // Subscribe THIS program as an agent before signalling: the drain seam
            // subscribes on first call, and CCEK only fans out to live agents.
            reg.getValue("ccek.agent").run(
                LcncNode("n4", "ccek.agent", mapOf("name" to "watcher")), mapOf("handle" to handle),
            )

            val sent = reg.getValue("ccek.signal").run(
                LcncNode("n3", "ccek.signal", mapOf("verb" to "append", "blockKind" to "TEXT", "text" to "reached the engine")),
                mapOf("handle" to handle),
            )
            assertEquals(true, sent["sent"], "the signal reached a live node")

            // Let the real fan-out run (threads, not a test dispatcher).
            var drained = 0
            repeat(40) {
                delay(50)
                val out = reg.getValue("ccek.agent").run(
                    LcncNode("n4", "ccek.agent", mapOf("name" to "watcher")), mapOf("handle" to handle),
                )
                drained += out["count"] as Int
                if (drained > 0) return@repeat
            }
            assertTrue(drained > 0, "the program's agent received the signal off CCEK's fan-out")

            val replay = reg.getValue("ccek.recording").run(
                LcncNode("n6", "ccek.recording"), mapOf("handle" to handle),
            )
            assertTrue((replay["count"] as Int) >= 1, "CCEK recorded the signal: ${replay["count"]}")

            val projection = reg.getValue("ccek.projection").run(
                LcncNode("n5", "ccek.projection", mapOf("kind" to "markdown")), mapOf("handle" to handle),
            )
            assertEquals("markdown", projection["kind"])

            val status = reg.getValue("ccek.status").run(
                LcncNode("n7", "ccek.status"), mapOf("handle" to handle),
            )
            assertTrue((status["started"] as Int) >= 1, "fan-out status observed: $status")

            assertEquals(true, reg.getValue("ccek.drain").run(
                LcncNode("n8", "ccek.drain"), mapOf("handle" to handle),
            )["drained"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun contextLineageForksAgainstTheRealUserContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reg = CcekNodes.registry(CcekSeams.live(scope))
            val root = reg.getValue("ccek.context").run(
                LcncNode("c1", "ccek.context", mapOf("role" to "operator")), emptyMap(),
            )
            val rootId = root["contextId"].toString()
            assertTrue(rootId.startsWith("ctx"), "a real UserContext id: $rootId")

            val child = reg.getValue("ccek.context").run(
                LcncNode("c2", "ccek.context", mapOf("role" to "reviewer")), mapOf("parent" to rootId),
            )
            @Suppress("UNCHECKED_CAST")
            val doc = child["context"] as Map<String, Any?>
            assertEquals(rootId, doc["parentId"], "the fork records its lineage")
            assertTrue(child["contextId"].toString() != rootId, "a fork is a distinct context")

            val fact = reg.getValue("ccek.fact").run(
                LcncNode("f", "ccek.fact", mapOf("kind" to "observation")),
                mapOf("contextId" to child["contextId"], "fields" to mapOf("card" to "c1")),
            )
            assertEquals(true, fact["asserted"], "the causal fact landed: $fact")
            assertTrue((fact["factCount"] as Int) >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun choreographBindsARealNodeToAContextAndVitalsFollowTheDrain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reg = CcekNodes.registry(CcekSeams.live(scope))
            val ctx = reg.getValue("ccek.context").run(
                LcncNode("c", "ccek.context", mapOf("role" to "author")), emptyMap(),
            )["contextId"].toString()
            val bound = reg.getValue("ccek.choreograph").run(
                LcncNode("ch", "ccek.choreograph", mapOf("title" to "live-stage")), mapOf("contextId" to ctx),
            )
            assertEquals("live-stage", bound["handle"]); assertEquals(true, bound["bound"])
            assertEquals(true, reg.getValue("ccek.vitals").run(LcncNode("v", "ccek.vitals"), mapOf("handle" to "live-stage"))["active"])

            // The context's own causal-assertion agent rides the node's fan-out:
            // an append becomes a block:appended fact the rete query can see.
            assertEquals(true, reg.getValue("ccek.signal").run(
                LcncNode("s", "ccek.signal", mapOf("verb" to "append", "text" to "asserted")), mapOf("handle" to "live-stage"),
            )["sent"])
            var count = 0
            repeat(60) {
                delay(50)
                count = reg.getValue("ccek.query").run(
                    LcncNode("q", "ccek.query", mapOf("kind" to "block:appended")), mapOf("contextId" to ctx),
                )["count"] as Int
                if (count > 0) return@repeat
            }
            assertTrue(count >= 1, "the choreographed node asserted into its context")
            assertEquals(1, reg.getValue("ccek.veneer").run(
                LcncNode("ve", "ccek.veneer", mapOf("column" to "text", "value" to "asserted")), mapOf("contextId" to ctx),
            )["count"])

            assertEquals(true, reg.getValue("ccek.drain").run(LcncNode("d", "ccek.drain"), mapOf("handle" to "live-stage"))["drained"])
            var active = true
            repeat(60) {
                delay(50)
                active = reg.getValue("ccek.vitals").run(LcncNode("v", "ccek.vitals"), mapOf("handle" to "live-stage"))["active"] as Boolean
                if (!active) return@repeat
            }
            assertEquals(false, active, "a drained node reports inactive")

            val validation = reg.getValue("ccek.validate").run(
                LcncNode("va", "ccek.validate", mapOf("requiredKeys" to "MuxReactorElement")), emptyMap(),
            )
            assertEquals(false, validation["valid"], "a bare SupervisorJob scope carries no reactor: $validation")
            assertEquals(listOf("MuxReactorElement"), validation["missingKeys"])
        } finally {
            scope.cancel()
        }
    }
}
