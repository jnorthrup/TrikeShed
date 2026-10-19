package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncProvenanceAdmissionTest {
    @Test
    fun overridingAPureTypeNameDoesNotAuthorizeAnOpaqueRunnerBeforeAmbientExpiry() = runTest {
        val program = LcncProgram("opaque-pick-admission", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("merge", "merge"),
            LcncNode("button", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("projection", "pick"),
                LcncNode("lateEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            ]),
        ], s_[
            LcncWire("alias", "value", "merge", "a"),
            LcncWire("button", "press", "gate", "activation"),
            LcncWire("merge", "value", "projection", "x"),
        ])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        var sources = 0
        var gateEntries = 0
        var effects = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
            "button" to LcncPureRunner { _, _ -> sources++; emptyMap() },
            "pick" to LcncNodeRunner { _, inputs -> effects++; mapOf("y" to inputs["x"]) },
        )).apply {
            onScopeEnter = { path, _ -> if (path.lastOrNull() == "gate") gateEntries++ }
        }
        val args = mapOf("edge" to 1L)
        val base = runner.runProcedure(program, args)
        assertNull(base.retained.unavailable)
        assertNotNull(base.retained.origins, "The base must come from a completed walk with real origin proof")
        assertEquals(mapOf("value" to 1L), base.retained.outputs["merge"])
        assertEquals(args, base.retained.bindings, "The ambient typed read was skipped initially")
        assertEquals(1, sources)
        assertEquals(0, gateEntries)
        assertEquals(0, effects)

        runner.pulse = LcncPulse("button", mapOf("press" to 2L), base.retained)
        val failure = assertFailsWith<IllegalArgumentException> { runner.runProcedure(program, args) }
        assertEquals(LcncPulse.BINDING_ORIGIN_UNAVAILABLE, failure.message)
        assertEquals(1, sources, "Refusal must precede even the selected control source")
        assertEquals(0, gateEntries)
        assertEquals(0, effects, "An opaque implementation cannot borrow purity from its registered type name")
    }

    @Test
    fun admittedNamedDefinitionsRemainTheActualBodiesWhilePublicPulseChanges() = runTest {
        val original = LcncProgram("pinned-reader", s_[
            LcncNode("typedEdge", "scope.in", mapOf("name" to "edge", "kind" to "trigger")),
            LcncNode("label", "text.value", mapOf("value" to "pinned")),
            LcncNode("marker", "scope.out", mapOf("name" to "marker", "kind" to "text")),
        ], s_[LcncWire("label", "value", "marker", "value")])
        val replacement = LcncProgram("pinned-reader", s_[
            LcncNode("effect", "test.effect"),
            LcncNode("label", "text.value", mapOf("value" to "replacement")),
            LcncNode("marker", "scope.out", mapOf("name" to "marker", "kind" to "text")),
        ], s_[LcncWire("label", "value", "marker", "value")])
        val program = LcncProgram("definition-snapshot", s_[
            LcncNode("alias", "scope.in", mapOf("name" to "edge")),
            LcncNode("merge", "merge"),
            LcncNode("ordinary", "scope.in", mapOf("name" to "ordinary", "kind" to "num")),
            LcncNode("button", "button"),
            LcncNode("gate", "scope", children = s_[
                LcncNode("activation", "scope.in", mapOf("name" to "activation", "kind" to "trigger")),
                LcncNode("pause", "test.pause"),
                LcncNode("reader", "scope", subprogram = original.name),
                LcncNode("receipt", "scope.out", mapOf("name" to "receipt", "kind" to "json")),
            ]),
        ], s_[
            LcncWire("alias", "value", "merge", "a"),
            LcncWire("button", "press", "gate", "activation"),
            LcncWire("reader", "returns", "receipt", "value"),
        ])
        assertTrue(LcncTypeCheck.check(program, strict = false).isEmpty())
        assertTrue(LcncTypeCheck.check(original, strict = false).isEmpty())
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        var loads = 0
        var entries = 0
        val runner = LcncRunner(PureNodes.registry { 1L } + mapOf(
            "test.pause" to LcncPureRunner { _, _ -> paused.complete(Unit); release.await(); emptyMap() },
            "test.effect" to LcncNodeRunner { _, _ -> effects++; emptyMap() },
        )).apply {
            subprogramLoader = { loads++; error("The admitted definition must bypass the live loader") }
            onScopeEnter = { path, _ -> if (path.lastOrNull() == original.name) entries++ }
        }
        val args = mapOf("edge" to 1L, "ordinary" to 1L)
        val base = runner.runProcedure(program, args)
        assertNull(base.retained.unavailable)
        assertNotNull(base.retained.origins)
        assertEquals(args, base.retained.bindings)
        assertEquals(mapOf("value" to 1L), base.retained.outputs["merge"])
        assertEquals(0, entries)

        val definitions = mutableMapOf(original.name to original)
        val admitted = LcncPulse("button", mapOf("press" to 2L), base.retained, definitions = definitions)
        runner.pulse = admitted
        val completed = async { runner.runProcedure(program, args) }
        try {
            paused.await()
            definitions[original.name] = replacement
            runner.pulse = admitted.copy(definitions = mapOf(original.name to replacement))
        } finally { release.complete(Unit) }
        val next = completed.await()
        assertEquals(0, effects, "A map replacement cannot install an unadmitted effectful body")
        assertEquals(0, loads)
        assertEquals(1, entries, "The original pinned reader must actually execute once")
        assertEquals(mapOf("marker" to "pinned"), next.nodeOutputs.getValue("gate")["receipt"])
        assertNull(next.retained.unavailable)
        assertNotNull(next.retained.origins)
        assertEquals(emptyMap(), next.retained.outputs["alias"])
        assertEquals(emptyMap(), next.retained.outputs["merge"], "The original reader must expire the cached root alias")
        assertEquals(mapOf("ordinary" to 1L), next.retained.bindings)
    }
}
