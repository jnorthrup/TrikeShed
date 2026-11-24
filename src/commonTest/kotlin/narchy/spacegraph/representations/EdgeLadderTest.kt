package narchy.spacegraph.representations

// Upstream test/edge-ladder.test.ts at 125b369, 6 cases, expected values copied from upstream.
// Deviation: upstream passes ScaleStop objects to edgeLadderFromStops; here ScaleStop does not implement
// RungStop, so each stop is mapped to a RungStop fixture first.

import narchy.spacegraph.zoom.ScaleDomain
import narchy.spacegraph.zoom.ScaleDomainConfig
import narchy.spacegraph.zoom.ScaleStop
import narchy.spacegraph.zoom.ZoomRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Edge(
    override val id: String,
    override val kind: String,
    override val importance: Double? = null,
) : LadderEdge

private data class EdgeStop(override val at: Double, override val id: String) : RungStop

private val STOPS = EDGE_RUNG_IDS.mapIndexed { index, id ->
    ScaleStop(
        at = index.toDouble() / (EDGE_RUNG_IDS.size - 1),
        id = id,
        importance = index.toDouble() / (EDGE_RUNG_IDS.size - 1),
        transitionDuration = 0.1,
    )
}

private val DOMAIN = ScaleDomain(
    ScaleDomainConfig(stops = STOPS, range = ZoomRange(min = 1.0, max = 100.0, closerIsLarger = false)),
)

private fun sample(normalized: Double) = DOMAIN.sample(normalized, DOMAIN.rawAt(normalized))

private fun ladder(hysteresis: Double = 0.0): EdgeLadder = EdgeLadder(
    STOPS.map { stop -> EdgeRung(id = stop.id, at = stop.at, style = DEFAULT_EDGE_STYLES[stop.id]) },
    hysteresis,
)

private val EDGE = Edge("e1", "causal")

class EdgeLadderTest {
    // 'selects bundle → hair → arrow → ribbon over the scale'
    @Test
    fun selectsBundleHairArrowRibbonOverTheScale() {
        val registry = ladder()
        assertEquals(EDGE_RUNG_IDS, registry.ids)
        assertEquals("bundle", registry.select(EDGE, sample(0.0))?.rung?.id)
        assertEquals("hair", registry.select(EDGE, sample(0.4))?.rung?.id)
        assertEquals("arrow", registry.select(EDGE, sample(0.7))?.rung?.id)
        assertEquals("ribbon", registry.select(EDGE, sample(1.0))?.rung?.id)
    }

    // 'carries host-owned styles through the selection'
    @Test
    fun carriesHostOwnedStylesThroughTheSelection() {
        val selection = ladder().select(EDGE, sample(1.0))
        assertEquals("e1", selection?.id)
        assertEquals("ribbon", selection?.rung?.id)
        assertEquals(4.0, selection?.style?.thickness)
        assertEquals("target", selection?.style?.arrowhead)
        assertNotNull(selection?.style?.labelLod)
    }

    // 'gates rungs by kind and importance'
    @Test
    fun gatesRungsByKindAndImportance() {
        val registry = EdgeLadder(
            listOf(
                EdgeRung(id = "hair", at = 0.0, kinds = listOf("causal"), style = EdgeRungStyle(thickness = 1.0)),
                EdgeRung(
                    id = "ribbon",
                    at = 0.5,
                    kinds = listOf("causal"),
                    minImportance = 0.9,
                    style = EdgeRungStyle(thickness = 4.0),
                ),
            ),
        )
        assertNull(registry.select(Edge("x", "other"), sample(1.0)))
        assertEquals("hair", registry.select(EDGE, sample(0.7))?.rung?.id)
        assertEquals("ribbon", registry.select(EDGE.copy(importance = 1.0), sample(0.7))?.rung?.id)
    }

    // 'holds a rung inside the dead zone'
    @Test
    fun holdsARungInsideTheDeadZone() {
        val registry = ladder(0.05)
        assertEquals("arrow", registry.select(EDGE, sample(0.64), "arrow")?.rung?.id)
        assertEquals("hair", registry.select(EDGE, sample(0.29), "hair")?.rung?.id)
        assertEquals("bundle", registry.select(EDGE, sample(0.35), "bundle")?.rung?.id)
    }

    // 'resolves a model by stable edge id'
    @Test
    fun resolvesAModelByStableEdgeId() {
        val resolved = ladder().resolve(listOf(EDGE, Edge("e2", "evidence")), sample(0.1))
        assertEquals(listOf("e1", "e2"), resolved.keys.toList())
        assertEquals(EdgeRungStyle(thickness = 1.0, dashed = true), resolved["e1"]?.style)
    }

    // 'aligns to scale stops and drops rungs without a style'
    @Test
    fun alignsToScaleStopsAndDropsRungsWithoutAStyle() {
        val registry = edgeLadderFromStops(
            STOPS.map { EdgeStop(it.at, it.id) },
            mapOf("bundle" to EdgeRungStyle(thickness = 1.0), "ribbon" to EdgeRungStyle(thickness = 4.0)),
            hysteresis = 0.01,
        )
        assertEquals(listOf("bundle", "ribbon"), registry.ids)
        assertEquals("ribbon", registry.select(EDGE, sample(1.0))?.rung?.id)
        assertEquals("bundle", registry.select(EDGE, sample(0.5))?.rung?.id)
        assertTrue(registry.ids.size == 2)
    }
}
