package narchy.spacegraph.layout

// Upstream test/layout/strategies.test.ts at 125b369, 15 cases, expected values copied from upstream.
// Upstream's `Strategies.layoutGraph` and `Strategies.laneAnchors` are the top-level functions here.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class TestNode(
    override val id: String,
    override val kind: String,
    val turnID: String? = null,
    val clusterOf: List<String>? = null,
) : LayoutNode

data class TestEdge(
    override val id: String,
    override val source: String,
    override val target: String,
    override val kind: String,
) : LayoutEdge

private fun ownerTurn(model: LayoutModel<TestNode, TestEdge>, node: TestNode): String? {
    val edge = model.edges.find { it.target == node.id }
    val other = edge?.let { e -> model.nodes.find { it.id == e.source } }

    return other?.turnID
}

private fun config(turnIdFn: (TestNode) -> String? = { it.turnID }) = LayoutConfig<TestNode, TestEdge, String>(
    nodeSizeFn = { node -> if (node.kind == "chip") Pair(100.0, 40.0) else Pair(200.0, 60.0) },
    laneList = listOf("top", "bottom"),
    laneOfFn = { node -> if (node.kind == "chip") "top" else "bottom" },
    depthMap = mapOf("root" to 0.0, "turn" to 1.0, "work" to 2.0),
    turnIdFn = turnIdFn,
    ownerTurnFn = { model, node -> if (node.kind == "file") ownerTurn(model, node) else null },
    parentOfFn = { _, node ->
        when (node.kind) {
            "turn" -> "root"
            "work" -> node.turnID
            else -> null
        }
    },
    clusterOfFn = { node -> node.clusterOf },
    geometry = LayoutGeometry(standardSize = Pair(200.0, 60.0), indentStep = 100.0, depthLevels = 3, padding = 40.0),
)

private val CONFIG = config()

private fun makeModel(): LayoutModel<TestNode, TestEdge> = LayoutModel(
    nodes = listOf(
        TestNode("root", "root"),
        TestNode("t1", "turn", turnID = "t1"),
        TestNode("t2", "turn", turnID = "t2"),
        TestNode("a", "work", turnID = "t1"),
        TestNode("b", "work", turnID = "t2"),
        TestNode("file", "file"),
    ),
    edges = listOf(TestEdge("e1", "a", "file", "writes")),
    root = "root",
    turns = listOf("t1", "t2"),
)

private fun wideModel(turns: Int): LayoutModel<TestNode, TestEdge> {
    val nodes = mutableListOf(TestNode("root", "root"))
    val turnIDs = mutableListOf<String>()

    for (turn in 0 until turns) {
        val turnID = "t$turn"
        turnIDs.add(turnID)
        nodes.add(TestNode(turnID, "turn", turnID = turnID))
        for (member in 0 until 4) nodes.add(TestNode("$turnID-n$member", "work", turnID = turnID))
    }

    return LayoutModel(nodes = nodes, edges = emptyList(), root = "root", turns = turnIDs)
}

private const val LANE_Y = 60.0 + 24.0

class StrategiesTest {
    // lanes

    // 'is deterministic'
    @Test
    fun isDeterministic() {
        val model = makeModel()

        assertEquals(
            layoutGraph(model, CONFIG).positions.entries.toList(),
            layoutGraph(model, CONFIG).positions.entries.toList(),
        )
    }

    // 'puts a node in its lane and its kind in its depth'
    @Test
    fun putsANodeInItsLaneAndItsKindInItsDepth() {
        val layout = layoutGraph(makeModel(), CONFIG)

        assertEquals(LANE_Y, layout.positions.getValue("a").y)
        assertEquals(2 * 64.0, layout.positions.getValue("a").z)
        assertEquals(0.0, layout.positions.getValue("root").z)
    }

    // 'anchors a node without a turn in the turn that owns it'
    @Test
    fun anchorsANodeWithoutATurnInTheTurnThatOwnsIt() {
        val layout = layoutGraph(makeModel(), CONFIG)
        val work = layout.positions.getValue("a")
        val file = layout.positions.getValue("file")

        assertTrue(file.x > work.x)
        assertTrue(file.x < layout.positions.getValue("b").x)
    }

