package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LcncTreeShakeTest {

    @Test
    fun selectedParentBoundsNewWiresButRetainsFullProgramContext() {
        val inbound = LcncWire("external", "heap", "nested", "args?")
        val program = LcncProgram("selection", listOf(
            LcncNode("external", "graal.heap"),
            LcncNode("outside-source", "prompt.chat", x = 100.0),
            LcncNode("outside-sink", "display", x = 200.0),
            LcncNode("selected", "scope", children = listOf(
                LcncNode("source", "prompt.chat", x = 100.0),
                LcncNode("sink", "display", x = 200.0),
                LcncNode("nested", "scope", x = 800.0, children = listOf(
                    LcncNode("binding", "scope.in", params = mapOf("name" to "text")),
                ).toSeries()),
            ).toSeries()),
        ).toSeries(), listOf(inbound).toSeries())
        val result = LcncTreeShake.shake(program, LcncTreeShakeOptions(parentId = "selected"))
        assertEquals("selected", result.toMap()["parentId"])
        assertEquals(listOf(LcncWire("source", "content", "sink", "x")), result.made)
        assertEquals(program.nodes, result.program.nodes)
        assertTrue(inbound in result.program.wires.toList())
        assertTrue(result.verdicts.any { it.nodeId == "nested" && it.port == "text" && it.status == "binding" })
        assertTrue(result.verdicts.any { it.nodeId == "binding" && it.label.startsWith("Enclosing frame") })
        assertFalse(result.verdicts.any { it.nodeId.startsWith("outside") })
    }

    @Test
    fun invalidParentCannotFallBackToWholeProgramShake() {
        val program = LcncProgram("selection", listOf(LcncNode("leaf", "display"), LcncNode("empty", "scope")).toSeries(),
            emptyList<LcncWire>().toSeries())
        for (parent in listOf("missing", "leaf")) assertFailsWith<IllegalArgumentException> {
            LcncTreeShake.shake(program, LcncTreeShakeOptions(parentId = parent))
        }
        assertTrue(LcncTreeShake.shake(program, LcncTreeShakeOptions(parentId = "empty")).verdicts.isEmpty())
        for (parent in listOf("", 42)) assertFailsWith<IllegalArgumentException> {
            LcncTreeShakeOptions.fromMap(mapOf("parentId" to parent))
        }
        assertEquals("empty", LcncTreeShakeOptions.fromMap(mapOf("parentId" to "empty")).parentId)
    }

    @Test
    fun argumentMapIsNotOverriddenByAGuessedNamedWire() {
        val program = LcncProgram("bound", listOf(
            LcncNode("map", "graal.heap"),
            LcncNode("other", "graal.heap"),
            LcncNode("ring", "scope", children = listOf(
                LcncNode("arg", "scope.in", params = mapOf("name" to "text")),
            ).toSeries()),
        ).toSeries(), listOf(LcncWire("map", "heap", "ring", "args?")).toSeries())
        for (optional in listOf(false, true)) {
            val result = LcncTreeShake.shake(program, LcncTreeShakeOptions(includeOptional = optional))
            assertFalse(result.made.any { it.toNode == "ring" && it.toPort == "text" })
            assertTrue(result.verdicts.any { it.nodeId == "ring" && it.port == "text" && it.status == "binding" })
            assertFalse("ring" in result.starvedNodeIds)
        }
    }

    @Test
    fun optionalInputsAreReportedWithoutBeingConnected() {
        val program = LcncProgram("optional", listOf(LcncNode("ring", "scope")).toSeries(),
            emptyList<LcncWire>().toSeries())
        val result = LcncTreeShake.shake(program)
        assertTrue(result.made.isEmpty())
        assertTrue(result.verdicts.any { it.port == "args?" && it.status == "optional" })
        assertTrue(result.starvedNodeIds.isEmpty())
    }

    @Test
    fun testTreeShakeMatesCompatiblePortsWithinReach() {
        // Source node: prompt.chat produces content (kind "text")
        // Target node: display consumes x (kind "Any")
        // Within 340 distance
        val program = LcncProgram(
            name = "test-shake",
            nodes = listOf(
                LcncNode("p1", "prompt.chat", x = 100.0, y = 100.0),
                LcncNode("d1", "display", x = 200.0, y = 100.0),
            ).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )

        val result = LcncTreeShake.shake(program)
        // d1.x is a sink input that was unsatisfied, p1.content is compatible
        assertEquals(1, result.made.size, "should mate p1.content -> d1.x")
        val wire = result.made[0]
        assertEquals("p1", wire.fromNode)
        assertEquals("content", wire.fromPort)
        assertEquals("d1", wire.toNode)
        assertEquals("x", wire.toPort)

        val verdict = result.verdicts.find { it.nodeId == "d1" }
        assertNotNull(verdict)
        assertEquals("ok", verdict.status)
    }

    @Test
    fun testTreeShakeRejectsIncompatibleKinds() {
        // Source node: graal.heap produces heap (kind "json")
        // Target node: prompt.chat prompt consumes kind "text"
        // Incompatible kinds: json cannot plug into text
        val program = LcncProgram(
            name = "test-incompatible",
            nodes = listOf(
                LcncNode("g1", "graal.heap", x = 100.0, y = 100.0),
                LcncNode("p1", "prompt.chat", x = 200.0, y = 100.0),
            ).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )

        val result = LcncTreeShake.shake(program, LcncTreeShakeOptions(includeOptional = true))
        assertTrue(result.made.isEmpty(), "should not mate incompatible kinds")
        val verdict = result.verdicts.find { it.nodeId == "p1" }
        assertNotNull(verdict)
        assertEquals("dead", verdict.status)
    }

    @Test
    fun testTreeShakeRejectsMatingToEffectNodes() {
        // Effect nodes (isEffect = true, such as project.kill) should not be auto-wired by tree-shake
        val program = LcncProgram(
            name = "test-effect",
            nodes = listOf(
                LcncNode("t1", "timer", x = 100.0, y = 100.0),
                LcncNode("pk", "project.kill", x = 200.0, y = 100.0),
            ).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )

        val result = LcncTreeShake.shake(program)
        assertTrue(result.made.isEmpty(), "tree-shake must not auto-mate into effect nodes")
    }

    @Test
    fun testTreeShakeRejectsNodesBeyondReachDistance() {
        // Distance between nodes is 600 px (beyond 340 reach)
        val program = LcncProgram(
            name = "test-distance",
            nodes = listOf(
                LcncNode("p1", "prompt.chat", x = 100.0, y = 100.0),
                LcncNode("d1", "display", x = 700.0, y = 100.0),
            ).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )

        val result = LcncTreeShake.shake(program, LcncTreeShakeOptions(reach = 340.0))
        assertTrue(result.made.isEmpty(), "nodes beyond reach distance must not be auto-mated")
    }

    @Test
    fun testTreeShakeEnforcesLateralOrInwardScopeContainment() {
        // Scoped node inside "ring-1" produces text, outer sink requires text
        val innerNode = LcncNode("inner-chat", "prompt.chat", x = 200.0, y = 100.0)
        val ringNode = LcncNode("ring-1", "scope", x = 200.0, y = 100.0, children = listOf(innerNode).toSeries())
        val outerSink = LcncNode("outer-confirm", "result.confirm", x = 100.0, y = 100.0)

        val program = LcncProgram(
            name = "test-scope",
            nodes = listOf(outerSink, ringNode).toSeries(),
            wires = emptyList<LcncWire>().toSeries(),
        )

        val result = LcncTreeShake.shake(program)
        // Data flow cannot escape from inner scope to outer without scope.out
        assertTrue(result.made.isEmpty(), "inner scope cannot auto-mate outward to outer scope")
        val v = result.verdicts.find { it.nodeId == "outer-confirm" && it.port == "content" }
        assertNotNull(v)
        assertEquals("scope", v.status)
    }
}
