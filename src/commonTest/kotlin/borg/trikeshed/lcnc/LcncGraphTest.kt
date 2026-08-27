package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LcncGraphTest {

    private fun node(id: String, type: String = "noop") = LcncNode(id, type)
    private fun wire(fromNode: String, fromPort: String, toNode: String, toPort: String) =
        LcncWire(fromNode, fromPort, toNode, toPort)

    @Test
    fun topoOrdersUpstreamBeforeDownstream() {
        // a -> b -> c, plus an unconnected node d — order must respect the wires
        // and still include the untouched node exactly once.
        val program = LcncProgram(
            "p",
            listOf(node("c"), node("a"), node("d"), node("b")).toSeries(),
            listOf(wire("a", "y", "b", "x"), wire("b", "y", "c", "x")).toSeries(),
        )
        val order = program.topo().let { s -> (0 until s.size).map { s[it].id } }
        assertTrue(order.indexOf("a") < order.indexOf("b"), "a before b: $order")
        assertTrue(order.indexOf("b") < order.indexOf("c"), "b before c: $order")
        assertEquals(4, order.size)
        assertTrue("d" in order, "unwired node still appears")
    }

    @Test
    fun topoDetectsACycle() {
        val program = LcncProgram(
            "p",
            listOf(node("a"), node("b")).toSeries(),
            listOf(wire("a", "y", "b", "x"), wire("b", "y", "a", "x")).toSeries(),
        )
        assertFailsWith<LcncCycleException> { program.topo() }
    }

    @Test
    fun topoSkipsWiresToNodesThatNoLongerExist() {
        // A stale wire (its source node was deleted) must not crash execution order.
        val program = LcncProgram(
            "p",
            listOf(node("a")).toSeries(),
            listOf(wire("ghost", "y", "a", "x")).toSeries(),
        )
        val order = program.topo()
        assertEquals(1, order.size)
        assertEquals("a", order[0].id)
    }

    @Test
    fun inputsOfFindsOnlyWiresTargetingThatNode() {
        val program = LcncProgram(
            "p",
            listOf(node("a"), node("b"), node("c")).toSeries(),
            listOf(wire("a", "y", "b", "x"), wire("a", "y", "c", "x")).toSeries(),
        )
        assertEquals(1, program.inputsOf("b").size)
        assertEquals(1, program.inputsOf("c").size)
        assertEquals(0, program.inputsOf("a").size)
    }
}
