package narchy.spacegraph.layout

// Upstream test/layout/force.test.ts at 125b369, 10 cases, expected values copied from upstream.
// Upstream's `place(items, previous)` is ForcePlacement.place; upstream `z` is Vec3.z.

import narchy.spacegraph.Vec3
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun makeItems(count: Int): List<PlacementItem<ForceMeta>> =
    List(count) { i -> PlacementItem(id = "node-$i", width = 100.0, depth = 0.0, meta = ForceMeta()) }

private fun distanceXY(positions: Map<String, Vec3>, a: String, b: String): Double =
    hypot(positions.getValue(a).x - positions.getValue(b).x, positions.getValue(a).y - positions.getValue(b).y)

private fun allFiniteXY(positions: Map<String, Vec3>): Boolean =
    positions.values.all { it.x.isFinite() && it.y.isFinite() }

class ForceTest {
    // 'places nodes deterministically'
    @Test
    fun placesNodesDeterministically() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 100))
        val items = makeItems(10)

        assertEquals(layout.place(items).entries.toList(), layout.place(items).entries.toList())
    }

    // 'respects pinned nodes'
    @Test
    fun respectsPinnedNodes() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 100))
        val previous = mapOf(
            "node-0" to Vec3(100.0, 100.0, 0.0),
            "node-1" to Vec3(200.0, 200.0, 0.0),
        )

        val positions = layout.place(makeItems(5), previous)

        assertEquals(Vec3(100.0, 100.0, 0.0), positions["node-0"])
        assertEquals(Vec3(200.0, 200.0, 0.0), positions["node-1"])
    }

    // 'pulls linked nodes closer than the same graph without links'
    @Test
    fun pullsLinkedNodesCloserThanTheSameGraphWithoutLinks() {
        fun items(links: List<String>?): List<PlacementItem<ForceMeta>> = listOf(
            PlacementItem(id = "a", width = 100.0, depth = 0.0, meta = ForceMeta(links = links)),
            PlacementItem(id = "b", width = 100.0, depth = 0.0, meta = ForceMeta()),
            PlacementItem(id = "c", width = 100.0, depth = 0.0, meta = ForceMeta()),
        )
        fun place(links: List<String>?): Map<String, Vec3> =
            ForcePlacement(ForceCoreConfig(seed = 42, iterations = 100)).place(items(links))

        assertTrue(distanceXY(place(listOf("b")), "a", "b") < distanceXY(place(null), "a", "b"))
    }

    // 'places large graphs through Barnes-Hut'
    @Test
    fun placesLargeGraphsThroughBarnesHut() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 50, useBarnesHut = true))

        val positions = layout.place(makeItems(120))

        assertEquals(120, positions.size)
        assertTrue(allFiniteXY(positions))
    }

    // 'places large graphs without Barnes-Hut'
    @Test
    fun placesLargeGraphsWithoutBarnesHut() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 50, useBarnesHut = false))

        val positions = layout.place(makeItems(20))

        assertEquals(20, positions.size)
        assertTrue(allFiniteXY(positions))
    }

    // 'accepts a custom Barnes-Hut theta'
    @Test
    fun acceptsACustomBarnesHutTheta() {
        val layout = ForcePlacement(
            ForceCoreConfig(
                seed = 42,
                iterations = 50,
                useBarnesHut = true,
                barnesHut = BarnesHutOptions(theta = 0.8),
            ),
        )

        assertEquals(10, layout.place(makeItems(10)).size)
    }

    // 'records depth in the Z coordinate'
    @Test
    fun recordsDepthInTheZCoordinate() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 50, depthStep = 64.0))
        val items = listOf(
            PlacementItem(id = "a", width = 100.0, depth = 0.0, meta = ForceMeta()),
            PlacementItem(id = "b", width = 100.0, depth = 1.0, meta = ForceMeta()),
            PlacementItem(id = "c", width = 100.0, depth = 2.0, meta = ForceMeta()),
        )

        val positions = layout.place(items)

        assertEquals(0.0, positions.getValue("a").z)
        assertEquals(64.0, positions.getValue("b").z)
        assertEquals(128.0, positions.getValue("c").z)
    }

    // 'handles a single node'
    @Test
    fun handlesASingleNode() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 50))

        assertEquals(1, layout.place(makeItems(1)).size)
    }

    // 'handles empty items'
    @Test
    fun handlesEmptyItems() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 50))

        assertEquals(0, layout.place(emptyList()).size)
    }

    // 'separates overlapping nodes'
    @Test
    fun separatesOverlappingNodes() {
        val layout = ForcePlacement(ForceCoreConfig(seed = 42, iterations = 100, spacing = 50.0))
        val items = listOf(
            PlacementItem(id = "a", width = 200.0, depth = 0.0, meta = ForceMeta(radius = 100.0)),
            PlacementItem(id = "b", width = 200.0, depth = 0.0, meta = ForceMeta(radius = 100.0)),
        )

        val positions = layout.place(items)

        assertTrue(distanceXY(positions, "a", "b") >= 190.0)
    }
}