    // 'never overlaps two nodes of one lane'
    @Test
    fun neverOverlapsTwoNodesOfOneLane() {
        val model = makeModel()
        val layout = layoutGraph(model, CONFIG)
        val placed = model.nodes.filter { layout.positions[it.id]?.y == LANE_Y }

        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val gap = kotlin.math.abs(layout.positions.getValue(placed[i].id).x - layout.positions.getValue(placed[j].id).x)
            val needed = (CONFIG.nodeSizeFn(placed[i]).first + CONFIG.nodeSizeFn(placed[j]).first) / 2
            assertTrue(gap >= needed, "${placed[i].id} and ${placed[j].id} overlap: gap $gap < $needed")
        }
    }

    // 'restricts the placed nodes to the ones the host includes'
    @Test
    fun restrictsThePlacedNodesToTheOnesTheHostIncludes() {
        val layout = layoutGraph(makeModel(), CONFIG, LayoutOptions(include = { it.kind == "chip" }))

        assertEquals(0, layout.positions.size)
    }

    // 'appends without moving the earlier nodes'
    @Test
    fun appendsWithoutMovingTheEarlierNodes() {
        val model = makeModel()
        val before = layoutGraph(model, CONFIG).positions
        val extended = LayoutModel(
            nodes = model.nodes + TestNode("c", "work", turnID = "t2"),
            edges = model.edges,
            root = model.root,
            turns = model.turns,
        )
        val after = layoutGraph(extended, CONFIG).positions

        for (id in before.keys) assertEquals(before[id], after[id])
    }

    // 'bounds a derived cluster over its members'
    @Test
    fun boundsADerivedClusterOverItsMembers() {
        val base = makeModel()
        val model = LayoutModel(
            nodes = base.nodes + TestNode("group", "work", clusterOf = listOf("a", "b")),
            edges = base.edges,
            root = base.root,
            turns = base.turns,
        )
        val layout = layoutGraph(model, CONFIG)
        val bounds = layout.clusters.getValue("group")

        for (id in listOf("a", "b")) {
            val point = layout.positions.getValue(id)
            assertTrue(point.x >= bounds.min.x)
            assertTrue(point.x <= bounds.max.x)
        }
    }

    // 'emits a band per turn'
    @Test
    fun emitsABandPerTurn() {
        val layout = layoutGraph(makeModel(), CONFIG)

        assertTrue(layout.clusters.containsKey("t1"))
        assertTrue(layout.clusters.containsKey("t2"))
    }

    // 'draws a node that names both a turn and an owner in the owner's column'
    @Test
    fun drawsANodeThatNamesBothATurnAndAnOwnerInTheOwnersColumn() {
        val base = makeModel()
        val model = LayoutModel(
            nodes = base.nodes + TestNode("f2", "file", turnID = "t2"),
            edges = base.edges + TestEdge("e2", "a", "f2", "writes"),
            root = base.root,
            turns = base.turns,
        )

        val layout = layoutGraph(model, CONFIG)
        val owned = layout.positions.getValue("f2")
        val owner = layout.positions.getValue("a")
        val other = layout.positions.getValue("b")

        assertTrue(owned.x > owner.x)
        assertTrue(owned.x < other.x)
    }

    // 'keeps the place of a turn that drew nothing'
    @Test
    fun keepsThePlaceOfATurnThatDrewNothing() {
        val model = makeModel()
        fun span(layout: GraphLayout): Double = layout.positions.getValue("b").x - layout.positions.getValue("a").x

        val without = span(layoutGraph(model, CONFIG))
        val withEmpty = span(
            layoutGraph(
                LayoutModel(nodes = model.nodes, edges = model.edges, root = model.root, turns = listOf("t1", "empty", "t2")),
                CONFIG,
            ),
        )

        assertEquals(CONFIG.geometry.standardSize.first + 96.0, withEmpty - without)
    }

    // timeline

    // 'flows the turns left to right'
    @Test
    fun flowsTheTurnsLeftToRight() {
        val layout = layoutGraph(makeModel(), CONFIG, LayoutOptions(strategy = LaneLayoutStrategy.timeline))

        assertEquals(LaneLayoutStrategy.timeline, layout.strategy)
        assertTrue(layout.positions.getValue("a").x < layout.positions.getValue("b").x)
    }

    // 'emits a band per turn' (timeline)
    @Test
    fun emitsABandPerTurnInTheTimeline() {
        val layout = layoutGraph(makeModel(), CONFIG, LayoutOptions(strategy = LaneLayoutStrategy.timeline))

        assertTrue(layout.clusters.containsKey("t1"))
        assertTrue(layout.clusters.containsKey("t2"))
    }

    // hierarchy

    // 'indents the deeper kinds to the right'
    @Test
    fun indentsTheDeeperKindsToTheRight() {
        val layout = layoutGraph(makeModel(), CONFIG, LayoutOptions(strategy = LaneLayoutStrategy.hierarchy))

        assertEquals(LaneLayoutStrategy.hierarchy, layout.strategy)
        assertTrue(layout.positions.getValue("a").x > layout.positions.getValue("root").x)
        assertTrue(layout.positions.getValue("a").z > layout.positions.getValue("root").z)
    }

    // 'wraps the outline into capped columns'
    @Test
    fun wrapsTheOutlineIntoCappedColumns() {
        val base = makeModel()
        val model = LayoutModel(
            nodes = base.nodes + TestNode("c", "work", turnID = "t2"),
            edges = base.edges,
            root = base.root,
            turns = base.turns,
        )
        val layout = layoutGraph(model, CONFIG, LayoutOptions(strategy = LaneLayoutStrategy.hierarchy, rows = 2))
        val rows = layout.positions.values.map { it.y }.toSet()

        assertTrue(rows.size <= 2)
    }

    // laneAnchors

    // 'anchors every lane in order'
    @Test
    fun anchorsEveryLaneInOrder() {
        val anchors = laneAnchors(CONFIG)

        assertEquals(listOf(LaneAnchor("top", 0.0), LaneAnchor("bottom", 84.0)), anchors)
    }

    // cost

    // 'asks each node for its turn a bounded number of times (%s)' — one case per strategy
    private fun assertBoundedTurnCalls(strategy: LaneLayoutStrategy) {
        val model = wideModel(40)
        var calls = 0
        val counting = config(turnIdFn = { node ->
            calls += 1
            node.turnID
        })

        layoutGraph(model, counting, LayoutOptions(strategy = strategy))

        assertTrue(calls <= model.nodes.size * 3 + 8, "$strategy asked $calls times for ${model.nodes.size} nodes")
    }

    @Test fun costLanes() = assertBoundedTurnCalls(LaneLayoutStrategy.lanes)
    @Test fun costTimeline() = assertBoundedTurnCalls(LaneLayoutStrategy.timeline)
    @Test fun costHierarchy() = assertBoundedTurnCalls(LaneLayoutStrategy.hierarchy)
}
