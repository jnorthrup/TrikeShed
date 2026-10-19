package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncManyRetentionTest {
    @Test fun recordFoldRetainsTheFlattenedDataProjectionOfEveryExecutedManyInput() = runTest {
        val program = LcncProgram("many-retention", s_[
            LcncNode("source", "scope", children = s_[
                LcncNode("timer", "timer"),
                LcncNode("label", "text.value", mapOf("value" to "event")),
                LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("id", "scope.out", mapOf("name" to "id", "kind" to "text"))]),
            LcncNode("ordinary", "json.value", mapOf("value" to "[{\"id\":\"ordinary\"}]")),
            LcncNode("fold", "record.fold"),
            LcncNode("button", "button")], s_[
            LcncWire("timer", "tick", "edge", "value"),
            LcncWire("label", "value", "id", "value"),
            LcncWire("source", "returns", "fold", "parts"),
            LcncWire("ordinary", "value", "fold", "parts")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L } + CouncilNodes.registry(CouncilDialog {
            error("Projection cannot invoke a model")
        }))
        val base = runner.runProcedure(program)
        assertEquals(listOf(mapOf("edge" to 1L, "id" to "event"), mapOf("id" to "ordinary")),
            base.nodeOutputs["fold"]?.get("turns"), "The receipt still describes both actual inputs in wire order")
        var retained = base.retained
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), retained)
            val next = runner.runProcedure(program)
            assertEquals(listOf(mapOf("id" to "event"), mapOf("id" to "ordinary")),
                next.nodeOutputs["fold"]?.get("turns"),
                "Flattening must preserve ordinary records without retaining a nested event or a nested list")
            retained = next.retained
        }
    }
}
