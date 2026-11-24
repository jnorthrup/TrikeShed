package narchy.spacegraph.representations

// Upstream test/representation-ladder.test.ts at 125b369, 9 cases, expected values copied from upstream.
// Deviation: upstream passes ScaleStop objects to ladderFromStops; here ScaleStop does not implement RungStop,
// so the test maps each stop to a RungStop fixture first.

import narchy.spacegraph.zoom.ScaleDomain
import narchy.spacegraph.zoom.ScaleDomainConfig
import narchy.spacegraph.zoom.ScaleStop
import narchy.spacegraph.zoom.ZoomRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Node(
    override val id: String,
    override val kind: String,
    override val importance: Double? = null,
) : LadderNode

private data class Cluster(
    override val id: String,
    override val kind: String,
    override val importance: Double? = null,
    override val clusterOf: List<String>? = null,
) : ClusterNode

private data class LadderStop(override val at: Double, override val id: String) : RungStop

private val STOPS = listOf(
    ScaleStop(at = 0.0, id = "dot", importance = 0.0, transitionDuration = 0.2),
    ScaleStop(at = 0.25, id = "glyph", importance = 0.25),
    ScaleStop(at = 0.5, id = "card", importance = 0.5),
    ScaleStop(at = 0.75, id = "widget", importance = 0.75, transitionDuration = 0.2),
    ScaleStop(at = 1.0, id = "detail", importance = 1.0),
)

private val DOMAIN = ScaleDomain(
    ScaleDomainConfig(stops = STOPS, range = ZoomRange(min = 100.0, max = 2000.0, closerIsLarger = false)),
)

private fun sample(normalized: Double) = DOMAIN.sample(normalized, DOMAIN.rawAt(normalized))

private fun build(rung: String): (LadderNode, LadderContext) -> String = { node, _ -> "$rung:${node.id}" }

private fun ladder(hysteresis: Double = 0.0): RepresentationLadder<LadderNode, String> =
    RepresentationLadder(
        STOPS.map { stop -> LadderRung(id = stop.id, at = stop.at, build = build(stop.id)) },
        LadderConfig(hysteresis = hysteresis),
    )

private val NODE = Node("n1", "tool")

class RepresentationLadderTest {
    // 'registers host-ordered rungs and selects the one covering the scale sample'
    @Test
    fun registersHostOrderedRungsAndSelectsTheOneCoveringTheScaleSample() {
        val registry = ladder()
        assertEquals(DEFAULT_RUNG_IDS, registry.ids)
        assertEquals("dot", registry.select(NODE, sample(0.1))?.rung?.id)
        assertEquals("glyph", registry.select(NODE, sample(0.3))?.rung?.id)
        assertEquals("widget", registry.select(NODE, sample(0.99))?.rung?.id)
    }

    // 'uses the last rung at the far end of the scale'
    @Test
    fun usesTheLastRungAtTheFarEndOfTheScale() {
        assertEquals("detail", ladder().select(NODE, sample(1.0))?.rung?.id)
    }

    // 'keeps node ids stable while the appearance changes'
    @Test
    fun keepsNodeIdsStableWhileTheAppearanceChanges() {
        val selection = ladder().select(NODE, sample(0.55))
        assertEquals("n1", selection?.id)
        assertEquals("card", selection?.rung?.id)
        assertEquals("card:n1", selection?.value)
    }

    // 'respects kind and importance gates, falling back to a coarser rung'
    @Test
    fun respectsKindAndImportanceGatesFallingBackToACoarserRung() {
        val registry = RepresentationLadder<LadderNode, String>(
            listOf(
                LadderRung(id = "dot", at = 0.0, kinds = listOf("tool"), build = build("dot")),
                LadderRung(
                    id = "card",
                    at = 0.5,
                    kinds = listOf("session"),
                    minImportance = 0.8,
                    build = build("card"),
                ),
            ),
        )
        assertEquals("dot", registry.select(NODE, sample(1.0))?.rung?.id)
        assertNull(registry.select(Node("s1", "session"), sample(0.6)))
        assertEquals(
            "card",
            registry.select(Node("s1", "session", importance = 0.9), sample(0.6))?.rung?.id,
        )
    }

