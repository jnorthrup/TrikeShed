package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OroborosRuntimeTest {
    private fun program(vararg nodes: LcncNode) =
        LcncProgram("observed", nodes.size j { nodes[it] }, emptySeriesOf())

    @Test fun pauseWaitsForTheAdmittedNodeThenHoldsTheNextBoundary() = runTest {
        val debug = LcncDebug(stepping = false)
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val runner = LcncRunner(mapOf("test.effect" to LcncNodeRunner { node, _ ->
            calls.add(node.id)
            if (node.id == "first") release.await()
            mapOf("value" to node.id)
        }))
        val work = async(debug) { runner.runProcedure(program(
            LcncNode("first", "test.effect"), LcncNode("second", "test.effect"))) }
        try {
            runCurrent()
            assertEquals("first", (debug.snapshot()["site"] as Map<*, *>)["nodeId"])
            assertEquals("executing", (debug.snapshot()["site"] as Map<*, *>)["phase"])
            assertTrue(debug.pause())
            assertEquals("pause-requested", debug.snapshot()["state"])
            assertEquals("requested", (debug.snapshot()["controls"] as List<Map<*, *>>).last()["status"])
            assertEquals(listOf("first"), calls)
            release.complete(Unit)
            runCurrent()
            val pause = debug.snapshot()
            assertEquals("paused", pause["state"])
            assertEquals("second", (pause["site"] as Map<*, *>)["nodeId"])
            assertEquals("applied", (pause["controls"] as List<Map<*, *>>).last()["status"])
            assertEquals(listOf("first"), calls)
            assertTrue(debug.command(pause["ordinal"] as Int, LcncDebug.Command.CONTINUE))
            work.await()
            assertEquals(listOf("first", "second"), calls)
            assertEquals("applied", (debug.snapshot()["controls"] as List<Map<*, *>>).last()["status"])
            debug.finish("completed")
            debug.close()
            assertEquals("completed", debug.snapshot()["state"])
            assertFalse(debug.pause())
            assertFalse(debug.cancel())
        } finally { work.cancelAndJoin(); debug.close() }
    }

    @Test fun cancellationCompletesAdmittedEffectsAndDoesNotAdmitAnotherNode() = runTest {
        val debug = LcncDebug(stepping = false)
        val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val runner = LcncRunner(mapOf("test.effect" to LcncNodeRunner { node, _ ->
            release.await()
            effects.add(node.id)
            emptyMap()
        }))
        val work = async(debug) { runner.runProcedure(program(
            LcncNode("first", "test.effect"), LcncNode("second", "test.effect"))) }
        try {
            runCurrent()
            assertTrue(debug.cancel())
            assertFalse(work.isCompleted)
            assertEquals("cancel-requested", debug.snapshot()["state"])
            release.complete(Unit)
            assertFailsWith<LcncDebug.Cancelled> { work.await() }
            assertEquals(listOf("first"), effects)
            assertEquals("cancelled", debug.snapshot()["state"])
            assertEquals("applied", (debug.snapshot()["controls"] as List<Map<*, *>>).last()["status"])
        } finally { work.cancelAndJoin(); debug.close() }
    }

    @Test fun lexicalPatchChangesTheSelectedScopeAndInvalidatesReplayRetention() = runTest {
        val debug = LcncDebug(stepping = false)
        val body = s_[LcncNode("read", "scope.in", mapOf("name" to "x")),
            LcncNode("yield", "scope.out", mapOf("name" to "value"))]
        val graph = LcncProgram("lexical", s_[LcncNode("ring", "scope", children = body)],
            s_[LcncWire("read", "value", "yield", "value")])
        assertTrue(debug.select(setOf("read"), listOf("ring")))
        val work = async(debug) { LcncRunner(emptyMap()).runProcedure(graph, mapOf("x" to "outer")) }
        try {
            runCurrent()
            val pause = debug.snapshot()
            assertEquals(listOf("ring"), (pause["site"] as Map<*, *>)["path"])
            assertTrue(debug.intervene(pause["ordinal"] as Int, bindings = mapOf("x" to "inner")))
            assertEquals("accepted", (debug.snapshot()["interventions"] as List<Map<*, *>>).single()["status"])
            assertTrue(debug.command(pause["ordinal"] as Int, LcncDebug.Command.CONTINUE))
            val result = work.await()
            assertEquals("inner", result.nodeOutputs["ring"]?.get("value"))
            assertEquals("debug_intervention", result.retained.unavailable)
            assertEquals("applied", (debug.snapshot()["interventions"] as List<Map<*, *>>).single()["status"])
        } finally { work.cancelAndJoin(); debug.close() }
    }

    @Test fun inputPatchRunsOnceAndStaleOrCancelledPatchesCannotAffectLaterWork() = runTest {
        val debug = LcncDebug(stepping = false)
        val values = mutableListOf<Any?>()
        val runner = LcncRunner(mapOf(
            "test.source" to LcncNodeRunner { _, _ -> mapOf("value" to "original") },
            "test.effect" to LcncNodeRunner { _, inputs -> values.add(inputs["value"]); mapOf("value" to inputs["value"]) },
        ))
        val graph = LcncProgram("inputs", s_[LcncNode("source", "test.source"), LcncNode("effect", "test.effect")],
            s_[LcncWire("source", "value", "effect", "value")])
        debug.select(setOf("effect"))
        val work = async(debug) { runner.runProcedure(graph) }
        try {
            runCurrent()
            val pause = debug.snapshot()
            val ordinal = pause["ordinal"] as Int
            assertFalse(debug.intervene(ordinal - 1, inputs = mapOf("value" to "stale")))
            assertFailsWith<IllegalArgumentException> { debug.intervene(ordinal, inputs = mapOf("unknown" to 1)) }
            assertFailsWith<IllegalArgumentException> { debug.intervene(ordinal, inputs = mapOf("value?" to "ineffective")) }
            assertTrue(debug.intervene(ordinal, inputs = mapOf("value" to "replacement")))
            assertTrue(values.isEmpty())
            assertTrue(debug.command(ordinal, LcncDebug.Command.CONTINUE))
            assertEquals("replacement", work.await().nodeOutputs["effect"]?.get("value"))
            assertEquals(listOf<Any?>("replacement"), values)
        } finally { work.cancelAndJoin(); debug.close() }

        val cancelled = LcncDebug()
        var called = false
        val held = async { cancelled.checkpoint(1, LcncNode("held", "test"), emptyList(), null, null,
            mapOf("value" to "original"), apply = { called = true }) }
        try {
            runCurrent()
            assertTrue(cancelled.intervene(cancelled.snapshot()["ordinal"] as Int, inputs = mapOf("value" to "cancelled")))
            assertTrue(cancelled.cancel())
            assertFailsWith<LcncDebug.Cancelled> { held.await() }
            assertFalse(called)
            assertEquals("cancelled", (cancelled.snapshot()["interventions"] as List<Map<*, *>>).single()["status"])
        } finally { held.cancelAndJoin(); cancelled.close() }
    }

    @Test fun automaticFailureStopsBeforePropagationAndKeepsFailureEvidence() = runTest {
        val debug = LcncDebug(stepping = false)
        debug.select(setOf("failure"), automatic = LcncDebug.Automatic.FAILURE)
        val runner = LcncRunner(mapOf("test.failure" to LcncNodeRunner { _, _ -> error("failure detail") }))
        val work = async(debug) { runCatching { runner.runProcedure(program(LcncNode("failure", "test.failure"))) } }
        try {
            runCurrent()
            val pause = debug.snapshot()
            assertEquals("paused", pause["state"])
            val site = pause["site"] as Map<*, *>
            assertEquals("after", site["phase"])
            assertEquals("failed", site["status"])
            assertEquals("failure detail", site["reason"])
            assertFalse("intervene" in pause["actions"] as List<*>)
            assertFalse(work.isCompleted)
            assertTrue(debug.command(pause["ordinal"] as Int, LcncDebug.Command.CONTINUE))
            assertEquals("failure detail", work.await().exceptionOrNull()?.message)
        } finally { work.cancelAndJoin(); debug.close() }
    }

    @Test fun terminalCancellationCannotBeCommittedAsSuccessfulCompletion() = runTest {
        val debug = LcncDebug(stepping = false)
        withContext(debug) { LcncRunner(emptyMap()).runProcedure(program()) }
        assertTrue(debug.cancel())
        assertFailsWith<LcncDebug.Cancelled> { debug.finish("completed") }
        debug.finish("cancelled")
        debug.close()
        assertEquals("cancelled", debug.snapshot()["state"])
        assertTrue((debug.snapshot()["actions"] as List<*>).isEmpty())
    }

    @Test fun inspectionOnlyRunCannotAcceptInterventionsAndDrainReleasesItsHold() = runTest {
        val debug = LcncDebug(allowInterventions = false)
        var called = false
        val held = async { debug.checkpoint(1, LcncNode("held", "test"), emptyList(), null, null,
            mapOf("value" to "original"), apply = { called = true }) }
        try {
            runCurrent()
            val pause = debug.snapshot()
            assertFalse("intervene" in pause["actions"] as List<*>)
            assertFalse(debug.intervene(pause["ordinal"] as Int, inputs = mapOf("value" to "invalid")))
            debug.drain()
            held.await()
            assertFalse(called)
        } finally { held.cancelAndJoin(); debug.close() }
    }
}
