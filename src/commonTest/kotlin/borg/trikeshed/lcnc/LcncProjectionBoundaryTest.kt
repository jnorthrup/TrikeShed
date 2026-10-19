package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncProjectionBoundaryTest {
    @Test fun cyclicAndDeepOutputsKeepActualValuesWithoutExportingPartialPulseState() = runTest {
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["self"] = cyclic
        var deep: Any? = "leaf"
        repeat(96) { deep = listOf(deep) }
        for (value in listOf(cyclic, deep)) {
            val actual = mapOf("value" to value)
            var calls = 0
            val runner = LcncRunner(PureNodes.registry { 1L } +
                ("test.output" to LcncNodeRunner { _, _ -> calls++; actual }))
            val program = LcncProgram("projection-boundary", s_[
                LcncNode("source", "test.output"), LcncNode("button", "button")], emptySeriesOf())
            val completed = runner.runProcedure(program)
            assertSame(actual, completed.nodeOutputs["source"])
            assertSame(value, completed.nodeOutputs.getValue("source")["value"])
            assertEquals("depth_limit", completed.retained.unavailable)
            assertTrue(completed.retained.outputs.isEmpty())
            assertTrue(completed.retained.bindings.isEmpty())
            runner.pulse = LcncPulse("button", mapOf("press" to 2L), completed.retained)
            val failure = assertFailsWith<IllegalArgumentException> { runner.runProcedure(program) }
            assertEquals("pulse_state_unavailable", failure.message)
            assertEquals(1, calls, "Refusing an unavailable frame cannot rerun its producer")
        }
    }
}
