package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncWarmOriginProjectionTest {
    @Test fun anIgnoredOrdinaryManyScalarCannotKeepAnExpiredFoldedMapPresent() = runTest {
        val program = LcncProgram("warm-fold-map-presence", s_[
            LcncNode("feed", "graal.events"),
            LcncNode("ordinary", "json.value", mapOf("value" to "42")),
            LcncNode("fold", "record.fold"), LcncNode("button", "button")], s_[
            LcncWire("feed", "event", "fold", "parts"),
            LcncWire("ordinary", "value", "fold", "parts")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        var sources = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            CouncilNodes.registry(CouncilDialog { error("No model belongs to this construction") }) +
            ("graal.events" to LcncNodeRunner { _, _ -> sources++; emptyMap() }))
        var result = runner.runProcedure(program)
        assertEquals(mapOf("turns" to emptyList<Any?>()), result.retained.outputs["fold"],
            "An ordinary scalar-only input remains the actual empty fold")
        val event: Map<String, Any?> = mapOf("id" to "transient", "value" to null)
        runner.pulse = LcncPulse("feed", mapOf("event" to event), result.retained)
        result = runner.runProcedure(program)
        assertEquals(mapOf("turns" to listOf(event)), result.nodeOutputs["fold"])
        assertEquals(emptyMap(), result.retained.outputs["fold"],
            "The ordinary scalar is ignored by the fold; it cannot preserve an expired actual map row")
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
            result = runner.runProcedure(program)
            assertEquals(emptyMap(), result.nodeOutputs["fold"])
            assertEquals(2, sources, "Only the base observation and fresh feed pulse invoke the source")
        }
    }

    @Test fun anExpiredGroupBucketStaysEmptyWhileItsOrdinarySiblingSurvives() = runTest {
        val program = LcncProgram("warm-group-bucket-presence", s_[
            LcncNode("feed", "graal.events"),
            LcncNode("ordinary", "json.value", mapOf("value" to "{\"group\":\"safe\",\"id\":\"ordinary\",\"value\":null}")),
            LcncNode("fold", "record.fold"),
            LcncNode("grouped", "list.groupBy", mapOf("key" to "group")),
            LcncNode("pick", "pick", mapOf("path" to "gone.0")),
            LcncNode("button", "button")], s_[
            LcncWire("feed", "event", "fold", "parts"), LcncWire("ordinary", "value", "fold", "parts"),
            LcncWire("fold", "turns", "grouped", "x"), LcncWire("grouped", "groups", "pick", "x")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        var sources = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            CouncilNodes.registry(CouncilDialog { error("No model belongs to this construction") }) +
            ("graal.events" to LcncNodeRunner { _, _ -> sources++; emptyMap() }))
        var result = runner.runProcedure(program)
        val event: Map<String, Any?> = mapOf("group" to "gone", "id" to "transient")
        val ordinary: Map<String, Any?> = mapOf("group" to "safe", "id" to "ordinary", "value" to null)
        runner.pulse = LcncPulse("feed", mapOf("event" to event), result.retained)
        result = runner.runProcedure(program)
        assertEquals(mapOf("groups" to mapOf("gone" to listOf(event), "safe" to listOf(ordinary))), result.nodeOutputs["grouped"])
        assertEquals(mapOf("y" to event), result.nodeOutputs["pick"])
        val retained = mapOf("groups" to mapOf("gone" to emptyList<Any?>(), "safe" to listOf(ordinary)))
        assertEquals(retained, result.retained.outputs["grouped"], "The executed bucket key remains; only its event row expires")
        assertEquals(emptyMap(), result.retained.outputs["pick"], "The actual expired row cannot turn into a missing-path null")
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), result.retained)
            result = runner.runProcedure(program)
            assertEquals(retained, result.nodeOutputs["grouped"])
            assertEquals(emptyMap(), result.nodeOutputs["pick"])
            assertEquals(2, sources)
        }
    }

    @Test fun boundNullAndAnOriginallyMissingPickPathSurviveAnotherBindingExpiry() = runTest {
        val program = LcncProgram("warm-null-and-missing-origin", s_[
            LcncNode("boundNull", "scope.in", mapOf("name" to "nullable", "kind" to "json")),
            LcncNode("payload", "scope.in", mapOf("name" to "payload", "kind" to "json")),
            LcncNode("present", "pick", mapOf("path" to "present")),
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("missing", "pick", mapOf("path" to "absent")),
            LcncNode("first", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger"))]),
            LcncNode("other", "button")], s_[
            LcncWire("payload", "value", "present", "x"),
            LcncWire("alias", "value", "missing", "x"),
            LcncWire("first", "press", "gate", "activation")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val args = mapOf("nullable" to null, "payload" to mapOf("present" to null), "edge" to 1L)
        val runner = LcncRunner(PureNodes.registry { 1L })
        var result = runner.runProcedure(program, args)
        assertEquals(mapOf("value" to null), result.nodeOutputs["boundNull"])
        assertEquals(mapOf("y" to null), result.nodeOutputs["present"])
        assertEquals(mapOf("y" to null), result.nodeOutputs["missing"], "A missing scalar path originally computed ordinary null")
        runner.pulse = LcncPulse("first", mapOf("press" to 2L), result.retained)
        result = runner.runProcedure(program, args)
        assertEquals(args - "edge", result.retained.bindings)
        assertEquals(emptyMap(), result.retained.outputs["alias"])
        for (id in listOf("present", "missing")) assertEquals(mapOf("y" to null), result.retained.outputs[id], id)
        assertEquals(mapOf("value" to null), result.retained.outputs["boundNull"])
        repeat(2) { turn ->
            runner.pulse = LcncPulse("other", mapOf("press" to turn + 3L), result.retained)
            result = runner.runProcedure(program, args)
            assertEquals(args - "edge", result.retained.bindings)
            assertEquals(mapOf("value" to null), result.nodeOutputs["boundNull"])
            for (id in listOf("present", "missing")) assertEquals(mapOf("y" to null), result.nodeOutputs[id], id)
        }
    }
}
