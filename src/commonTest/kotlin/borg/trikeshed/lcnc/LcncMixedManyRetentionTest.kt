package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncMixedManyRetentionTest {
    private fun source() = LcncNode("source", "scope", children = s_[
        LcncNode("timer", "timer"),
        LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
    ])

    private val eventWires = s_[
        LcncWire("timer", "tick", "edge", "value"),
        LcncWire("source", "returns", "pick", "x"),
    ]

    @Test fun anExpiredManyFeederDoesNotEraseOrdinaryTurnRecordsOnEitherSide() = runTest {
        val program = LcncProgram("mixed-many-records", s_[
            source(),
            LcncNode("pick", "pick", mapOf("path" to "edge")),
            LcncNode("first", "json.value", mapOf("value" to "{\"id\":\"first\",\"value\":null}")),
            LcncNode("last", "json.value", mapOf("value" to "[{\"id\":\"last\"}]")),
            LcncNode("fold", "record.fold"), LcncNode("button", "button"),
        ], combine(s_[eventWires, s_[
            LcncWire("first", "value", "fold", "parts"),
            LcncWire("pick", "y", "fold", "parts"),
            LcncWire("last", "value", "fold", "parts"),
        ]]))
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var sourceCalls = 0
        val runner = LcncRunner(PureNodes.registry { sourceCalls++; 1L } +
            CouncilNodes.registry(CouncilDialog { error("Retention cannot invoke a model") }))
        val records = listOf(mapOf("id" to "first", "value" to null), mapOf("id" to "last"))
        var result = runner.runProcedure(program)
        assertEquals(mapOf("y" to 1L), result.nodeOutputs["pick"])
        assertEquals(emptyMap(), result.retained.outputs["pick"], "The picked scalar was an expired event")
        assertEquals(mapOf("turns" to records), result.nodeOutputs["fold"],
            "The actual fold ignores the scalar while preserving both records in wire order")
        assertEquals(mapOf("turns" to records), result.retained.outputs["fold"],
            "One expired MANY feeder must not erase other feeders' ordinary data or explicit null")
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
            result = runner.runProcedure(program)
            assertEquals(mapOf("turns" to records), result.nodeOutputs["fold"])
            assertEquals(emptyMap(), result.nodeOutputs["pick"])
            assertEquals(1, sourceCalls, "An unrelated pulse must reuse the producer and its projection")
        }
    }

    @Test fun retainedManyArityFollowsTheActualFedCountWithoutFlatteningASurvivingList() = runTest {
        for (mixed in listOf(false, true)) {
            val wires = if (mixed) combine(s_[eventWires, s_[
                LcncWire("ordinary", "value", "observe", "x"), LcncWire("pick", "y", "observe", "x"),
            ]]) else combine(s_[eventWires, s_[LcncWire("ordinary", "value", "observe", "x")]])
            val program = LcncProgram("mixed-many-arity", s_[
                source(), LcncNode("pick", "pick", mapOf("path" to "edge")),
                LcncNode("ordinary", "json.value", mapOf("value" to "[null,{\"id\":\"ordinary\"}]")),
                LcncNode("observe", "display"), LcncNode("button", "button"),
            ], wires)
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
            val observer = object : LcncNodeRunner {
                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> = inputs
                override fun outputSources(node: LcncNode, inputs: Map<String, Any?>) = mapOf("x" to "x")
            }
            val runner = LcncRunner(PureNodes.registry { 1L } + ("display" to observer))
            val ordinary = listOf(null, mapOf("id" to "ordinary"))
            val actual: Any = if (mixed) listOf(ordinary, 1L) else ordinary
            val retained: Any = if (mixed) listOf(ordinary) else ordinary
            var result = runner.runProcedure(program)
            assertEquals(mapOf("x" to actual), result.nodeOutputs["observe"], "Execution inputs remain unchanged")
            assertEquals(mapOf("x" to retained), result.retained.outputs["observe"],
                "A single fed list stays scalar; two fed wires keep a list envelope after one expires")
            repeat(2) { turn ->
                runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
                result = runner.runProcedure(program)
                assertEquals(mapOf("x" to retained), result.nodeOutputs["observe"])
            }
        }
    }

    @Test fun anEntirelyExpiredManySelectionStaysAbsentInsteadOfBecomingAnEmptyFold() = runTest {
        val program = LcncProgram("expired-many-records", s_[
            source(), LcncNode("pick", "pick", mapOf("path" to "edge")),
            LcncNode("fold", "record.fold"), LcncNode("button", "button"),
        ], combine(s_[eventWires, s_[LcncWire("pick", "y", "fold", "parts")]]))
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L } +
            CouncilNodes.registry(CouncilDialog { error("Retention cannot invoke a model") }))
        var result = runner.runProcedure(program)
        assertEquals(mapOf("turns" to emptyList<Any?>()), result.nodeOutputs["fold"],
            "This records the actual scalar-ignoring fold before retention")
        assertEquals(emptyMap(), result.retained.outputs["fold"], "An absent selected projection cannot invent a retained empty turn list")
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
            result = runner.runProcedure(program)
            assertEquals(emptyMap(), result.nodeOutputs["fold"])
        }
    }
}
