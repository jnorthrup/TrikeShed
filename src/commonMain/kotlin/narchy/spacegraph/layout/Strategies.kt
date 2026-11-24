package narchy.spacegraph.layout

// Port of upstream src/layout/strategies.ts at 125b369.
// Generic layout strategies: deterministic, append-only placement of nodes in a lanes band, a chronological timeline,
// a hierarchical outline, a tree, and a force simulation. The core owns the geometry; the host owns the vocabulary
// (box sizes, lane names, depths, turn grouping) through the config.
// Upstream has no test that imports this file; nothing in the port checks it beyond the compile gate.

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.appendTo

/** Internal layout strategies (host-owned, not plugin layouts). */
enum class LaneLayoutStrategy { lanes, timeline, hierarchy, tree, force }

val LANE_LAYOUT_STRATEGIES: List<LaneLayoutStrategy> = LaneLayoutStrategy.entries.toList()

interface LayoutNode {
    val id: String
    val kind: String
}

interface LayoutEdge {
    val id: String
    val source: String
    val target: String
    val kind: String
}

class LayoutModel<Node : LayoutNode, Edge : LayoutEdge>(
    val nodes: List<Node>,
    val edges: List<Edge>,
    /** The id of the root node. */
    val root: String,
    /** The ids of the turns, in order. */
    val turns: List<String>,
)

data class LayoutGeometry(
    /** The footprint of a standard node box. */
    val standardSize: Pair<Double, Double>,
    /** The horizontal indent per hierarchy depth. */
    val indentStep: Double,
    /** The number of hierarchy depth levels. */
    val depthLevels: Int,
    /** The extra room around a turn column. */
    val padding: Double,
)

class LayoutConfig<Node : LayoutNode, Edge : LayoutEdge, Name>(
    /** The box a node occupies. */
    val nodeSizeFn: (Node) -> Pair<Double, Double>,
    /** The ordered lane names. */
    val laneList: List<Name>,
    /** The lane a node belongs to. */
    val laneOfFn: (Node) -> Name,
    /** The depth of a node kind. */
    val depthMap: Map<String, Double>,
    /** The turn a node belongs to; a turn node owns itself. */
    val turnIdFn: (Node) -> String?,
    /** The turn that owns a node with no turn of its own. */
    val ownerTurnFn: ((LayoutModel<Node, Edge>, Node) -> String?)? = null,
    /** The members of a derived cluster chip; null when the node is not a cluster. */
    val clusterOfFn: ((Node) -> List<String>?)? = null,
    /** The parent of a node in the tree layout; null for a root. */
    val parentOfFn: ((LayoutModel<Node, Edge>, Node) -> String?)? = null,
    /** The ids a node is pulled towards in the force layout. */
    val forceLinksFn: ((LayoutModel<Node, Edge>, Node) -> List<String>)? = null,
    /** The measurements the strategies lay out against. */
    val geometry: LayoutGeometry,
)

data class LayoutOptions<Node : LayoutNode>(
    val strategy: LaneLayoutStrategy? = null,
    /** The horizontal distance between adjacent nodes in a lane. */
    val gap: Double? = null,
    /** The vertical distance between lanes. */
    val laneGap: Double? = null,
    /** The horizontal distance between turn columns. */
    val turnGap: Double? = null,
    /** The Z distance between hierarchy depths. */
    val depthStep: Double? = null,
    /** The cards per wrapped row before a turn's block folds to the next row. */
    val columns: Int? = null,
    /** Timeline axis: turn columns left-to-right, or turn rows stacked top-to-bottom. */
    val orientation: String? = null,
    /** The leaves a tree block holds before it folds to the next row. */
    val treeColumns: Int? = null,
    /** The vertical distance between rows. */
    val rowGap: Double? = null,
    /** The rows per column before the outline wraps into the next column. */
    val rows: Int? = null,
    /** Restricts the nodes the lanes layout places. */
    val include: ((Node) -> Boolean)? = null,
    /** Overrides the minimum width of a turn column in the lanes layout. */
    val minColumnWidth: Double? = null,
    /**
     * The positions the previous layout assigned. A layout that cannot keep its boxes where they were — the tree
     * re-centres a parent, the force simulation relaxes — reads them here, so a streamed session never moves a node
     * the reader is looking at.
     */
    val previous: Map<String, Position>? = null,
)

