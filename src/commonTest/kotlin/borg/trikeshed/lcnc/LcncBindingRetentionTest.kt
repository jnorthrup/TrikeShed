package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncBindingRetentionTest {
    @Test fun nestedServiceBindingsPreserveExecutedDataProjections() = runTest {
        for (type in listOf("merge", "coalesce", "pick")) {
            val sourcePort = when (type) { "pick" -> "x"; "coalesce" -> "b"; else -> "a" }
            val outputPort = if (type == "pick") "y" else "value"
            val program = LcncProgram("bound-$type", s_[
                LcncNode("producer", "scope", children = s_[
                    LcncNode("timer", "timer"),
                    LcncNode("text", "text.value", mapOf("value" to "retained")),
                    LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                    LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
                LcncNode("select", type, mapOf("path" to "")),
                LcncNode("button", "button"),
                LcncNode("consumer", "scope", children = s_[
                    LcncNode("inputEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                    LcncNode("inputPress", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                    LcncNode("effect", "test.effect")])], s_[
                LcncWire("timer", "tick", "edge", "value"), LcncWire("text", "value", "data", "value"),
                LcncWire("producer", "returns", "select", sourcePort),
                LcncWire("select", outputPort, "consumer", "args"),
                LcncWire("button", "press", "consumer", "press"),
                LcncWire("inputEdge", "value", "effect", "edge"),
                LcncWire("inputPress", "value", "effect", "press")])
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
            var sources = 0
            var effects = 0
            val registry = (PureNodes.registry { sources++; 1L } + CouncilNodes.registry(CouncilDialog {
                error("A data projection cannot invoke a model")
            })).toMutableMap()
            registry[type] = boundLcnc(CoroutineName("projection"),
                boundLcnc(AgentRunIdKey { error("A data projection cannot mint an agent run") }, registry.getValue(type)))
            registry["test.effect"] = LcncNodeRunner { _, _ -> effects++; emptyMap() }
            val runner = LcncRunner(registry)
            var result = runner.runProcedure(program)
            assertEquals(0, effects)
            assertEquals(mapOf("edge" to 1L, "data" to "retained"), result.nodeOutputs["select"]?.get(outputPort))
            repeat(2) { turn ->
                runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
                result = runner.runProcedure(program)
                assertEquals(0, effects, "$type must preserve event expiry through both service bindings")
                assertEquals(1, sources, "Projection must not dispatch the original source")
                assertEquals(mapOf(outputPort to mapOf("data" to "retained")), result.nodeOutputs["select"])
            }
        }
    }
}
