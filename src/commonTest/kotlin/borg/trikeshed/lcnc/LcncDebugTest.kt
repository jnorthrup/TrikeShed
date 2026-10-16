package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LcncDebugTest {
    @Test fun nodeTypeAloneDoesNotAdmitSourceCommandsOrConsumeTheHold() = runTest {
        val debug = LcncDebug()
        var calls = 0
        val runner = LcncRunner(mapOf("vm.polyglot" to LcncNodeRunner { _, _ ->
            calls++; mapOf("value" to calls)
        }))
        val execution = async(debug) { runner.runProcedure(LcncProgram("node-only",
            s_[LcncNode("guest", "vm.polyglot")], emptySeriesOf())) }
        try {
            runCurrent()
            val pause = debug.snapshot()
            assertEquals(listOf("step", "continue"), pause["actions"])
            assertEquals(0, calls)
            assertFalse(debug.command(pause["ordinal"] as Int, LcncDebug.Command.SOURCE_INTO))
            assertEquals(pause, debug.snapshot())
            assertTrue(debug.command(pause["ordinal"] as Int, LcncDebug.Command.STEP))
            assertEquals(1, execution.await().nodeOutputs["guest"]?.get("value"))
            assertEquals(1, calls)
        } finally { execution.cancelAndJoin(); debug.close() }
    }

    @Test fun lineCommandsValidateTheHeldSourceBeforeConsumingItsOrdinal() = runTest {
        val debug = LcncDebug()
        val hold = async { debug.sourceCheckpoint(1, "guest", mapOf("lineCount" to 8)) }
        try {
            runCurrent()
            val paused = debug.snapshot()
            val ordinal = paused["ordinal"] as Int
            assertTrue("source-line" in paused["actions"] as List<*>)
            for (line in listOf(null, 0, -1, 9)) {
                assertFailsWith<IllegalArgumentException> { debug.command(ordinal, LcncDebug.Command.SOURCE_LINE, line) }
                assertEquals(paused, debug.snapshot())
                assertFalse(hold.isCompleted)
            }
            assertFalse(debug.command(ordinal - 1, LcncDebug.Command.SOURCE_LINE, 4))
            assertTrue(debug.command(ordinal, LcncDebug.Command.SOURCE_LINE, 8))
            assertFalse(debug.command(ordinal, LcncDebug.Command.SOURCE_LINE, 8))
            assertEquals(borg.trikeshed.vm.VmDebugAction.Line(8), hold.await())
        } finally { hold.cancelAndJoin(); debug.close() }
    }

    @Test fun sourceCommandsUseTheSamePauseSequenceAndDrainReleasesTheCallback() = runTest {
        val debug = LcncDebug()
        val node = async { debug.checkpoint(1, LcncNode("guest", "vm.polyglot"), emptyList(), null, null, emptyMap(),
            sourceDebugging = true) }
        runCurrent()
        val start = debug.snapshot()
        assertEquals(listOf("step", "continue", "source-into"), start["actions"])
        assertFalse(debug.command(start["ordinal"] as Int, LcncDebug.Command.SOURCE_OVER))
        assertTrue(debug.command(start["ordinal"] as Int, LcncDebug.Command.SOURCE_INTO))
        assertTrue(node.await())
        for ((command, action) in listOf(LcncDebug.Command.SOURCE_INTO to borg.trikeshed.vm.VmDebugAction.INTO,
            LcncDebug.Command.SOURCE_OVER to borg.trikeshed.vm.VmDebugAction.OVER,
            LcncDebug.Command.SOURCE_OUT to borg.trikeshed.vm.VmDebugAction.OUT,
            LcncDebug.Command.STEP to borg.trikeshed.vm.VmDebugAction.CONTINUE)) {
            val locals = mutableListOf("before")
            val hold = async { debug.sourceCheckpoint(1, "guest", mapOf("locals" to locals)) }
            runCurrent()
            locals[0] = "after"
            val pause = debug.snapshot()
            assertEquals(listOf("before"), (pause["site"] as Map<*, *>)["locals"])
            assertFalse(debug.command(start["ordinal"] as Int, command))
            assertTrue(debug.command(pause["ordinal"] as Int, command))
            assertEquals(action, hold.await())
        }
        val held = async { debug.sourceCheckpoint(1, "guest", mapOf("code" to "x".repeat(65537))) }
        runCurrent()
        assertEquals("payload_limit", (debug.snapshot()["site"] as Map<*, *>)["observationOmitted"])
        assertFalse("source-line" in debug.snapshot()["actions"] as List<*>)
        debug.drain()
        assertEquals(borg.trikeshed.vm.VmDebugAction.CONTINUE, held.await())
        assertEquals(borg.trikeshed.vm.VmDebugAction.CONTINUE, debug.sourceCheckpoint(1, "guest", emptyMap()))
        debug.close()
    }

    @Test fun concurrentCheckpointsCannotOverwritePausesOrReuseAnInvocationOrdinal() = runTest {
        val debug = LcncDebug()
        val released = mutableListOf<String>()
        val first = launch { debug.checkpoint(1, LcncNode("first", "test"), emptyList(), null, null, emptyMap()); released += "first" }
        val second = launch { debug.checkpoint(1, LcncNode("second", "test"), emptyList(), null, null, emptyMap()); released += "second" }
        try {
            runCurrent()
            val a = debug.snapshot()
            assertEquals("first", (a["site"] as Map<*, *>)["nodeId"])
            assertTrue(debug.command(a["ordinal"] as Int, LcncDebug.Command.STEP))
            runCurrent()
            val b = debug.snapshot()
            assertEquals("second", (b["site"] as Map<*, *>)["nodeId"])
            assertNotEquals(a["ordinal"], b["ordinal"])
            assertFalse(debug.command(a["ordinal"] as Int, LcncDebug.Command.CONTINUE))
            assertEquals(listOf("first"), released)
            debug.drain()
            first.join(); second.join()
            assertEquals(listOf("first", "second"), released)
        } finally { first.cancelAndJoin(); second.cancelAndJoin(); debug.close() }
    }

    @Test fun stepsEnterTheExistingRingAndEachIterationWithoutReplayingEffects() = runTest {
        val debug = LcncDebug()
        val calls = mutableListOf<Any?>()
        val body = s_[LcncNode("arg", "scope.in", mapOf("name" to "item")),
            LcncNode("work", "test.work"), LcncNode("out", "scope.out", mapOf("name" to "y"))]
        val program = LcncProgram("nested", s_[LcncNode("items", "test.items"),
            LcncNode("ring", "scope", children = body)], s_[
            LcncWire("items", "items", "ring", "each?"), LcncWire("arg", "value", "work", "x"),
            LcncWire("work", "y", "out", "value")])
        val runner = LcncRunner(mapOf(
            "test.items" to LcncNodeRunner { _, _ -> mapOf("items" to listOf("one", "two")) },
            "test.work" to LcncNodeRunner { _, inputs -> calls += inputs["x"]; mapOf("y" to inputs["x"]) }))
        val execution = async(debug) { runner.runProcedure(program) }
        val stops = mutableListOf<Map<*, *>>()
        try {
            repeat(8) {
                runCurrent()
                val pause = debug.snapshot()
                assertEquals("paused", pause["state"])
                stops += pause["site"] as Map<*, *>
                assertTrue(debug.command(pause["ordinal"] as Int, LcncDebug.Command.STEP))
                assertFalse(debug.command(pause["ordinal"] as Int, LcncDebug.Command.STEP))
            }
            runCurrent()
            assertEquals(listOf("items", "ring", "arg", "work", "out", "arg", "work", "out"), stops.map { it["nodeId"] })
            val work = stops.filter { it["nodeId"] == "work" }
            assertEquals(listOf(0, 1), work.map { it["iteration"] })
            assertTrue(work.all { it["path"] == listOf("ring") && it["parent"] == 2 })
            assertEquals<List<Any?>>(listOf("one", "two"), calls)
            assertEquals(listOf("one", "two"), execution.await().nodeOutputs["ring"]?.get("y"))
        } finally { execution.cancelAndJoin(); debug.close() }
    }

    @Test fun observationsAreBoundedCopiesAndDrainReleasesOnlyTheHold() = runTest {
        val debug = LcncDebug()
        val input = mutableListOf("before")
        val node = LcncNode("source", "test.source", mapOf("source" to "s".repeat(3000)))
        var executed = false
        val job = launch {
            debug.checkpoint(1, node, emptyList(), null, null, mapOf("x" to input))
            executed = true
            debug.checkpoint(2, node, emptyList(), null, null, emptyMap())
        }
        runCurrent()
        input[0] = "after"
        val site = debug.snapshot()["site"] as Map<*, *>
        assertEquals(mapOf("x" to listOf("before")), site["inputs"])
        assertEquals("payload_limit", site["paramsOmitted"])
        assertFalse(executed)
        debug.drain()
        assertFalse(debug.command(1, LcncDebug.Command.STEP))
        job.join()
        assertTrue(executed)
        debug.close()
        assertEquals("closed", debug.snapshot()["state"])
        assertNull(debug.snapshot()["site"])
    }

    @Test fun cancellationAtTheHoldNeverDispatchesTheNode() = runTest {
        val debug = LcncDebug()
        var calls = 0
        val runner = LcncRunner(mapOf("test.effect" to LcncNodeRunner { _, _ -> calls++; emptyMap() }))
        val job = launch(debug) { runner.runProcedure(LcncProgram("cancel", s_[LcncNode("effect", "test.effect")], emptySeriesOf())) }
        runCurrent()
        assertEquals("paused", debug.snapshot()["state"])
        job.cancelAndJoin()
        debug.close()
        assertEquals(0, calls)
        assertFalse(debug.command(1, LcncDebug.Command.CONTINUE))
    }
}
