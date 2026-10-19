package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncWarmAliasAdmissionTest {
    private fun warmProgram() = LcncProgram("warm-binding-admission", s_[
        LcncNode("alias", "scope.in", mapOf("name" to "edge")),
        LcncNode("merge", "merge"),
        LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")),
        LcncNode("first", "button"),
        LcncNode("gate", "scope", children = s_[
            LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
            LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))]),
        LcncNode("other", "button"),
        LcncNode("effect", "test.effect")], s_[
        LcncWire("alias", "value", "merge", "a"),
        LcncWire("first", "press", "gate", "activation"),
        LcncWire("first", "press", "effect", "press")])

    @Test fun aNewAmbientEventReadRefusesBeforeTheSourceGateOrEffectsEnter() = runTest {
        val program = warmProgram()
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var sources = 0
        var gates = 0
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
            "button" to LcncNodeRunner { _, _ -> sources++; emptyMap() },
            "test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() },
        )).apply { onScopeEnter = { _, _ -> gates++ } }
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("value" to 1L), base.nodeOutputs["merge"])
        assertEquals(args, base.retained.bindings)
        assertEquals(2, sources)
        assertEquals(0, gates)
        assertEquals(0, effects)
        runner.pulse = LcncPulse("first", mapOf("press" to 2L), base.retained)
        val failure = assertFailsWith<IllegalArgumentException> { runner.runProcedure(program, args) }
        assertEquals("pulse_binding_origin_unavailable", failure.message)
        assertEquals(2, sources, "Refusal must precede the selected control runner")
        assertEquals(0, gates, "The new typed read must never enter its ring")
        assertEquals(0, effects, "Refusal must precede every pulse effect")
    }

    @Test fun anUnselectedTypedDeclarationDoesNotRefuseAnOrdinaryBinding() = runTest {
        val program = warmProgram()
        var gates = 0
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() })).apply {
            onScopeEnter = { _, _ -> gates++ }
        }
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        val base = runner.runProcedure(program, args)
        runner.pulse = LcncPulse("other", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(args, next.retained.bindings)
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["merge"])
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["ordinary"])
        assertEquals(0, gates)
        assertEquals(0, effects)
    }

    @Test fun aDefinitePerNameFeedShadowsTheSameNamedRetainedRootBinding() = runTest {
        val program = LcncProgram("warm-binding-shadow", s_[
            LcncNode("ordinary", "scope.in", mapOf("name" to "edge", "kind" to "num")),
            LcncNode("button", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("button", "press", "gate", "activation"),
            LcncWire("button", "press", "gate", "edge"),
            LcncWire("edge", "value", "effect", "edge"),
            LcncWire("activation", "value", "effect", "activation")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        var gates = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, inputs ->
                assertEquals(2L, inputs["edge"])
                effects++
                emptyMap()
            })).apply { onScopeEnter = { _, _ -> gates++ } }
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(0, gates)
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(1, gates)
        assertEquals(1, effects, "The fresh per-name trigger remains executable")
        assertEquals(args, next.retained.bindings)
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["ordinary"])
    }

    @Test fun aSelectedNamedBodyCannotGuessBindingOriginsOrLoadDuringAdmission() = runTest {
        val body = LcncProgram("hidden", s_[
            LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
            LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))], emptySeriesOf())
        val program = LcncProgram("warm-binding-named", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("button", "button"),
            LcncNode("named", "scope", subprogram = "hidden")],
            s_[LcncWire("button", "press", "named", "activation")])
        var loads = 0
        var sources = 0
        var entries = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("button" to LcncNodeRunner { _, _ -> sources++; emptyMap() })).apply {
            subprogramLoader = { loads++; body }
            onScopeEnter = { _, _ -> entries++ }
        }
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(1, loads)
        assertEquals(1, sources)
        assertEquals(0, entries)
        runner.subprogramLoader = { error("Admission cannot load the selected body") }
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val failure = assertFailsWith<IllegalArgumentException> { runner.runProcedure(program, args) }
        assertEquals("pulse_binding_origin_unavailable", failure.message)
        assertEquals(1, sources)
        assertEquals(1, loads)
        assertEquals(0, entries)
    }
}
