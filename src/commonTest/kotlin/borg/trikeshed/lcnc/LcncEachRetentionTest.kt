package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncEachRetentionTest {
    @Test fun numericPickRetainsOriginalListSlotsUntilTheFinalProjectionIsExported() = runTest {
        val program = LcncProgram("retained-pick-slots", s_[
            LcncNode("feed", "vm.events"),
            LcncNode("ordinary", "json.value", mapOf("value" to "{\"id\":\"same\"}")),
            LcncNode("fold", "record.fold"),
            LcncNode("first", "pick", mapOf("path" to "0")),
            LcncNode("second", "pick", mapOf("path" to "1")),
            LcncNode("whole", "pick", mapOf("path" to "")),
            LcncNode("other", "button")], s_[
            LcncWire("feed", "event", "fold", "parts"),
            LcncWire("ordinary", "value", "fold", "parts"),
            LcncWire("fold", "turns", "first", "x"),
            LcncWire("fold", "turns", "second", "x"),
            LcncWire("fold", "turns", "whole", "x")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L } + CouncilNodes.registry(CouncilDialog {
            error("Projection cannot invoke a model")
        }))
        val ordinary = mapOf("id" to "same")
        val base = runner.runProcedure(program)
        assertEquals(mapOf("y" to ordinary), base.nodeOutputs["first"])
        assertEquals(mapOf("y" to null), base.nodeOutputs["second"])
        assertEquals(mapOf("y" to null), base.retained.outputs["second"],
            "An originally out-of-range index computed ordinary null rather than an expired event")
        assertEquals(mapOf("y" to listOf(ordinary)), base.nodeOutputs["whole"])
        runner.pulse = LcncPulse("feed", mapOf("event" to ordinary), base.retained)
        val event = runner.runProcedure(program)
        assertEquals(listOf(ordinary, ordinary), event.nodeOutputs["fold"]?.get("turns"))
        assertEquals(mapOf("y" to ordinary), event.nodeOutputs["first"])
        assertEquals(mapOf("y" to ordinary), event.nodeOutputs["second"])
        assertEquals(mapOf("y" to listOf(ordinary, ordinary)), event.nodeOutputs["whole"])
        assertEquals(emptyMap(), event.retained.outputs["first"],
            "Original slot zero expired even though slot one contains an equal ordinary record")
        assertEquals(mapOf("y" to ordinary), event.retained.outputs["second"],
            "Removing an earlier slot cannot make the original second record disappear")
        assertEquals(mapOf("y" to listOf(ordinary)), event.retained.outputs["whole"],
            "Whole-list export compacts absent slots only after every indexed projection is computed")
        runner.pulse = LcncPulse("other", mapOf("press" to 2L), event.retained)
        val reused = runner.runProcedure(program)
        assertEquals(emptyMap(), reused.nodeOutputs["first"])
        assertEquals(mapOf("y" to ordinary), reused.nodeOutputs["second"])
        assertEquals(mapOf("y" to listOf(ordinary)), reused.nodeOutputs["whole"])
    }

    @Test fun anExpiredRecordDoesNotTransferTheNextRecordsProjectionToItsIteration() = runTest {
        val program = LcncProgram("retained-list-slots", s_[
            LcncNode("feed", "vm.events"),
            LcncNode("ordinary", "json.value", mapOf("value" to "{\"id\":\"same\"}")),
            LcncNode("fold", "record.fold"),
            LcncNode("loop", "scope", mapOf("item" to "record"), children = s_[
                LcncNode("record", "scope.in", mapOf("name" to "record")),
                LcncNode("payload", "scope.out", mapOf("name" to "payload", "kind" to "json"))]),
            LcncNode("last", "pick", mapOf("path" to "1.payload")),
            LcncNode("other", "button")], s_[
            LcncWire("feed", "event", "fold", "parts"),
            LcncWire("ordinary", "value", "fold", "parts"),
            LcncWire("fold", "turns", "loop", "each"),
            LcncWire("record", "value", "payload", "value"),
            LcncWire("loop", "returns", "last", "x")])
        val violations = LcncTypeCheck.check(program)
        assertTrue(violations.isEmpty(), violations.joinToString { it.render() })
        var entries = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + CouncilNodes.registry(CouncilDialog {
            error("Projection cannot invoke a model")
        })).apply { onScopeEnter = { _, _ -> entries++ } }
        val ordinary = mapOf("id" to "same")
        val base = runner.runProcedure(program)
        assertEquals(listOf(mapOf("payload" to ordinary)), base.nodeOutputs["loop"]?.get("returns"))
        runner.pulse = LcncPulse("feed", mapOf("event" to ordinary), base.retained)
        val event = runner.runProcedure(program)
        assertEquals(listOf(ordinary, ordinary), event.nodeOutputs["fold"]?.get("turns"))
        assertEquals(listOf(mapOf("payload" to ordinary), mapOf("payload" to ordinary)),
            event.nodeOutputs["loop"]?.get("returns"), "The event receipt preserves both actual iterations")
        assertEquals(3, entries, "Retaining projections cannot enter the body or execute either iteration again")
        val expected = listOf(emptyMap<String, Any?>(), mapOf("payload" to ordinary))
        assertEquals(expected, event.retained.outputs["loop"]?.get("returns"),
            "Equal values cannot move the surviving ordinary record into the expired event's iteration")
        assertEquals(listOf(ordinary), event.retained.outputs["loop"]?.get("payload"),
            "An expired yield cannot erase another iteration's ordinary named yield")
        assertEquals(mapOf("y" to ordinary), event.retained.outputs["last"],
            "A named yield retains original occurrence positions during the walk")
        runner.pulse = LcncPulse("other", mapOf("press" to 2L), event.retained)
        val reused = runner.runProcedure(program)
        assertEquals(expected, reused.nodeOutputs["loop"]?.get("returns"))
        assertEquals(3, entries, "An unrelated pulse reuses the retained loop rather than reconstructing it")
    }
}
