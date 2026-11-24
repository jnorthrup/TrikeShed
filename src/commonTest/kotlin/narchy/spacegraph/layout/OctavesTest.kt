package narchy.spacegraph.layout

// Upstream test/layout/octaves.test.ts at 125b369: all 4 cases ported, expected values copied from upstream.

import narchy.spacegraph.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OctavesTest {
    private val card = 400.0 to 180.0

    private fun dist(a: Vec3, b: Vec3) = hypot(a.x - b.x, a.y - b.y)

    // 'draws one hierarchy at halving world scales'
    @Test
    fun drawsOneHierarchyAtHalvingWorldScales() {
        val result = octaveLayout(
            listOf("root", "a", "b", "a1"),
            mapOf("a" to "root", "b" to "root", "a1" to "a"),
            OctaveLayoutOptions(card = card, radiusFactor = 2.0, rootRadiusFactor = 2.0),
        )
        assertEquals(OctaveScale(0, 1.0), result.octaves["root"])
        assertEquals(OctaveScale(-1, 0.5), result.octaves["a"])
        assertEquals(OctaveScale(-2, 0.25), result.octaves["a1"])
        val root = result.positions["root"]!!
        val a = result.positions["a"]!!
        val a1 = result.positions["a1"]!!
        assertTrue(abs(dist(a, root) - 800.0) < 1e-5)
        assertTrue(abs(dist(a1, a) - 400.0) < 1e-5)
    }

    // 'caps recursion and ignores parent cycles'
    @Test
    fun capsRecursionAndIgnoresParentCycles() {
        val result = octaveLayout(
            listOf("a", "b", "c"),
            mapOf("a" to "b", "b" to "a", "c" to "b"),
            OctaveLayoutOptions(card = card, maxDepth = 1),
        )
        assertEquals(3, result.positions.size)
        assertTrue(result.octaves["c"]!!.octave >= -1)
    }

    // 'keeps every node positioned and bounds the whole range'
    @Test
    fun keepsEveryNodePositionedAndBoundsTheWholeRange() {
        val result = octaveLayout(listOf("x", "y", "z"), emptyMap(), OctaveLayoutOptions(card = card))
        assertEquals(3, result.positions.size)
        assertTrue(result.bounds.second.x > result.bounds.first.x)
        assertTrue(result.bounds.second.y > result.bounds.first.y)
    }

    // 'places a spiral deterministically and differently from a ring'
    @Test
    fun placesASpiralDeterministicallyAndDifferentlyFromARing() {
        val ids = listOf("root", "a", "b", "c", "d")
        val parentOf = ids.drop(1).associateWith { "root" }
        val options = OctaveLayoutOptions(
            card = card, shape = OctaveShape.spiral, radiusFactor = 1.0, rootRadiusFactor = 1.0, phase = 0.2,
        )
        val spiral = octaveLayout(ids, parentOf, options)
        val ring = octaveLayout(ids, parentOf, options.copy(shape = OctaveShape.ring))
        assertEquals(spiral.positions, octaveLayout(ids, parentOf, options).positions)
        val distances = ids.drop(1).map { id -> hypot(spiral.positions[id]!!.x, spiral.positions[id]!!.y) }
        assertTrue(distances.map { Math.round(it) }.toSet().size > 1)
        assertNotEquals(ring.positions["a"], spiral.positions["a"])
    }
}
