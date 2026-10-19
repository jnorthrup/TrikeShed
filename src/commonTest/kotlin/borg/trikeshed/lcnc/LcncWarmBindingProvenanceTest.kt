package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Warm frame occurrences retain their binding identity across root pulses. */
class LcncWarmBindingProvenanceTest {
    @Test fun aPureWarmReadRemovesCachedAliasesFromItsNextFrameBeforeAnotherGesture() = runTest {
        val program = LcncProgram("warm-binding-provenance", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("merge", "merge"),
            LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")),
            LcncNode("first", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))]),
            LcncNode("second", "button"),
            LcncNode("consumer", "scope", children = s_[
                LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("alias", "value", "merge", "a"),
            LcncWire("first", "press", "gate", "activation"),
            LcncWire("merge", "value", "consumer", "edge"),
            LcncWire("second", "press", "consumer", "press"),
            LcncWire("edge", "value", "effect", "edge"),
            LcncWire("press", "value", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        var gateEntries = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() })).apply {
            onScopeEnter = { path, _ -> if (path.lastOrNull() == "gate") gateEntries++ }
        }
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("value" to 1L), base.nodeOutputs["alias"])
        assertEquals(mapOf("value" to 1L), base.nodeOutputs["merge"])
        assertEquals(args, base.retained.bindings)
        assertFalse(base.nodeOutputs.containsKey("gate"))
        assertEquals(0, gateEntries)
        assertEquals(0, effects)

        runner.pulse = LcncPulse("first", mapOf("press" to 2L), base.retained)
        val typed = runner.runProcedure(program, args)
        assertEquals(1, gateEntries)
        assertEquals(mapOf("returns" to emptyMap<String, Any?>()), typed.nodeOutputs["gate"])
        assertEquals(mapOf("value" to 1L), typed.nodeOutputs["alias"], "Actual reused data remains execution evidence")
        assertEquals(mapOf("value" to 1L), typed.nodeOutputs["merge"])
        assertEquals(emptyMap(), typed.retained.outputs["alias"], "Repair is complete before this frame can be saved")
        assertEquals(emptyMap(), typed.retained.outputs["merge"])
        assertEquals(mapOf("ordinary" to 1L), typed.retained.bindings)
        assertEquals(mapOf("value" to 1L), typed.retained.outputs["ordinary"])
        assertEquals(0, effects, "The selected first subtree contains only bindings")

        runner.pulse = LcncPulse("second", mapOf("press" to 3L), typed.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(0, effects)
        assertEquals(emptyMap(), next.nodeOutputs["alias"])
        assertEquals(emptyMap(), next.nodeOutputs["merge"])
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["ordinary"])
        assertEquals(mapOf("ordinary" to 1L), next.retained.bindings)
        assertEquals(1, gateEntries, "An inactive reader is not rerun to reconstruct origins")
    }

    @Test fun aGenuinelySkippedSelectedPureReaderKeepsTheOrdinaryBindingAndItsAliases() = runTest {
        val program = LcncProgram("warm-binding-skipped-provenance", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("merge", "merge"),
            LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")),
            LcncNode("button", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("condition", "json.value", mapOf("value" to "false")),
                LcncNode("skipped", "scope", children = s_[
                    LcncNode("unreadEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))])])], s_[
            LcncWire("alias", "value", "merge", "a"),
            LcncWire("button", "press", "gate", "activation"),
            LcncWire("condition", "value", "skipped", "when")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val entered = ArrayList<String>()
        val runner = LcncRunner(PureNodes.registry { 1L }).apply {
            onScopeEnter = { path, _ -> entered.add(path.last()) }
        }
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        var completed = runner.runProcedure(program, args)
        assertTrue(entered.isEmpty(), "The outer required activation was initially absent")
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), completed.retained)
            completed = runner.runProcedure(program, args)
            assertEquals(List(turn + 1) { "gate" }, entered)
            assertTrue(completed.bindings.none { it["nodeId"] == "unreadEdge" }, "A declaration is not an observed event read")
            assertEquals(args, completed.retained.bindings)
            assertEquals(mapOf("value" to 1L), completed.retained.outputs["alias"])
            assertEquals(mapOf("value" to 1L), completed.retained.outputs["merge"])
            assertEquals(mapOf("value" to 1L), completed.retained.outputs["ordinary"])
        }
    }
}
