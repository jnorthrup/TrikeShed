package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncPulseTest {
    @Test fun forwardedNumericAliasesKeepTheirBindingOriginAcrossRootEachAndCarry() = runTest {
        for (mode in s_["root", "each", "carry"].view) {
            val nodes = mutableListOf<LcncNode>()
            val wires = mutableListOf<LcncWire>()
            val alias = LcncNode("alias", "scope.in", mapOf("name" to "edge"))
            val merge = LcncNode("merge", "merge")
            wires.add(LcncWire("alias", "value", "merge", "a"))
            if (mode == "root") {
                nodes.add(alias)
                nodes.add(merge)
                wires.add(LcncWire("merge", "value", "pick", "x"))
            } else {
                if (mode == "each") {
                    nodes.add(LcncNode("items", "json.value", mapOf("value" to "[1,2]")))
                    wires.add(LcncWire("items", "value", "ring", "each"))
                }
                nodes.add(LcncNode("ring", "scope", if (mode == "carry") mapOf("carry" to "true") else emptyMap(),
                    children = s_[alias, merge,
                        LcncNode("edgeYield", "scope.out", mapOf("name" to "edge", "kind" to "json"))]))
                wires.add(LcncWire("merge", "value", "edgeYield", "value"))
                // The each envelope is json; its named edge port would instead be List<json>.
                wires.add(LcncWire("ring", "returns", "pick", "x"))
            }
            val path = when (mode) { "each" -> "0.edge"; "carry" -> "edge"; else -> "" }
            nodes.add(LcncNode("pick", "pick", mapOf("path" to path)))
            nodes.add(LcncNode("typed", "scope.in", mapOf("name" to "edge", "kind" to "trigger")))
            nodes.add(LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")))
            nodes.add(LcncNode("button", "button"))
            nodes.add(LcncNode("consumer", "scope", children = s_[
                // pick declares json; this formal infers that exact kind from its per-name wire.
                LcncNode("edge", "scope.in", mapOf("name" to "edge")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")]))
            wires.addAll(listOf(LcncWire("pick", "y", "consumer", "edge"),
                LcncWire("button", "press", "consumer", "press"),
                LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press")))
            val program = LcncProgram("binding-origin-$mode", nodes.toSeries(), wires.toSeries())
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty(), mode)
            var effects = 0
            var ringEntries = 0
            val runner = LcncRunner(PureNodes.registry { 1L } +
                ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() })).apply {
                onScopeEnter = { entered, _ -> if (entered.lastOrNull() == "ring") ringEntries++ }
            }
            val args = mapOf("edge" to 1L, "ordinary" to 1L)
            var completed = runner.runProcedure(program, args)
            assertEquals(mapOf("y" to 1L), completed.nodeOutputs["pick"], mode)
            assertEquals(0, effects, mode)
            val expectedEntries = if (mode == "root") 0 else 2
            assertEquals(expectedEntries, ringEntries, "$mode must execute its intended iterations before the late typed read")
            assertEquals(mapOf("ordinary" to 1L), completed.retained.bindings, mode)
            repeat(2) { turn ->
                runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), completed.retained)
                completed = runner.runProcedure(program, args)
                assertEquals(0, effects, "$mode cannot revive a late-expired binding through merge/pick")
                assertEquals(emptyMap(), completed.nodeOutputs["pick"], mode)
                assertEquals(mapOf("value" to 1L), completed.nodeOutputs["ordinary"],
                    "The equal-valued ordinary binding has a different owner/name origin: $mode")
                assertEquals(expectedEntries, ringEntries, "Retaining $mode cannot rerun a ring")
                assertEquals(mapOf("ordinary" to 1L), completed.retained.bindings, mode)
            }
        }
    }

    @Test fun aLongLinearAliasChainExpiresWithoutRecursiveProjectionOrEffects() = runTest {
        val count = 4096
        val nodes = mutableListOf(LcncNode("alias", "scope.in", mapOf("name" to "edge")))
        val wires = mutableListOf<LcncWire>()
        var previous = "alias"
        repeat(count) { index ->
            val id = "merge-$index"
            nodes.add(LcncNode(id, "merge"))
            wires.add(LcncWire(previous, "value", id, "a"))
            previous = id
        }
        nodes.add(LcncNode("typed", "scope.in", mapOf("name" to "edge", "kind" to "trigger")))
        nodes.add(LcncNode("button", "button"))
        nodes.add(LcncNode("consumer", "scope", children = s_[
            LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
            LcncNode("effect", "test.effect")]))
        wires.addAll(listOf(LcncWire(previous, "value", "consumer", "edge"),
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press")))
        val program = LcncProgram("long-binding-alias", nodes.toSeries(), wires.toSeries())
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("value" to 1L), base.nodeOutputs[previous], "Every authored alias must actually execute")
        assertTrue(base.retained.bindings.isEmpty())
        assertEquals(emptyMap(), base.retained.outputs["alias"])
        assertTrue((0 until count).all { base.retained.outputs["merge-$it"] == emptyMap<String, Any?>() },
            "Late expiry must reach every executed alias without recursive projection")
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(emptyMap(), next.nodeOutputs[previous])
        assertEquals(0, effects)
    }

    @Test fun anEarlierRootArgumentAliasCannotReplayAnExpiredTrigger() = runTest {
        val program = LcncProgram("root-binding-alias", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("merge", "merge"),
            LcncNode("typed", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("button", "button"),
            LcncNode("consumer", "scope", children = s_[
                LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("alias", "value", "merge", "a"),
            LcncWire("merge", "value", "consumer", "edge"),
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("edge", "value", "effect", "edge"),
            LcncWire("press", "value", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("value" to 1L), base.nodeOutputs["alias"])
        assertEquals(mapOf("value" to 1L), base.nodeOutputs["merge"])
        assertEquals(0, effects)
        assertEquals(mapOf("ordinary" to 1L), base.retained.bindings)
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(0, effects, "An earlier alias of the same expired root binding must not supply a new edge")
        assertEquals(mapOf("ordinary" to 1L), next.retained.bindings)
    }

    @Test fun aNestedTriggerShadowDoesNotExpireTheSameNamedOrdinaryRootBinding() = runTest {
        val program = LcncProgram("root-binding-shadow", s_[
            LcncNode("ordinary", "scope.in", mapOf("name" to "edge", "kind" to "num")),
            LcncNode("timer", "timer"),
            LcncNode("shadow", "scope", children = s_[
                LcncNode("shadowEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("shadowYield", "scope.out", mapOf("name" to "edge", "kind" to "trigger"))]),
            LcncNode("button", "button"),
            LcncNode("yield", "scope.out", mapOf("name" to "ordinary", "kind" to "num"))], s_[
            LcncWire("timer", "tick", "shadow", "edge"),
            LcncWire("shadowEdge", "value", "shadowYield", "value"),
            LcncWire("ordinary", "value", "yield", "value")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L })
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("ordinary" to 1L), base.returns)
        assertEquals(1L, base.nodeOutputs["shadow"]?.get("edge"))
        assertEquals(args, base.retained.bindings,
            "The equal-valued trigger belongs to the nested parameter, not the root argument")
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(mapOf("ordinary" to 1L), next.returns)
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["ordinary"])
        assertFalse(next.nodeOutputs.getValue("shadow").containsKey("edge"))
        assertEquals(args, next.retained.bindings)
    }

    @Test fun anUnexecutedTypedReadDoesNotExpireAnOrdinaryRootBinding() = runTest {
        val program = LcncProgram("root-binding-skipped", s_[
            LcncNode("ordinary", "scope.in", mapOf("name" to "edge", "kind" to "num")),
            LcncNode("condition", "json.value", mapOf("value" to "false")),
            LcncNode("skipped", "scope", children = s_[
                LcncNode("unreadEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("unreadYield", "scope.out", mapOf("name" to "edge", "kind" to "trigger"))]),
            LcncNode("button", "button"),
            LcncNode("yield", "scope.out", mapOf("name" to "ordinary", "kind" to "num"))], s_[
            LcncWire("condition", "value", "skipped", "when"),
            LcncWire("unreadEdge", "value", "unreadYield", "value"),
            LcncWire("ordinary", "value", "yield", "value")])
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L })
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(mapOf("ordinary" to 1L), base.returns)
        assertFalse(base.nodeOutputs.containsKey("skipped"))
        assertEquals(args, base.retained.bindings,
            "A declaration in a skipped branch has not read the root argument as an event")
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(mapOf("ordinary" to 1L), next.returns)
        assertEquals(mapOf("value" to 1L), next.nodeOutputs["ordinary"])
        assertFalse(next.nodeOutputs.containsKey("skipped"))
        assertEquals(args, next.retained.bindings)
    }

    @Test fun mediaCommandsAreOneTransportAndObservationsDoNotReplayThem() = runTest {
        val program = LcncProgram("media-pulse", s_[
            LcncNode("url", "text.value", mapOf("value" to "/fixture.wav")),
            LcncNode("play", "button"), LcncNode("other", "button"),
            LcncNode("player", "media.player")], s_[
            LcncWire("url", "value", "player", "url"),
            LcncWire("play", "press", "player", "play?")])
        val runner = LcncRunner(PureNodes.registry { 1L })
        val base = runner.runProcedure(program)
        assertEquals(mapOf("url" to "/fixture.wav"), base.nodeOutputs["player"]?.get("rendered"))
        assertFalse(base.nodeOutputs.getValue("player").containsKey("state"), "a headless runner cannot invent DOM state")
        runner.pulse = LcncPulse("play", mapOf("press" to 2L), base.retained)
        val played = runner.runProcedure(program)
        assertEquals(mapOf("url" to "/fixture.wav", "play" to 2L), played.nodeOutputs["player"]?.get("rendered"))
        assertFalse(played.retained.outputs.getValue("player").containsKey("rendered"), "render commands must not replay")
        runner.pulse = LcncPulse("other", mapOf("press" to 3L), played.retained)
        assertEquals(emptyMap(), runner.runProcedure(program).nodeOutputs["player"])
        val observation = mapOf("url" to "/fixture.wav", "playing" to false, "t" to 4.0, "duration" to 4.0)
        runner.pulse = LcncPulse("player", mapOf("state" to observation, "ended" to 4L), played.retained)
        val ended = runner.runProcedure(program)
        assertEquals(mapOf("state" to observation, "ended" to 4L), ended.nodeOutputs["player"])
        assertEquals(mapOf("state" to observation), ended.retained.outputs["player"])
    }

    @Test fun eventFeedsEmitOnlyTheSuppliedCurrentObservation() = runTest {
        for (type in listOf("graal.events", "vm.events")) {
            val program = LcncProgram("event-feed", s_[LcncNode("feed", type), LcncNode("other", "button")], emptySeriesOf())
            val runner = LcncRunner(PureNodes.registry { 1L })
            val base = runner.runProcedure(program)
            assertEquals(emptyMap(), base.nodeOutputs["feed"])
            val event = mapOf("kind" to "synthetic-observation")
            runner.pulse = LcncPulse("feed", mapOf("event" to event), base.retained)
            val current = runner.runProcedure(program)
            assertEquals(mapOf("event" to event), current.nodeOutputs["feed"])
            runner.pulse = LcncPulse("other", mapOf("press" to 2L), current.retained)
            assertEquals(emptyMap(), runner.runProcedure(program).nodeOutputs["feed"])
        }
    }

    @Test fun coalesceRetainsTheSelectedProjectionWithoutChoosingItsFallbackAgain() = runTest {
        val program = LcncProgram("coalesce-origin", s_[
            LcncNode("ring", "scope", children = s_[LcncNode("timer", "timer"),
                LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger"))]),
            LcncNode("fallback", "json.value", mapOf("value" to "{\"ordinary\":\"fallback\"}")),
            LcncNode("select", "coalesce"), LcncNode("button", "button")], s_[
            LcncWire("timer", "tick", "edge", "value"),
            LcncWire("ring", "returns", "select", "a"), LcncWire("fallback", "value", "select", "b")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        val runner = LcncRunner(PureNodes.registry { 1L } + CouncilNodes.registry(CouncilDialog {
            error("retention cannot invoke a model")
        }))
        val base = runner.runProcedure(program)
        assertEquals(mapOf("value" to mapOf("edge" to 1L)), base.nodeOutputs["select"])
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        assertEquals(mapOf("value" to emptyMap<String, Any?>()), runner.runProcedure(program).nodeOutputs["select"],
            "expiring the selected event must not reselect the ordinary fallback")
    }

    @Test fun carryRetainsOnlyTheFinalIterationsData() = runTest {
        val program = LcncProgram("carry-origin", s_[
            LcncNode("ring", "scope", mapOf("carry" to "true"), children = s_[
                LcncNode("timer", "timer"), LcncNode("text", "text.value", mapOf("value" to "retained")),
                LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
            LcncNode("button", "button")], s_[LcncWire("timer", "tick", "edge", "value"),
            LcncWire("text", "value", "data", "value")])
        val runner = LcncRunner(PureNodes.registry { 1L })
        val base = runner.runProcedure(program)
        assertEquals(2, base.nodeOutputs["ring"]?.get("count"), "the unchanged second iteration reaches the fixed point")
        assertEquals(1L, base.nodeOutputs["ring"]?.get("edge"))
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        assertEquals(mapOf("data" to "retained", "returns" to mapOf("data" to "retained"), "count" to 2),
            runner.runProcedure(program).nodeOutputs["ring"])
    }

    @Test fun anActiveRingCannotRebindAnExpiredRootArgument() = runTest {
        val program = LcncProgram("ambient-trigger", s_[
            LcncNode("rootEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("button", "button"), LcncNode("consumer", "scope", children = s_[
                LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertEquals(0, effects)
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(emptyMap(), next.nodeOutputs["rootEdge"])
        assertEquals(0, effects, "expiring the root output is insufficient if the ring can rebind the original argument")
    }

    @Test fun structuredEventsExpireThroughMergeWithoutLosingData() = runTest {
        val program = LcncProgram("merge-envelope", s_[
            LcncNode("ring", "scope", children = s_[
                LcncNode("timer", "timer"),
                LcncNode("text", "text.value", mapOf("value" to "retained")),
                LcncNode("edge", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
            LcncNode("merge", "merge"), LcncNode("button", "button"),
            LcncNode("consumer", "scope", children = s_[
                LcncNode("inputEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("inputPress", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                LcncNode("effect", "test.effect")])], s_[
            LcncWire("timer", "tick", "edge", "value"), LcncWire("text", "value", "data", "value"),
            LcncWire("ring", "returns", "merge", "a"), LcncWire("merge", "value", "consumer", "args"),
            LcncWire("button", "press", "consumer", "press"),
            LcncWire("inputEdge", "value", "effect", "edge"), LcncWire("inputPress", "value", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        var base = runner.runProcedure(program)
        assertEquals(0, effects)
        repeat(2) { turn ->
            runner.pulse = LcncPulse("button", mapOf("press" to turn + 2L), base.retained)
            base = runner.runProcedure(program)
            assertEquals(0, effects, "a retained JSON envelope cannot replay its nested event through merge")
            assertEquals(mapOf("value" to mapOf("data" to "retained")), base.nodeOutputs["merge"])
        }
    }

    @Test fun identicalRootValuesRetainTheirExecutedYieldOrigin() = runTest {
        val program = LcncProgram("yield-origin", s_[
            LcncNode("ring", "scope", children = s_[
                LcncNode("choice", "test.choice"),
                LcncNode("events", "scope", children = s_[LcncNode("timer", "timer"),
                    LcncNode("eventYield", "scope.out", mapOf("name" to "edge", "kind" to "trigger"))]),
                LcncNode("data", "scope", mapOf("else" to "true"), children = s_[
                    LcncNode("number", "test.number"),
                    LcncNode("dataYield", "scope.out", mapOf("name" to "edge", "kind" to "int"))]),
                LcncNode("payload", "scope.out", mapOf("name" to "payload", "kind" to "json"))]),
            LcncNode("button", "button")], s_[
            LcncWire("choice", "value", "events", "when"), LcncWire("choice", "value", "data", "when"),
            LcncWire("timer", "tick", "eventYield", "value"), LcncWire("number", "value", "dataYield", "value"),
            LcncWire("events", "returns", "payload", "value"), LcncWire("data", "returns", "payload", "value")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        val before = mutableListOf<Map<String, Map<String, Any?>>>()
        val after = mutableListOf<Map<String, Map<String, Any?>>>()
        for (eventBranch in listOf(true, false)) {
            var choices = 0
            val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
                "test.choice" to LcncNodeRunner { _, _ -> choices++; mapOf("value" to eventBranch) },
                "test.number" to LcncNodeRunner { _, _ -> mapOf("value" to 1L) }))
            val base = runner.runProcedure(program)
            before.add(base.nodeOutputs)
            runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
            after.add(runner.runProcedure(program).nodeOutputs)
            assertEquals(1, choices, "retention must not rerun the branch selector")
        }
        assertEquals(before[0], before[1], "raw root values alone cannot identify which branch supplied the yield")
        assertEquals(before[1]["ring"], after[1]["ring"], "ordinary data named edge survives unchanged")
        val empty = emptyMap<String, Any?>()
        assertEquals(mapOf("payload" to empty, "returns" to mapOf("payload" to empty)), after[0]["ring"],
            "the event branch expires even though its later-authored data feeder did not run")
    }

    @Test fun namedBodyEventsExpireWithoutLoadingTheBodyAgain() = runTest {
        val body = LcncProgram("named", s_[LcncNode("timer", "timer"),
            LcncNode("text", "text.value", mapOf("value" to "retained")),
            LcncNode("event", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))], s_[
            LcncWire("timer", "tick", "event", "value"), LcncWire("text", "value", "data", "value")])
        val program = LcncProgram("named-root", s_[LcncNode("ring", "scope", subprogram = "named"),
            LcncNode("button", "button")], emptySeriesOf())
        var loads = 0
        val runner = LcncRunner(PureNodes.registry { 1L }).apply {
            subprogramLoader = { loads++; body }
        }
        val base = runner.runProcedure(program)
        runner.subprogramLoader = { error("inactive named bodies must not be loaded or executed during a pulse") }
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program)
        assertEquals(1, loads)
        val data = mapOf("data" to "retained")
        assertEquals(data + ("returns" to data), next.nodeOutputs["ring"])
    }

    @Test fun scopeEventsExpireInPortsAndArgumentEnvelopes() = runTest {
        for (declared in listOf(false, true)) for (nested in listOf(false, true)) {
            val kind = if (declared) mapOf("kind" to "trigger") else emptyMap()
            val body = s_[LcncNode("timer", "timer"), LcncNode("text", "text.value", mapOf("value" to "retained")),
                LcncNode("event", "scope.out", mapOf("name" to "edge") + kind),
                LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]
            val scope = if (nested) LcncNode("ring", "scope", children = s_[
                LcncNode("inner", "scope", children = body),
                LcncNode("payload", "scope.out", mapOf("name" to "payload", "kind" to "json"))])
                else LcncNode("ring", "scope", children = body)
            val wires = mutableListOf(LcncWire("timer", "tick", "event", "value"),
                LcncWire("text", "value", "data", "value"),
                LcncWire("ring", if (nested) "payload" else "returns", "consumer", "args"),
                LcncWire("button", "press", "consumer", "press"),
                LcncWire("edge", "value", "effect", "edge"), LcncWire("press", "value", "effect", "press"))
            if (nested) wires.add(LcncWire("inner", "returns", "payload", "value"))
            val program = LcncProgram("scope-events", s_[scope, LcncNode("button", "button"),
                LcncNode("consumer", "scope", children = s_[
                    LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
                    LcncNode("press", "scope.in", mapOf("name" to "press", "kind" to "trigger")),
                    LcncNode("effect", "test.effect")])], wires.toSeries())
            assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
            var effects = 0
            val runner = LcncRunner(PureNodes.registry { 1L } +
                ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
            val base = runner.runProcedure(program)
            assertEquals(0, effects)
            runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
            val next = runner.runProcedure(program)
            assertEquals(0, effects, "old scope events cannot satisfy a new pulse: declared=$declared nested=$nested")
            val outputs = next.nodeOutputs.getValue("ring")
            val retained = mapOf("data" to "retained")
            assertEquals(if (nested) mapOf("payload" to retained, "returns" to mapOf("payload" to retained))
                else retained + ("returns" to retained), outputs, "ordinary data survives event erasure")
        }
    }

    @Test fun typeVariablesCannotRetainAnEventFromAnotherPulse() = runTest {
        val program = LcncProgram("generic-event", s_[LcncNode("timer", "timer"), LcncNode("merge", "merge"),
            LcncNode("button", "button"), LcncNode("effect", "test.effect")], s_[
            LcncWire("timer", "tick", "merge", "a"), LcncWire("merge", "value", "effect", "edge"),
            LcncWire("button", "press", "effect", "press")])
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val base = runner.runProcedure(program)
        assertEquals(1L, base.nodeOutputs.getValue("merge")["value"])
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program)
        assertEquals(0, effects)
        assertEquals(emptyMap(), next.nodeOutputs["merge"])
    }

    @Test fun aRetainedRootBindingDoesNotReissueItsTrigger() = runTest {
        val program = LcncProgram("binding-event", s_[
            LcncNode("edge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("button", "button"), LcncNode("effect", "test.effect")], s_[
            LcncWire("edge", "value", "effect", "edge"), LcncWire("button", "press", "effect", "press")])
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program, args)
        assertEquals(0, effects)
        assertEquals(emptyMap(), next.nodeOutputs["edge"])
    }

    @Test fun eachRetainsItsDataListsWithoutRetainingEventLists() = runTest {
        val program = LcncProgram("each-events", s_[
            LcncNode("items", "json.value", mapOf("value" to "[1,2]")),
            LcncNode("ring", "scope", children = s_[LcncNode("timer", "timer"),
                LcncNode("text", "text.value", mapOf("value" to "retained")),
                LcncNode("event", "scope.out", mapOf("name" to "edge", "kind" to "trigger")),
                LcncNode("data", "scope.out", mapOf("name" to "data", "kind" to "text"))]),
            LcncNode("button", "button"), LcncNode("effect", "test.effect")], s_[
            LcncWire("items", "value", "ring", "each"), LcncWire("timer", "tick", "event", "value"),
            LcncWire("text", "value", "data", "value"), LcncWire("ring", "edge", "effect", "edges"),
            LcncWire("button", "press", "effect", "press")])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() }))
        val base = runner.runProcedure(program)
        assertEquals(listOf(1L, 1L), base.nodeOutputs.getValue("ring")["edge"])
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program)
        assertEquals(0, effects)
        assertEquals(mapOf("data" to listOf("retained", "retained"),
            "returns" to listOf(mapOf("data" to "retained"), mapOf("data" to "retained")),
            "count" to 2), next.nodeOutputs["ring"])
    }

    @Test fun aYieldNamedReturnsIsNotConfusedWithItsScopeEnvelope() = runTest {
        val program = LcncProgram("returns-name", s_[LcncNode("ring", "scope", children = s_[
            LcncNode("timer", "timer"),
            LcncNode("event", "scope.out", mapOf("name" to "returns", "kind" to "trigger"))]),
            LcncNode("button", "button")], s_[LcncWire("timer", "tick", "event", "value")])
        val runner = LcncRunner(PureNodes.registry { 1L })
        val base = runner.runProcedure(program)
        assertEquals(mapOf("returns" to mapOf("returns" to 1L)), base.nodeOutputs["ring"])
        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val next = runner.runProcedure(program)
        assertEquals(mapOf("returns" to emptyMap<String, Any?>()), next.nodeOutputs["ring"])
    }

    @Test fun selectedBranchesUseTheRetainedFrameWithoutReplayingRoots() = runTest {
        var effects = 0
        val registry = PureNodes.registry { 1L } + mapOf(
            "test.effect" to LcncNodeRunner { _, _ -> effects++; mapOf("value" to "retained") },
            "test.combine" to LcncNodeRunner { _, inputs -> mapOf("value" to inputs.getValue("text")) },
        )
        val program = LcncProgram("pulse", s_[LcncNode("text", "test.effect"), LcncNode("button", "button"),
            LcncNode("use", "test.combine"), LcncNode("out", "scope.out", mapOf("name" to "value"))],
            s_[LcncWire("text", "value", "use", "text"), LcncWire("button", "press", "use", "press"),
                LcncWire("use", "value", "out", "value")])
        val runner = LcncRunner(registry)
        val base = runner.runProcedure(program)
        assertEquals(1, effects)
        runner.pulse = LcncPulse("button", mapOf("press" to 1L), base.retained)
        val result = runner.runProcedure(program)
        assertEquals(1, effects)
        assertEquals("retained", result.returns["value"])
    }

    @Test fun retainedFutureValuesCannotMaskUseBeforeDefinition() = runTest {
        val program = LcncProgram("order", s_[LcncNode("use", "test.consume"), LcncNode("button", "button")],
            s_[LcncWire("button", "press", "use", "press")])
        var calls = 0
        val runner = LcncRunner(PureNodes.registry { 1L } +
            ("test.consume" to LcncNodeRunner { _, _ -> calls++; emptyMap() })).apply {
            pulse = LcncPulse("button", mapOf("press" to 2L), LcncRetainedFrame(mapOf("button" to emptyMap())))
        }
        assertFailsWith<LcncUseBeforeDef> { runner.runProcedure(program) }
        assertEquals(0, calls)
    }

    @Test fun aDifferentControlCannotReuseAnOldTriggerEdge() = runTest {
        val program = LcncProgram("edges", s_[LcncNode("a", "button"), LcncNode("b", "button"),
            LcncNode("join", "test.join")], s_[LcncWire("a", "press", "join", "a"), LcncWire("b", "press", "join", "b")])
        var calls = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
            "button" to LcncNodeRunner { _, _ -> mapOf("press" to 2L) },
            "test.join" to LcncNodeRunner { _, _ -> calls++; mapOf("value" to "old") }))
        val base = runner.runProcedure(program)
        assertEquals(mapOf("value" to "old"), base.nodeOutputs["join"])
        assertEquals(1, calls)
        runner.pulse = LcncPulse("b", mapOf("press" to 3L), base.retained)
        val result = runner.runProcedure(program)
        assertEquals(1, calls, "the previously successful join cannot reuse a's expired edge")
        assertFalse(result.nodeOutputs.containsKey("join"), "selected-but-unfed is not the old successful value")
        assertEquals(emptyMap(), result.nodeOutputs["a"])
    }

    @Test fun anInwardWireSelectsItsWholeRingWithoutLeakingLocals() = runTest {
        val program = LcncProgram("rings", s_[LcncNode("button", "button"), LcncNode("ring", "scope", children = s_[
            LcncNode("inner", "test.consume"), LcncNode("yield", "scope.out", mapOf("name" to "result")),
        ])], s_[LcncWire("button", "press", "inner", "press"), LcncWire("inner", "value", "yield", "value")])
        val runner = LcncRunner(PureNodes.registry { 1L } + ("test.consume" to LcncNodeRunner { _, inputs ->
            mapOf("value" to inputs.getValue("press"))
        })).apply { pulse = LcncPulse("button", mapOf("press" to 4L), LcncRetainedFrame()) }
        val result = runner.runProcedure(program)
        assertEquals(4L, result.nodeOutputs["ring"]?.get("result"))
        assertFalse(result.nodeOutputs.containsKey("inner"))
    }

    @Test fun aControlCannotInjectUndeclaredOutputsOrTargetAnEffect() {
        val program = LcncProgram("ports", s_[LcncNode("button", "button"), LcncNode("effect", "test.effect")], emptySeriesOf())
        assertFailsWith<IllegalArgumentException> { LcncPulse("button", mapOf("value" to 1), LcncRetainedFrame()).roots(program) }
        assertFailsWith<IllegalArgumentException> { LcncPulse("effect", emptyMap(), LcncRetainedFrame()).roots(program) }
        assertFailsWith<IllegalArgumentException> { LcncPulse("button", mapOf("press" to Double.NaN), LcncRetainedFrame()).roots(program) }
    }

    @Test fun mediaStateDoesNotFireAnEndedBranch() = runTest {
        val program = LcncProgram("media", s_[LcncNode("url", "text"), LcncNode("player", "media.player"),
            LcncNode("ended", "scope", children = s_[LcncNode("effect", "test.effect")])],
            s_[LcncWire("url", "value", "player", "url"), LcncWire("player", "ended", "ended", "ended")])
        var calls = 0
        val retained = LcncRetainedFrame(mapOf("url" to mapOf("value" to "test:media")))
        val runner = LcncRunner(mapOf(
            "media.player" to LcncNodeRunner { _, _ -> mapOf("ended" to 99L) },
            "test.effect" to LcncNodeRunner { _, _ -> calls++; emptyMap() },
        )).apply {
            pulse = LcncPulse("player", mapOf("state" to mapOf("paused" to true)), retained)
        }
        val result = runner.runProcedure(program)
        assertEquals(0, calls, "a state observation is not an ended event")
        assertFalse(result.nodeOutputs.getValue("player").containsKey("ended"))
        runner.pulse = LcncPulse("player", mapOf("state" to mapOf("paused" to true), "ended" to 100L), retained)
        runner.runProcedure(program)
        assertEquals(1, calls)
        assertFailsWith<IllegalArgumentException> {
            LcncPulse("player", mapOf("state" to emptyMap<String, Any?>(), "ended" to 0), LcncRetainedFrame()).roots(program)
        }
    }
}
