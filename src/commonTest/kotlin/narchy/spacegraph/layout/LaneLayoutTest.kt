package narchy.spacegraph.layout

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

// Expected values are upstream test/lane-layout.test.ts, copied by hand. Case names follow the upstream `it` titles.

private val LANES = listOf("prompt", "narrative", "explore", "edit", "verify", "delegate", "artifacts")

private fun make() = LaneLayout(LaneLayoutConfig(lanes = LANES, lanePitch = 56.0, step = 48.0, groupGap = 320.0, depthStep = 64.0))

private fun assertPositions(expected: Map<String, DoubleArray>, actual: Map<String, DoubleArray>) {
    assertEquals(expected.keys, actual.keys)
    for ((id, p) in expected) assertContentEquals(p, actual.getValue(id), id)
}

class LaneLayoutTest {
    @Test fun mapsLaneIndexToY() {
        val layout = make()
        assertEquals(2 * 56.0, layout.place(LaneItem("a", lane = "explore"))[1])
        assertEquals(6 * 56.0, layout.place(LaneItem("b", lane = "artifacts"))[1])
        assertEquals(0.0, layout.place(LaneItem("c", lane = "missing"))[1])
    }

    @Test fun advancesARowByTheWidthsOfTheItemsItHolds() {
        val layout = make()
        assertContentEquals(doubleArrayOf(50.0, 112.0, 0.0), layout.place(LaneItem("a", lane = "explore", width = 100.0)))
        assertContentEquals(doubleArrayOf(248.0, 112.0, 0.0), layout.place(LaneItem("b", lane = "explore", width = 200.0)))
    }

    @Test fun givesEveryRowItsOwnCursor() {
        val layout = make()
        layout.place(LaneItem("a", lane = "explore", width = 100.0))
        assertEquals(50.0, layout.place(LaneItem("b", lane = "edit", width = 100.0))[0])
    }

    @Test fun recordsTheDepthOnZ() {
        val layout = make()
        assertEquals(128.0, layout.place(LaneItem("a", lane = "explore", depth = 2.0))[2])
    }

    @Test fun indentsAnItemInsideItsColumn() {
        val layout = make()
        assertEquals(220.0, layout.place(LaneItem("a", lane = "explore", indent = 120.0, width = 200.0))[0])
    }

    @Test fun numbersTheRowsItselfWhenTheHostHasNoLaneNames() {
        val layout = LaneLayout(LaneLayoutConfig(lanePitch = 20.0, step = 8.0))
        assertContentEquals(doubleArrayOf(0.0, 40.0, 0.0), layout.place(LaneItem("a", row = 2)))
    }

    @Test fun placesTheSameIdOnce() {
        val layout = make()
        val first = layout.place(LaneItem("a", lane = "explore", width = 100.0))
        assertSame(first, layout.place(LaneItem("a", lane = "verify", width = 900.0)))
    }

    @Test fun opensAColumnWhereThePreviousOneEnded() {
        val layout = make()
        layout.open("t1")
        layout.place(LaneItem("a", lane = "explore", group = "t1", width = 100.0))
        layout.close("t1", 100.0)
        layout.open("t2")
        assertEquals(470.0, layout.place(LaneItem("b", lane = "explore", group = "t2", width = 100.0))[0])
    }

    @Test fun measuresAColumnFromTheWidestRowItHolds() {
        val layout = make()
        layout.open("t1")
        layout.place(LaneItem("a", lane = "explore", group = "t1", width = 100.0))
        layout.place(LaneItem("b", lane = "edit", group = "t1", width = 300.0))
        assertEquals(300.0, layout.width("t1"))
    }

    @Test fun computesBoundsOverASubset() {
        val layout = make()
        layout.placeAll(listOf(LaneItem("a", lane = "explore", width = 100.0), LaneItem("b", lane = "edit", width = 200.0)))
        val bounds = layout.bounds(listOf("a", "b"))!!
        assertContentEquals(doubleArrayOf(50.0, 112.0, 0.0), bounds.min)
        assertContentEquals(doubleArrayOf(100.0, 168.0, 0.0), bounds.max)
        assertContentEquals(doubleArrayOf(75.0, 140.0, 0.0), bounds.center)
        assertContentEquals(doubleArrayOf(50.0, 56.0, 0.0), bounds.size)
        assertNull(layout.bounds(emptyList()))
    }

    @Test fun isDeterministicAndAppendOnlyAcrossInstances() {
        val items = listOf(
            LaneItem("a", lane = "explore", width = 100.0),
            LaneItem("b", lane = "edit", width = 200.0),
            LaneItem("c", lane = "verify", width = 100.0),
        )
        val a = make()
        val b = make()
        a.placeAll(items)
        b.placeAll(items)
        assertPositions(b.positions, a.positions)
        a.place(LaneItem("d", lane = "artifacts", width = 100.0))
        assertContentEquals(doubleArrayOf(50.0, 112.0, 0.0), a.positions.getValue("a"))
        assertContentEquals(doubleArrayOf(100.0, 168.0, 0.0), a.positions.getValue("b"))
    }

    @Test fun clearsAllState() {
        val layout = make()
        layout.open("t1")
        layout.place(LaneItem("a", lane = "explore", group = "t1", width = 100.0))
        layout.close("t1", 100.0)
        layout.clear()
        assertEquals(0, layout.positions.size)
        layout.open("t2")
        assertEquals(50.0, layout.place(LaneItem("b", lane = "explore", group = "t2", width = 100.0))[0])
    }
}