data class GraphLayout(
    val positions: Map<String, Position>,
    val clusters: Map<String, LayoutBounds>,
    val bounds: LayoutBounds,
    val strategy: LaneLayoutStrategy,
)

/** The column the session's own nodes share, before the first turn's column. */
private const val SESSION_COLUMN = "__session__"

private fun depthOf(depthMap: Map<String, Double>, kind: String): Double = depthMap[kind] ?: 0.0

/** The bounds over the placed positions of a set of node ids; null when none are placed. */
private fun memberPositions(ids: List<String>?, positions: Map<String, Position>): LayoutBounds? {
    val members = (ids ?: emptyList()).mapNotNull { positions[it] }

    return if (members.isNotEmpty()) boundsOf(members) else null
}

/** The result of a layout: the placed positions, the derived clusters, the bounds, and the strategy. */
private fun finishLayout(
    positions: Map<String, Position>,
    strategy: LaneLayoutStrategy,
    clusters: Map<String, LayoutBounds> = emptyMap(),
): GraphLayout = GraphLayout(
    positions = positions,
    clusters = clusters,
    bounds = boundsOf(positions.values.toList()),
    strategy = strategy,
)

/** Derived cluster chips keep their membership order; their bounds drive the host's bands. */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> addDerivedClusters(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    positions: Map<String, Position>,
    clusters: MutableMap<String, LayoutBounds>,
) {
    for (node in model.nodes) {
        val members = config.clusterOfFn?.invoke(node) ?: continue
        val bounds = memberPositions(members, positions)
        if (bounds != null) clusters[node.id] = bounds
    }
}

/** The nodes bucketed by turn in model order, and the nodes that belong to no turn at all. */
private data class TurnBuckets<Node>(val global: MutableList<Node>, val byTurn: MutableMap<String, MutableList<Node>>)

private fun <Node : LayoutNode, Edge : LayoutEdge, Name> bucketByTurn(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
): TurnBuckets<Node> {
    val global = mutableListOf<Node>()
    val byTurn = mutableMapOf<String, MutableList<Node>>()

    for (node in model.nodes) {
        val turn = config.turnIdFn(node)

        if (turn == null) {
            global.add(node)
            continue
        }

        appendTo(byTurn, turn, node)
    }

    return TurnBuckets(global, byTurn)
}

