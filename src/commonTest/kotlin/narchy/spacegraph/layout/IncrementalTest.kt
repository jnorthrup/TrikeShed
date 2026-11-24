package narchy.spacegraph.layout

import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Expected values are upstream test/layout/incremental.test.ts ('incremental layout'), copied.
// NOT ported: 'the spatial index follows interpolated positions mid-flight' and 'a budget keeps a protected node'
// (both import SpatialIndex2D, applyNodeBudget and Kernel from packages/kernel, which the port does not have).
private fun p(x: Double, y: Double, z: Double = 0.0) = Vec3(x, y, z)

class IncrementalTest {
    @Test fun aFirstLayoutReportsEveryPositionAsAdded() {
        val next = linkedMapOf("a" to p(0.0, 0.0), "b" to p(10.0, 0.0))
        val incremental = computeIncremental(next)

        assertEquals(listOf("a", "b"), incremental.added.keys.toList())
        assertEquals(emptyList(), incremental.removed)
        assertEquals(0, incremental.moved.size)
    }

    @Test fun stableIdsKeepTheirPositionsAndMovedIsTheSymmetricDifference() {
        val previous = linkedMapOf("a" to p(0.0, 0.0), "b" to p(10.0, 0.0), "gone" to p(20.0, 0.0))
        val next = linkedMapOf("a" to p(0.0, 0.0), "b" to p(30.0, 4.0), "new" to p(40.0, 0.0))
        val incremental = computeIncremental(next, previous)

        assertEquals(listOf("new"), incremental.added.keys.toList())
        assertEquals(listOf("gone"), incremental.removed)
        assertEquals(listOf("b"), incremental.moved.keys.toList())
        assertEquals(LayoutMove("b", p(10.0, 0.0), p(30.0, 4.0)), incremental.moved["b"])
        assertEquals(p(0.0, 0.0), incremental.positions["a"])
    }

    @Test fun theClockIsAnArgumentEndpointsAreExactAndTheModelStaysTimeIndependent() {
        val previous = mapOf("a" to p(0.0, 0.0))
        val next = mapOf("a" to p(100.0, 40.0))
        val incremental = computeIncremental(next, previous)

        assertEquals(p(0.0, 0.0), interpolatePosition(p(0.0, 0.0), p(100.0, 40.0), 0.0))
        assertEquals(p(100.0, 40.0), interpolatePosition(p(0.0, 0.0), p(100.0, 40.0), 1.0))

        assertEquals(p(0.0, 0.0), interpolatedPositions(incremental, 0.0)["a"])
        assertEquals(p(50.0, 20.0), interpolatedPositions(incremental, 0.5)["a"])
        assertEquals(p(100.0, 40.0), interpolatedPositions(incremental, 1.0)["a"])
        assertEquals(p(100.0, 40.0), incremental.positions["a"])
    }

    @Test fun oneMovedSetInterpolatesAnyNumberOfViewsWithTheSameClock() {
        val incremental = diffPositions(mapOf("a" to p(40.0, 0.0)), mapOf("a" to p(0.0, 0.0)))

        assertEquals(interpolatedPositions(incremental, 0.25), interpolatedPositions(incremental, 0.25))
        assertEquals(p(10.0, 0.0), interpolatedPositions(incremental, 0.25)["a"])
    }

    @Test fun neverMovesAnIdThatBothLayoutsPlaceIdentically() {
        for (seed in 0 until 20) {
            val previous = (0 until 6).associate { i -> "n$i" to p((seed * 3 + i * 7).toDouble(), (i * 5).toDouble()) }
            val next = previous.toMutableMap()
            next["n0"] = p(seed.toDouble(), seed.toDouble())
            next["n1"] = previous.getValue("n1").let { p(it.x, it.y) }
            val incremental = computeIncremental(next, previous)

            for ((id, move) in incremental.moved) {
                assertEquals(false, move.from == move.to)
                assertEquals(move.to, next[id])
            }
        }
    }
}
