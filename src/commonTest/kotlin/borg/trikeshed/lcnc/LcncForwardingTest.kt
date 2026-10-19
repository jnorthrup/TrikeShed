package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncForwardingTest {
    @Test fun unrelatedPulsesRetainPickResultsForNullMissingAndOrdinaryData() = runTest {
        val program = LcncProgram("pick-data", s_[
            LcncNode("object", "json.value", mapOf("value" to "{\"value\":null,\"edge\":1}")),
            LcncNode("list", "json.value", mapOf("value" to "[null]")),
            LcncNode("nullField", "pick", mapOf("path" to "value")),
            LcncNode("missingField", "pick", mapOf("path" to "missing")),
            LcncNode("ordinaryEdge", "pick", mapOf("path" to "edge")),
            LcncNode("whole", "pick", mapOf("path" to "")),
            LcncNode("nullIndex", "pick", mapOf("path" to "0")),
            LcncNode("button", "button")], s_[
            LcncWire("object", "value", "nullField", "x"),
            LcncWire("object", "value", "missingField", "x"),
            LcncWire("object", "value", "ordinaryEdge", "x"),
            LcncWire("object", "value", "whole", "x"),
            LcncWire("list", "value", "nullIndex", "x")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L })
        val base = runner.runProcedure(program)
        for (id in s_["nullField", "missingField", "nullIndex"].view)
            assertEquals(mapOf("y" to null), base.nodeOutputs[id])
        assertEquals(1, assertIs<Number>(base.nodeOutputs.getValue("ordinaryEdge")["y"]).toInt())
        assertEquals(base.nodeOutputs["object"]?.get("value"), base.nodeOutputs["whole"]?.get("y"))
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program)
        for (id in s_["nullField", "missingField", "ordinaryEdge", "whole", "nullIndex"].view)
            assertEquals(base.nodeOutputs[id], next.nodeOutputs[id], "$id is ordinary data, including explicit null")
    }

    @Test fun pickRetainsTheSelectedDataProjectionWithoutReplayingItsNestedTrigger() = runTest {
        val program = LcncProgram("pick-projection", s_[
            LcncNode("producer", "scope", children = s_[
                LcncNode("bundle", "scope", children = s_[
                    LcncNode("timer", "timer"),
                    LcncNode("text", "text.value", mapOf("value" to "retained")),
                    LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                    LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
                LcncNode("payload", "scope.out", mapOf("name" to "payload", "kind" to "json"))]),
            LcncNode("pick", "pick", mapOf("path" to "payload")),
            LcncNode("button", "button"),
            LcncNode("consumer", "scope", children = s_[
                LcncNode("inputEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("inputPress", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("timer", "tick", "edge", "value"),
            LcncWire("text", "value", "data", "value"),
            LcncWire("bundle", "returns", "payload", "value"),
            LcncWire("producer", "returns", "pick", "x"),
            LcncWire("pick", "y", "consumer", "args"),
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("inputEdge", "value", "effect", "edge"),
            LcncWire("inputPress", "value", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var sourceCalls = 0
        val effects = mutableListOf<Map<String, Any?>>()
        val runner = LcncRunner(PureNodes.registry { sourceCalls++; 1L } +
            ("test.effect" to LcncNodeRunner { _, inputs -> effects.add(inputs); emptyMap() }))
        var completed = runner.runProcedure(program)
        val data = mapOf("data" to "retained")
        val envelope = mapOf("payload" to data)
        val projectedProducer = mapOf("payload" to data, "returns" to envelope)
        assertEquals(mapOf("y" to mapOf("edge" to 1L, "data" to "retained")), completed.nodeOutputs["pick"])
        assertEquals(projectedProducer, completed.retained.outputs["producer"],
            "The scope already expired its event before the structural adapter forwarded it")
        assertTrue(effects.isEmpty())
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), completed.retained)
            completed = runner.runProcedure(program)
            assertEquals(1, sourceCalls, "An unrelated control cannot rerun the producer")
            assertEquals(projectedProducer, completed.nodeOutputs["producer"])
            assertTrue(effects.isEmpty(), "pick must not replay an expired nested edge into a fresh press: $effects")
            assertEquals(mapOf("y" to data), completed.nodeOutputs["pick"],
                "Selecting the retained projection must preserve the ordinary payload")
        }
    }
}