/**
 * The nodes bucketed by turn in model order. One pass builds the buckets, and every turn reads its own; a filter per
 * turn was quadratic over a long session (T6.6).
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> nodesByTurn(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
): Map<String, MutableList<Node>> = bucketByTurn(model, config).byTurn

/**
 * The members of a turn's column: the nodes the turn owns, in model order, with the turn node itself included even
 * when it does not name its own turn.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge> turnColumnNodes(
    members: List<Node>,
    turnID: String,
    order: Map<String, Int>,
    model: LayoutModel<Node, Edge>,
): MutableList<Node> {
    val turnIndex = order[turnID] ?: return members.toMutableList()

    val turnNode = model.nodes[turnIndex]

    if (members.any { it.id == turnID }) return members.toMutableList()

    val merged = members.toMutableList()
    val at = merged.indexOfFirst { (order[it.id] ?: 0) > turnIndex }

    merged.add(if (at < 0) merged.size else at, turnNode)

    return merged
}

/**
 * The chronological blocks shared by the timeline and the hierarchy: the root group first (the root and any node
 * without an owning turn), then one block per turn with its members in model order.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> turnBlocks(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
): List<List<Node>> {
    val (global, byTurn) = bucketByTurn(model, config)
    val blocks = mutableListOf<List<Node>>()

    if (global.isNotEmpty()) blocks.add(global)

    for (turnID in model.turns) {
        val nodes = byTurn[turnID]

        if (nodes != null && nodes.isNotEmpty()) blocks.add(nodes)
    }

    return blocks
}

/**
 * Lanes: the session column, then one column per turn whose width is its own widest lane. Y is the semantic activity
 * lane, so the lanes read as horizontal bands.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> lanesLayout(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node>,
): GraphLayout {
    val gap = options.gap ?: 24.0
    val laneGap = options.laneGap ?: 24.0
    val turnGap = options.turnGap ?: 96.0
    val depthStep = options.depthStep ?: 64.0
    val laneHeight = config.geometry.standardSize.second + laneGap
    val minColumnWidth = options.minColumnWidth ?: config.geometry.standardSize.first

    val layout = GridPlacement(
        GridConfig(
            lanes = config.laneList.map { it.toString() },
            lanePitch = laneHeight,
            step = gap,
            groupGap = turnGap,
            depthStep = depthStep,
            minColumnWidth = minColumnWidth,
        ),
    )

    // A host that draws a subset (a level, a lens) restricts the nodes that consume space.
    val nodes = options.include?.let { model.nodes.filter(it) } ?: model.nodes

    // One pass over the visible nodes builds the session column and the per-turn buckets. A node that names both a
    // turn and an owner belongs to the owner's column: it is anchored there, so that is where it is drawn.
    val byTurn = mutableMapOf<String, MutableList<Node>>()
    val sessionColumn = mutableListOf<Node>()

    for (node in nodes) {
        val turn = config.turnIdFn(node)
        val owner = config.ownerTurnFn?.invoke(model, node)
        val column = if (node.id == model.root) SESSION_COLUMN else (owner ?: turn ?: SESSION_COLUMN)

        if (column == SESSION_COLUMN) sessionColumn.add(node)
        else appendTo(byTurn, column, node)
    }

    val columns = mutableListOf<Pair<String, List<Node>>>()

    if (sessionColumn.isNotEmpty()) columns.add(SESSION_COLUMN to sessionColumn)

    for (turnID in model.turns) columns.add(turnID to (byTurn[turnID] ?: emptyList()))

    val items = mutableListOf<PlacementItem<GridMeta>>()

    // Every turn is a column, including an empty one: the column is the turn's place in the session, and a session
    // does not close the gap of a turn that drew nothing. An empty column is declared, so the grid still opens and
    // closes it.
    for ((column, members) in columns) {
        if (members.isEmpty()) {
            items.add(PlacementItem(id = column, width = 0.0, meta = GridMeta(group = column, empty = true)))
            continue
        }

        for (node in members)
            items.add(
                PlacementItem(
                    id = node.id,
                    width = config.nodeSizeFn(node).first,
                    depth = depthOf(config.depthMap, node.kind),
                    meta = GridMeta(group = column, lane = config.laneOfFn(node).toString()),
                ),
            )
    }

    val positions = LinkedHashMap(layout.place(items))
    val clusters = mutableMapOf<String, LayoutBounds>()

    addDerivedClusters(model, config, positions, clusters)

    // Turn bands span all of a turn's members and the turn node itself.
    val allByTurn = nodesByTurn(model, config)

    for (turnID in model.turns) {
        val members = memberPositions(
            listOf(turnID) + (allByTurn[turnID] ?: emptyList()).map { it.id },
            positions,
        )

        if (members != null) clusters[turnID] = members
    }

    return finishLayout(positions, LaneLayoutStrategy.lanes, clusters)
}

/**
 * Timeline: chronological flow with one band per turn. Column orientation runs time left-to-right and stacks activity
 * inside each turn; row orientation swaps the axes so turns are horizontal stripes stacked top-to-bottom and activity
 * runs left-to-right inside each stripe.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> timelineLayout(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node>,
): GraphLayout {
    val gap = options.gap ?: 24.0
    val rowGap = options.rowGap ?: 20.0
    val depthStep = options.depthStep ?: 64.0
    val columns = maxOf(1, options.columns ?: 6)
    val rows = options.orientation == "rows"
    val (standardWidth, standardHeight) = config.geometry.standardSize
    // Placement always advances bands on X and members on Y; row orientation feeds swapped card dimensions and
    // transposes the result, so both orientations share one append-only engine.
    val bandPitch = if (rows) standardWidth + gap else standardHeight + rowGap
    val bandWidth =
        columns * ((if (rows) standardHeight else standardWidth) + gap) + config.geometry.padding
    val cardWidth = { node: Node ->
        if (rows) config.nodeSizeFn(node).second else config.nodeSizeFn(node).first
    }

    val layout = GridPlacement(GridConfig(lanePitch = bandPitch, step = gap, groupGap = 0.0, depthStep = depthStep))
    val items = mutableListOf<PlacementItem<GridMeta>>()
    val pushRow = { nodes: List<Node>, group: String ->
        for (i in nodes.indices step columns)
            for (node in nodes.subList(i, minOf(i + columns, nodes.size)))
                items.add(
                    PlacementItem(
                        id = node.id,
                        width = cardWidth(node),
                        depth = depthOf(config.depthMap, node.kind),
                        meta = GridMeta(group = group, row = i / columns, columnWidth = bandWidth),
                    ),
                )
    }

    // The leading block (the root group, or the first turn when the model has none) opens the timeline.
    val leading = turnBlocks(model, config).firstOrNull()

    if (leading != null && leading.isNotEmpty()) pushRow(leading, SESSION_COLUMN)

    // Each turn gets its own column band. The members come from one pass over the model, not one filter per turn.
    val byTurn = nodesByTurn(model, config)
    val order = model.nodes.withIndex().associate { it.value.id to it.index }
    val columnsByTurn = mutableMapOf<String, List<Node>>()

    for (turnID in model.turns) {
        val turnNodes = turnColumnNodes(byTurn[turnID] ?: emptyList(), turnID, order, model)

        if (turnNodes.isEmpty()) continue

        columnsByTurn[turnID] = turnNodes
        pushRow(turnNodes, turnID)
    }

    val positions = LinkedHashMap(layout.place(items))
    val clusters = mutableMapOf<String, LayoutBounds>()

    // The root band spans the leading block
    if (leading != null && leading.isNotEmpty()) {
        val members = memberPositions(listOf(model.root) + leading.map { it.id }, positions)

        if (members != null) clusters["session"] = members
    }

    // Turn band spans this turn's column
    for (turnID in model.turns) {
        val turnNodes = columnsByTurn[turnID] ?: continue

        val members = memberPositions(
            listOf(turnID) + turnNodes.filter { it.id != turnID }.map { it.id },
            positions,
        )

        if (members != null) clusters[turnID] = members
    }

    addDerivedClusters(model, config, positions, clusters)

    if (!rows) return finishLayout(positions, LaneLayoutStrategy.timeline, clusters)

    val oriented = LinkedHashMap<String, Position>()
    for ((id, position) in positions) oriented[id] = Vec3(position.y, position.x, position.z)

    val orientedClusters = LinkedHashMap<String, LayoutBounds>()
    for ((id, bounds) in clusters) {
        orientedClusters[id] = LayoutBounds(
            Vec3(bounds.min.y, bounds.min.x, bounds.min.z),
            Vec3(bounds.max.y, bounds.max.x, bounds.max.z),
        )
    }

    return finishLayout(oriented, LaneLayoutStrategy.timeline, orientedClusters)
}

/**
 * Hierarchy: a left-to-right indented outline. Depth reads as X offset (ancestry is a staircase), rows wrap within a
 * capped column height so cards stay legible. Positions are deterministic and append-only. Builds a proper tree from
 * parentOfFn and traverses it depth-first.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> hierarchyLayout(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node>,
): GraphLayout {
    val rowGap = options.rowGap ?: 12.0
    val depthStep = options.depthStep ?: 64.0
    val rowHeight = config.geometry.standardSize.second + rowGap
    // Cap column height to keep card text above the legibility floor.
    val rowsPerColumn = maxOf(1, options.rows ?: 14)
    val indentStep = config.geometry.indentStep
    // Column width accommodates the deepest indent, the card width, and the padding.
    val columnWidth =
        indentStep * config.geometry.depthLevels + config.geometry.standardSize.first + config.geometry.padding

    val layout = GridPlacement(
        GridConfig(lanePitch = rowHeight, step = options.gap ?: 24.0, groupGap = 0.0, depthStep = depthStep),
    )
    val items = mutableListOf<PlacementItem<GridMeta>>()
    var column = 0
    var row = 0

    // Build adjacency list from parentOfFn
    val children = mutableMapOf<String, MutableList<Node>>()
    val roots = mutableListOf<Node>()
    val nodeById = model.nodes.associateBy { it.id }

    for (node in model.nodes) {
        val parentId = config.parentOfFn?.invoke(model, node)
        if (parentId != null && nodeById.containsKey(parentId)) {
            appendTo(children, parentId, node)
        } else {
            roots.add(node)
        }
    }

    // Sort children by model order for deterministic output
    val order = model.nodes.withIndex().associate { it.value.id to it.index }
    for (bucket in children.values) bucket.sortBy { order[it.id] ?: 0 }
    roots.sortBy { order[it.id] ?: 0 }

    // Depth-first traversal to place nodes in indented outline
    fun placeNode(node: Node, depth: Int) {
        if (row >= rowsPerColumn) {
            row = 0
            column += 1
        }

        items.add(
            PlacementItem(
                id = node.id,
                width = config.nodeSizeFn(node).first,
                depth = depth.toDouble(),
                meta = GridMeta(
                    group = "column:$column",
                    row = row,
                    indent = depth * indentStep,
                    columnWidth = columnWidth,
                ),
            ),
        )
        row += 1

        // Recursively place children
        children[node.id]?.forEach { placeNode(it, depth + 1) }
    }

    // Place roots
    for (root in roots) placeNode(root, 0)

    return finishLayout(LinkedHashMap(layout.place(items)), LaneLayoutStrategy.hierarchy)
}

/**
 * Tree: a box hangs under its parent, the tree grows downward, and a parent sits over the middle of its children. The
 * parent relation is the host's: the tree reads the edges it binds, not the kinds.
 *
 * A tidy tree re-centres a parent when a child is appended, which would move a node the reader is looking at, so the
 * layout keeps the positions it already assigned (`previous`) and only the new boxes take a slot.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> treeLayout(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node>,
): GraphLayout {
    val layout = TreePlacement(
        TreeConfig(
            columnPitch = config.geometry.standardSize.first + (options.gap ?: 24.0),
            rowPitch = config.geometry.standardSize.second + (options.rowGap ?: 24.0),
            depthStep = options.depthStep ?: 64.0,
            columns = options.treeColumns,
        ),
    )

    val items = model.nodes.map { node ->
        PlacementItem(
            id = node.id,
            width = config.nodeSizeFn(node).first,
            depth = depthOf(config.depthMap, node.kind),
            meta = TreeMeta(parent = config.parentOfFn?.invoke(model, node)),
        )
    }

    return finishLayout(LinkedHashMap(layout.place(items, options.previous)), LaneLayoutStrategy.tree)
}

/**
 * Force: a simulation. The nodes push each other apart and the edges pull them together, so the shape of the session
 * decides where its parts sit.
 *
 * The layout contract says "no physics", because a live simulation jitters while the session streams. So the
 * simulation is deterministic — a fixed seed, a fixed number of steps, the model's own order — and it is frozen: the
 * boxes the layout already placed keep their position, and only the new ones relax against them.
 */
