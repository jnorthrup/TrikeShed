package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LcncTreeShakeTest {

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
        val v = result.verdicts.find { it.nodeId == "outer-confirm" }
        assertNotNull(v)
        assertEquals("scope", v.status)
    }
}
