package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncGroupRetentionTest {
    private fun consumer() = LcncNode("consumer", "scope", children = s_[
        LcncNode("inputEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
        LcncNode("inputPress", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
        LcncNode("effect", "test.effect"),
    ])

    private val consumeWires = s_[
        LcncWire("pick", "y", "consumer", "args"),
        LcncWire("button", "press", "consumer", "press"),
        LcncWire("inputEdge", "value", "effect", "edge"),
        LcncWire("inputPress", "value", "effect", "press"),
    ]

    @Test fun groupedRowsExpireNestedEventsWithoutChangingTheExecutedBucket() = runTest {
        // The second key itself expires. Re-grouping retained rows would move its bucket to "".
        for (key in listOf("group", "edge")) {
            val program = LcncProgram("grouped-events", s_[
                LcncNode("items", "json.value", mapOf("value" to "[1,2]")),
                LcncNode("producer", "scope", children = s_[
                    LcncNode("timer", "timer"),
                    LcncNode("category", "text.value", mapOf("value" to "team")),
                    LcncNode("text", "text.value", mapOf("value" to "retained")),
                    LcncNode("null", "json.value", mapOf("value" to "null")),
                    LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                    LcncNode("group", "scope.out", mapOf("name" to "group", "kind" to "text")),
                    LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text")),
                    LcncNode("value", "scope.out", mapOf("name" to "value", "kind" to "json")),
                ]),
                LcncNode("grouped", "list.groupBy", mapOf("key" to key)),
                LcncNode("pick", "pick", mapOf("path" to (if (key == "group") "team.0" else "1.0"))),
                LcncNode("button", "button"), consumer(),
            ], combine(s_[consumeWires, s_[
                LcncWire("items", "value", "producer", "each"),
                LcncWire("timer", "tick", "edge", "value"),
                LcncWire("category", "value", "group", "value"),
                LcncWire("text", "value", "data", "value"),
                LcncWire("null", "value", "value", "value"),
                LcncWire("producer", "returns", "grouped", "x"),
                LcncWire("grouped", "groups", "pick", "x"),
            ]]))
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
            var sources = 0
            var effects = 0
            val runner = LcncRunner(PureNodes.registry { sources++; 1L } +
                ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
            val data: Map<String, Any?> = mapOf("group" to "team", "data" to "retained", "value" to null)
            val actual = data + ("edge" to 1L)
            val bucket = if (key == "group") "team" else "1"
            val retained = mapOf("groups" to mapOf(bucket to listOf(data, data)))
            var result = runner.runProcedure(program)
            val initialRetained = result.retained
            assertEquals(mapOf("groups" to mapOf(bucket to listOf(actual, actual))), result.nodeOutputs["grouped"])
            assertEquals(mapOf("y" to actual), result.nodeOutputs["pick"])
            assertEquals(0, effects)
            repeat(2) { turn ->
                runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
                result = runner.runProcedure(program)
                assertEquals(0, effects, "Grouping must not restore a nested edge into a fresh button press")
                assertEquals(retained, result.nodeOutputs["grouped"])
                assertEquals(mapOf("y" to data), result.nodeOutputs["pick"])
                assertEquals(2, sources, "Only the original two each iterations invoke the source")
            }
            assertEquals(retained, initialRetained.outputs["grouped"],
                "Retention must project the executed groups, keep their bucket, and preserve nulls")
            assertEquals(mapOf("y" to data), initialRetained.outputs["pick"])
        }
    }

    @Test fun anExpiredGroupedRowDoesNotRetargetPickToTheNextOrdinaryRow() = runTest {
        val program = LcncProgram("grouped-slots", s_[
            LcncNode("feed", "graal.events"),
            LcncNode("ordinary", "json.value", mapOf("value" to "{\"group\":\"team\",\"id\":\"ordinary\",\"value\":null}")),
            LcncNode("fold", "record.fold"),
            LcncNode("grouped", "list.groupBy", mapOf("key" to "group")),
            LcncNode("pick", "pick", mapOf("path" to "team.0")),
            LcncNode("button", "button"), consumer(),
        ], combine(s_[consumeWires, s_[
            LcncWire("feed", "event", "fold", "parts"),
            LcncWire("ordinary", "value", "fold", "parts"),
            LcncWire("fold", "turns", "grouped", "x"),
            LcncWire("grouped", "groups", "pick", "x"),
        ]]))
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            CouncilNodes.registry(CouncilDialog { error("Projection cannot invoke a model") }) +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val ordinary: Map<String, Any?> = mapOf("group" to "team", "id" to "ordinary", "value" to null)
        val event: Map<String, Any?> = mapOf("group" to "team", "id" to "transient", "edge" to 1L)
        var result = runner.runProcedure(program)
        runner.pulse = LcncPulse("feed", mapOf("event" to event), result.retained)
        result = runner.runProcedure(program)
        assertEquals(mapOf("turns" to listOf(event, ordinary)), result.nodeOutputs["fold"])
        assertEquals(mapOf("groups" to mapOf("team" to listOf(event, ordinary))), result.nodeOutputs["grouped"])
        assertEquals(mapOf("y" to event), result.nodeOutputs["pick"], "The original pick selected the event row")
        assertEquals(mapOf("turns" to listOf(ordinary)), result.retained.outputs["fold"])
        assertEquals(mapOf("groups" to mapOf("team" to listOf(ordinary))), result.retained.outputs["grouped"])
        assertEquals(emptyMap(), result.retained.outputs["pick"],
            "Internal grouped slots must keep the missing first row until pick has projected its actual selection")
        assertEquals(0, effects)
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
            result = runner.runProcedure(program)
            assertEquals(emptyMap(), result.nodeOutputs["pick"])
            assertEquals(mapOf("groups" to mapOf("team" to listOf(ordinary))), result.nodeOutputs["grouped"])
            assertEquals(0, effects, "An expired event row must not survive or silently become ordinary row zero")
        }
    }
}