private fun <Node : LayoutNode, Edge : LayoutEdge, Name> forceLayout(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node>,
): GraphLayout {
    val layout = ForcePlacement(
        ForceCoreConfig(
            spacing = config.geometry.standardSize.first + (options.gap ?: 24.0),
            depthStep = options.depthStep ?: 64.0,
        ),
    )

    val items = model.nodes.map { node ->
        PlacementItem(
            id = node.id,
            width = config.nodeSizeFn(node).first,
            depth = depthOf(config.depthMap, node.kind),
            meta = ForceMeta(
                links = config.forceLinksFn?.invoke(model, node) ?: emptyList(),
                radius = config.nodeSizeFn(node).first / 2,
            ),
        )
    }

    return finishLayout(LinkedHashMap(layout.place(items, options.previous)), LaneLayoutStrategy.force)
}

/** Lays a model out with the requested strategy. */
fun <Node : LayoutNode, Edge : LayoutEdge, Name> layoutGraph(
    model: LayoutModel<Node, Edge>,
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node> = LayoutOptions(),
): GraphLayout = when (options.strategy ?: LaneLayoutStrategy.lanes) {
    LaneLayoutStrategy.hierarchy -> hierarchyLayout(model, config, options)
    LaneLayoutStrategy.timeline -> timelineLayout(model, config, options)
    LaneLayoutStrategy.tree -> treeLayout(model, config, options)
    LaneLayoutStrategy.force -> forceLayout(model, config, options)
    LaneLayoutStrategy.lanes -> lanesLayout(model, config, options)
}

/** A lane's name and the vertical centre of its band, for a lane-label overlay aligned to the lanes layout. */
data class LaneAnchor<Name>(val name: Name, val y: Double)

/** The vertical centre of each lane, for a lane-label overlay aligned to the lanes layout. */
fun <Node : LayoutNode, Edge : LayoutEdge, Name> laneAnchors(
    config: LayoutConfig<Node, Edge, Name>,
    options: LayoutOptions<Node> = LayoutOptions(),
): List<LaneAnchor<Name>> {
    val laneGap = options.laneGap ?: 24.0
    val laneHeight = config.geometry.standardSize.second + laneGap

    return config.laneList.mapIndexed { index, name -> LaneAnchor(name, index * laneHeight) }
}