    // 'holds a rung inside the dead zone on both sides of a boundary'
    @Test
    fun holdsARungInsideTheDeadZoneOnBothSidesOfABoundary() {
        val registry = ladder(0.05)
        assertEquals("glyph", registry.select(NODE, sample(0.53), "glyph")?.rung?.id)
        assertEquals("dot", registry.select(NODE, sample(0.28), "dot")?.rung?.id)
        assertEquals("dot", registry.select(NODE, sample(0.18), "glyph")?.rung?.id)
        assertEquals("widget", registry.select(NODE, sample(0.72), "widget")?.rung?.id)
    }

    // 'resolves a model by stable id and reports swaps with the domain duration'
    @Test
    fun resolvesAModelByStableIdAndReportsSwapsWithTheDomainDuration() {
        val registry = ladder()
        val nodes = listOf(NODE, Node("n2", "session"))
        val first = registry.resolve(nodes, sample(0.1))
        val second = registry.resolve(nodes, sample(0.8), mapOf("n1" to "dot"))

        assertEquals(listOf("n1", "n2"), first.keys.toList())
        assertEquals("widget", second["n1"]?.rung?.id)
        assertEquals(
            listOf(RungChange(id = "n1", from = "dot", to = "widget", duration = 0.2)),
            representationChanges(mapOf("n1" to "dot"), mapOf("n1" to "widget"), sample(0.8)),
        )
    }

    // 'derives rungs from scale stops and skips rungs without a builder'
    @Test
    fun derivesRungsFromScaleStopsAndSkipsRungsWithoutABuilder() {
        val registry = ladderFromStops<LadderNode, String>(
            STOPS.map { LadderStop(it.at, it.id) },
            mapOf(
                "dot" to build("dot"),
                "glyph" to build("glyph"),
                "detail" to build("detail"),
            ),
            LadderConfig(hysteresis = 0.01),
        )
        assertEquals(listOf("dot", "glyph", "detail"), registry.ids)
        assertEquals("detail", registry.select(NODE, sample(1.0))?.rung?.id)
        assertEquals("glyph", registry.select(NODE, sample(0.4))?.rung?.id)
    }

    // 'lets a host register a rung after construction'
    @Test
    fun letsAHostRegisterARungAfterConstruction() {
        val registry = RepresentationLadder<LadderNode, String>()
        assertNull(registry.select(NODE, sample(0.5)))
        registry.register(LadderRung(id = "card", at = 0.5, build = build("card")))
        assertEquals(listOf("card"), registry.ids)
        assertEquals("card:n1", registry.select(NODE, sample(0.5))?.value)
    }

    // 'collapses clusters as representations until the host expand rung'
    @Test
    fun collapsesClustersAsRepresentationsUntilTheHostExpandRung() {
        val nodes: List<ClusterNode> = listOf(
            Cluster("c1", "cluster", clusterOf = listOf("a", "b")),
            Cluster("a", "tool"),
            Cluster("b", "tool"),
            Cluster("c", "tool"),
        )
        val registry = RepresentationLadder<ClusterNode, String>(
            STOPS.map { stop -> LadderRung(id = stop.id, at = stop.at, build = build(stop.id)) },
        )

        val collapsed = collapseClusters(nodes, sample(1.0), registry, "card")
        assertEquals(listOf("c1", "c"), collapsed.visible.toList())
        assertEquals("detail", collapsed.clusters["c1"])

        val expanded = collapseClusters(nodes, sample(0.5), registry, "card")
        assertEquals(listOf("c1", "a", "b", "c"), expanded.visible.toList())
        assertTrue(expanded.clusters.isEmpty())
    }
}
