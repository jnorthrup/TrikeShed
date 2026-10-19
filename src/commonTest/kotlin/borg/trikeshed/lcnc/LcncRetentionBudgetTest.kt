package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncRetentionBudgetTest {
    @Test fun excessiveFedWireRetentionDoesNotChangeTheActualComputedRunOrAuthorizeAPulse() = runTest {
        val program = LcncProgram("retained-feeder-budget", s_[
            LcncNode("source", "test.source"),
            LcncNode("compute", "test.compute"),
            LcncNode("button", "button")],
            65537 j { LcncWire("source", "value", "compute", "x") })
        var sources = 0
        var computations = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
            "test.source" to LcncNodeRunner { _, _ -> sources++; mapOf("value" to 1L) },
            "test.compute" to LcncNodeRunner { _, inputs ->
                computations++
                assertEquals(1L, inputs["x"])
                mapOf("value" to "computed")
            },
        )).apply { maxNodeExecutions = 3 }
        val completed = runner.runProcedure(program)
        assertEquals(1, sources)
        assertEquals(1, computations)
        assertEquals(mapOf("value" to 1L), completed.nodeOutputs["source"])
        assertEquals(mapOf("value" to "computed"), completed.nodeOutputs["compute"])
        assertEquals(3, completed.nodeOutputs.size, "Projection admission must not narrow or repeat actual execution")
        assertEquals("work_limit", completed.retained.unavailable)
        assertTrue(completed.retained.outputs.isEmpty(), "A disabled projection cannot export a partial frame")
        assertTrue(completed.retained.bindings.isEmpty())
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), completed.retained)
        val refusal = assertFailsWith<IllegalArgumentException> { runner.runProcedure(program) }
        assertEquals("pulse_state_unavailable", refusal.message)
        assertEquals(1, sources)
        assertEquals(1, computations)
    }
}
