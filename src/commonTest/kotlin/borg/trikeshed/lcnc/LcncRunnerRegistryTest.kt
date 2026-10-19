package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncRunnerRegistryTest {
    @Test fun aSelectedHandlerCannotTurnAnAdmittedLeafIntoAHiddenBody() = runTest {
        val hidden = LcncProgram("hidden", s_[
            LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
            LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))], emptySeriesOf())
        val program = LcncProgram("registry-admission", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("button", "button"),
            LcncNode("mutator", "test.mutate"),
            LcncNode("leaf", "hidden")], s_[
            LcncWire("button", "press", "mutator", "press"),
            LcncWire("mutator", "value", "leaf", "activation")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var mutations = 0
        var leaves = 0
        var loads = 0
        var entries = 0
        val registry = PureNodes.registry { 1L }.toMutableMap()
        registry["test.mutate"] = LcncNodeRunner { _, inputs ->
            assertNotNull(registry.remove("hidden"))
            mutations++
            mapOf("value" to inputs.getValue("press"))
        }
        registry["hidden"] = LcncNodeRunner { _, inputs ->
            assertEquals(2L, inputs["activation"])
            leaves++
            mapOf("value" to "admitted")
        }
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { loads++; hidden }
            onScopeEnter = { _, _ -> entries++ }
        }
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(args, base.retained.bindings)
        assertEquals(0, mutations)
        assertEquals(0, leaves)
        assertEquals(0, loads)
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(1, mutations, "The earlier selected handler really removed the public registration")
        assertFalse(registry.containsKey("hidden"))
        assertEquals(0, loads, "Dispatch cannot reinterpret an admitted leaf as an ambient-reading body")
        assertEquals(0, entries)
        assertEquals(1, leaves, "The frozen admitted handler must actually run")
        assertEquals(mapOf("value" to "admitted"), next.nodeOutputs["leaf"])
        assertEquals(args, next.retained.bindings, "The hidden typed read must not expire the ordinary root binding")
    }
}
