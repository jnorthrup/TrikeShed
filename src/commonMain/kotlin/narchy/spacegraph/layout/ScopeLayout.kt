package narchy.spacegraph.layout

// Port of upstream src/layout/scope-layout.ts at 125b369.
// Geometry for recursive scopes. Each scope solves its own interior; child scopes enter the parent as portal bodies
// sized from their solved bounds. Local frames are then composed top-down into world space, and edges are projected
// through those frames so a cross-scope edge has real endpoints. Deterministic for a given tree, seed and previous
// state. No renderer is involved.

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.appendTo

private const val DEFAULT_WIDTH = 120.0
private const val DEFAULT_HEIGHT = 64.0

private val ZERO: Position = Vec3(0.0, 0.0, 0.0)

/** The shared vocabulary of every solver; each engine receives only its own slice of it. */
private data class ScopeMeta(
    val links: List<String>,
    val radius: Double,
    val lane: String?,
    val row: Int?,
    val parent: String?,
)

private typealias SolverItem = PlacementItem<ScopeMeta>

private fun interface Solver {
    fun place(items: List<SolverItem>, previous: Map<String, Position>?): Map<String, Position>
}

data class ScopeFrame(
    val id: String,
    val parent: String?,
    val depth: Int,
    /** The world position of this scope's origin. */
    val origin: Position,
    /** World scale of this scope's contents. */
    val scale: Double,
    val bounds: CenterBounds,
    val active: Boolean,
)

data class ScopeEdgeView(
    val id: String,
    val source: String,
    val target: String,
    val from: Position,
    val to: Position,
    val interScope: Boolean,
)

class ScopeLayout(
    /** Qualified node id -> world position. */
    val positions: Map<String, Position>,
    /** Qualified node id -> position in its owning scope. */
    val locals: Map<String, Position>,
    val frames: Map<String, ScopeFrame>,
    val edges: List<ScopeEdgeView>,
    val bounds: CenterBounds,
)

data class ScopeSignals(
    /** Zoom band. Scopes deeper than `band + 1` are inactive. */
    val band: Int? = null,
    /** A qualified node or scope id whose ancestors stay active. */
    val focus: String? = null,
    /** Explicit depth cap; wins over `band`. */
    val maxDepth: Int? = null,
)

data class ScopeLayoutOptions(
    /** Default strategy for scopes that do not declare one. */
    val strategy: ScopeStrategy? = null,
    val seed: Int? = null,
    /** Base spacing passed to the solvers. */
    val gap: Double? = null,
    /** Measured 0..1 canvas coverage feeding scale-aware spacing; absent means keep the base gap. */
    val occupancy: Double? = null,
    /** 0..1 coverage target; with `occupancy`, tightens collision slack below it down to half. */
    val targetOccupancy: Double? = null,
    val depthStep: Double? = null,
    val nodePadding: Double? = null,
    /** Local positions keyed by scope path. Used by `pinned` and, optionally, as a solver warm start. */
    val previous: Map<String, Map<String, Position>>? = null,
    /** Seed the solvers with previous positions instead of reflowing. */
    val warmStart: Boolean = false,
    /** Qualified ids forced to their previous local position. */
    val pinned: Set<String>? = null,
    /** Active scope paths; inactive scopes are still laid out but flagged. */
    val active: Set<String>? = null,
)

private fun emptyBounds(): CenterBounds = boundsFromExtents(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

private fun hashPath(path: String): Int {
    var hash = -2128831035 // FNV-1a offset basis, as signed 32-bit
    for (ch in path) hash = (hash xor ch.code) * 16777619
    return hash
}

private fun itemsBounds(items: List<SolverItem>, positions: Map<String, Position>): CenterBounds {
    var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
    for (item in items) {
        val position = positions[item.id] ?: continue
        val halfWidth = item.width / 2
        val halfHeight = (item.height ?: item.width) / 2
        minX = minOf(minX, position.x - halfWidth)
        maxX = maxOf(maxX, position.x + halfWidth)
        minY = minOf(minY, position.y - halfHeight)
        maxY = maxOf(maxY, position.y + halfHeight)
        minZ = minOf(minZ, position.z)
        maxZ = maxOf(maxZ, position.z)
    }
    return if (minX == Double.POSITIVE_INFINITY) emptyBounds() else boundsFromExtents(minX, minY, minZ, maxX, maxY, maxZ)
}

private class ScopeLayoutState(
    val tree: ScopeTree,
    val options: ScopeLayoutOptions,
    val padding: Double,
) {
    val localsByPath = HashMap<String, MutableMap<String, Position>>()
    val boundsByPath = HashMap<String, CenterBounds>()
    val locals = LinkedHashMap<String, Position>()
    val frames = LinkedHashMap<String, ScopeFrame>()
    val world = LinkedHashMap<String, Position>()
    val edges = mutableListOf<ScopeEdgeView>()
}

/** Shared 3-axis transform. */
private fun transformPoint(origin: Position, scale: Double, point: Position): Position =
    Vec3(origin.x + scale * point.x, origin.y + scale * point.y, origin.z + scale * point.z)

private fun portalSize(bounds: CenterBounds, padding: Double): Pair<Double, Double> =
    Pair(bounds.size[0] + padding * 2, bounds.size[1] + padding * 2)

private fun solveScopes(state: ScopeLayoutState, entry: ScopeEntry) {
    for (child in entry.children) solveScopes(state, child)

    val links = HashMap<String, MutableList<String>>()
    fun addLink(from: String, to: String) = appendTo(links, from, to)
    val portalNodeIds = HashMap<String, String>()
    for (node in entry.nodes) {
        val opened = node.scope ?: continue
        if (entry.children.any { it.spec.id == opened }) portalNodeIds[opened] = node.id
    }
    fun localId(qualified: String): String? {
        val prefix = "${entry.path}/"
        if (!qualified.startsWith(prefix)) return null
        val local = qualified.substring(prefix.length)
        return portalNodeIds[local] ?: local
    }

    for (edge in entry.edges) {
        val source = localId(resolveScopeRef(state.tree, entry.path, edge.source))
        val target = localId(resolveScopeRef(state.tree, entry.path, edge.target))
        if (source == null || target == null) continue
        addLink(source, target)
        addLink(target, source)
    }

    val items = mutableListOf<SolverItem>()
    for (node in entry.nodes) {
        val child = node.scope?.let { opened -> entry.children.firstOrNull { it.spec.id == opened } }
        val bounds = child?.let { state.boundsByPath.getValue(it.path) }
        val declared = node.size ?: Pair(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        val (width, height) = if (bounds != null) portalSize(bounds, state.padding) else declared
        items.add(
            makeItem(node.id, width, height, links[node.id].orEmpty(), node.depth ?: 0,
                lane = node.lane, row = node.row, parent = node.parent)
        )
    }

    for (child in entry.children) {
        if (portalNodeIds.containsKey(child.spec.id)) continue
        val (width, height) = portalSize(state.boundsByPath.getValue(child.path), state.padding)
        items.add(makeItem(child.spec.id, width, height, links[child.spec.id].orEmpty(), 0))
    }

    val strategy = entry.spec.strategy ?: state.options.strategy ?: ScopeStrategy.FORCE
    val seed = hashPath(entry.path) xor (state.options.seed ?: 0)
    val solver = makeSolver(strategy, seed, state.options, items)
    val previous = state.options.previous?.get(entry.path)
    val solved = solver.place(items, if (state.options.warmStart) previous else null)
    val local = HashMap<String, Position>()

    for (item in items) {
        val qualified = "${entry.path}/${item.id}"
        val held = if (state.options.pinned?.contains(qualified) == true) previous?.get(item.id) else null
        val position = held ?: solved[item.id] ?: ZERO
        local[item.id] = position
        state.locals[qualified] = position
    }
    state.localsByPath[entry.path] = local
    state.boundsByPath[entry.path] = itemsBounds(items, local)
}

private fun makeItem(
    id: String,
    width: Double,
    height: Double,
    links: List<String>,
    depth: Int,
    lane: String? = null,
    row: Int? = null,
    parent: String? = null,
): SolverItem = PlacementItem(
    id = id,
    width = width,
    height = height,
    depth = depth.toDouble(),
    meta = ScopeMeta(links, maxOf(width, height) / 2, lane, row, parent),
)

private fun placeFrames(state: ScopeLayoutState, entry: ScopeEntry, parent: ScopeFrame?) {
    val scale = (parent?.scale ?: 1.0) * (entry.spec.scale ?: 1.0)
    val local = state.localsByPath.getValue(entry.path)
    val parentEntry = parent?.let { state.tree.byPath[it.id] }
    val portalNodeId = parentEntry?.nodes?.firstOrNull { it.scope == entry.spec.id }?.id ?: entry.spec.id
    val portal = parent?.let { state.localsByPath[it.id]?.get(portalNodeId) }
    val offset = entry.spec.offset ?: ZERO
    val origin = if (parent != null) {
        transformPoint(
            parent.origin, parent.scale,
            Vec3((portal?.x ?: 0.0) + offset.x, (portal?.y ?: 0.0) + offset.y, (portal?.z ?: 0.0) + offset.z),
        )
    } else {
        Vec3(offset.x, offset.y, offset.z)
    }

    val frame = ScopeFrame(
        id = entry.path,
        parent = parent?.id,
        depth = entry.depth,
        origin = origin,
        scale = scale,
        bounds = transformBounds(state.boundsByPath.getValue(entry.path), origin, scale),
        active = state.options.active?.contains(entry.path) ?: true,
    )
    state.frames[entry.path] = frame

    for (node in entry.nodes) {
        state.world["${entry.path}/${node.id}"] = transformPoint(origin, scale, local[node.id] ?: ZERO)
    }
    for (child in entry.children) placeFrames(state, child, frame)
}

private fun collectScopeEdges(state: ScopeLayoutState, entry: ScopeEntry) {
    for (edge in entry.edges) {
        val source = resolveScopeRef(state.tree, entry.path, edge.source)
        val target = resolveScopeRef(state.tree, entry.path, edge.target)
        val from = state.world[source] ?: state.frames[source]?.origin
        val to = state.world[target] ?: state.frames[target]?.origin
        if (from == null || to == null) {
            throw ScopeError(ScopeErrorCode.UNKNOWN_ENDPOINT, "cannot project edge ${edge.id}")
        }
        state.edges.add(
            ScopeEdgeView(
                id = edge.id,
                source = source,
                target = target,
                from = from,
                to = to,
                interScope = scopeOf(state.tree, source) != scopeOf(state.tree, target),
            )
        )
    }
    for (child in entry.children) collectScopeEdges(state, child)
}

/** Scale-aware packing: sparse coverage tightens collision slack toward the target, floored at half. */
fun occupancySpacingFactor(occupancy: Double?, target: Double?): Double {
    if (occupancy == null || target == null || occupancy == 0.0 || target <= 0.0) return 1.0
    return (occupancy / target).coerceIn(0.5, 1.0)
}

private fun makeSolver(strategy: ScopeStrategy, seed: Int, options: ScopeLayoutOptions, items: List<SolverItem>): Solver {
    val gap = options.gap ?: 160.0
    val nodePadding = (options.nodePadding ?: 20.0) * occupancySpacingFactor(options.occupancy, options.targetOccupancy)
    return when (strategy) {
        ScopeStrategy.GRID -> {
            val tallest = maxOf(80.0, items.maxOfOrNull { it.height ?: 0.0 } ?: 0.0)
            val widest = maxOf(80.0, items.maxOfOrNull { it.width } ?: 0.0)
            val grid = GridPlacement(
                GridConfig(
                    step = maxOf(gap, widest * 0.2),
                    lanePitch = maxOf(tallest * 1.5, 140.0) + nodePadding + gap,
                    groupGap = gap * 2,
                    depthStep = options.depthStep ?: 64.0,
                )
            )
            Solver { solverItems, previous ->
                grid.place(solverItems.map { item ->
                    PlacementItem(item.id, item.width, item.height, item.depth,
                        GridMeta(lane = item.meta?.lane, row = item.meta?.row))
                }, previous)
            }
        }
        ScopeStrategy.TREE -> {
            val tree = TreePlacement(
                TreeConfig(columnPitch = gap * 2, rowPitch = gap, depthStep = options.depthStep ?: 64.0)
            )
            Solver { solverItems, previous ->
                tree.place(solverItems.map { item ->
                    PlacementItem(item.id, item.width, item.height, item.depth, TreeMeta(parent = item.meta?.parent))
                }, previous)
            }
        }
        ScopeStrategy.FORCE -> {
            val force = ForcePlacement(
                ForceCoreConfig(
                    seed = seed,
                    spacing = gap,
                    depthStep = options.depthStep ?: 64.0,
                    nodePadding = nodePadding,
                    useBarnesHut = items.size > 200,
                )
            )
            Solver { solverItems, previous ->
                force.place(solverItems.map { item ->
                    PlacementItem(item.id, item.width, item.height, item.depth,
                        ForceMeta(links = item.meta?.links, radius = item.meta?.radius))
                }, previous)
            }
        }
    }
}

/** The scope paths that should be simulated at a zoom band, plus the ancestors of the focus. */
fun activeScopePaths(tree: ScopeTree, signals: ScopeSignals = ScopeSignals()): Set<String> {
    val maxDepth = signals.maxDepth ?: signals.band?.let { it + 1 } ?: Int.MAX_VALUE
    val active = LinkedHashSet<String>()
    for ((path, entry) in tree.byPath) {
        if (entry.depth <= maxDepth) active.add(path)
    }
    signals.focus?.let { focus ->
        var entry = tree.byPath[tree.nodePaths[focus] ?: focus]
        while (entry != null) {
            active.add(entry.path)
            entry = entry.parent
        }
    }
    return active
}

/** Solve every scope, compose their frames, and project the full graph into world space. */
fun layoutScopes(tree: ScopeTree, options: ScopeLayoutOptions = ScopeLayoutOptions()): ScopeLayout {
    val state = ScopeLayoutState(tree, options, options.nodePadding ?: 20.0)
    for (root in tree.roots) solveScopes(state, root)
    for (root in tree.roots) placeFrames(state, root, null)
    for (root in tree.roots) collectScopeEdges(state, root)

    val frameBounds: List<LayoutBounds> = state.frames.values.map { it.bounds }
    val bounds: LayoutBounds = if (frameBounds.isEmpty()) emptyBounds()
        else frameBounds.drop(1).fold(frameBounds[0]) { acc, b -> unionBounds(acc, b) }
    return ScopeLayout(
        positions = state.world,
        locals = state.locals,
        frames = state.frames,
        edges = state.edges,
        bounds = bounds as CenterBounds,
    )
}

/** The incremental change between two scope layouts, for animated transitions. */
fun scopeChanges(previous: ScopeLayout?, next: ScopeLayout): IncrementalLayout =
    computeIncremental(next.positions, previous?.positions)

fun scopePositionsAt(change: IncrementalLayout, t: Double): Map<String, Position> = interpolatedPositions(change, t)

class HostedScopeLayout(val layout: ScopeLayout, val change: IncrementalLayout, val tree: ScopeTree)

private fun localState(tree: ScopeTree, layout: ScopeLayout): Map<String, Map<String, Position>> {
    val state = HashMap<String, MutableMap<String, Position>>()
    for (path in tree.byPath.keys) state[path] = HashMap()
    for ((id, position) in layout.locals) {
        val path = scopeOf(tree, id) ?: continue
        state.getValue(path)[id.substring(path.length + 1)] = position
    }
    return state
}

/** A stateful host helper: keeps local positions across updates and reports what moved. */
class ScopeHost(private val options: ScopeLayoutOptions = ScopeLayoutOptions()) {
    private var previous: Map<String, Map<String, Position>>? = null
    private var last: ScopeLayout? = null

    fun update(
        specs: List<ScopeSpec>,
        signals: ScopeSignals = ScopeSignals(),
        overrides: ScopeLayoutOptions = ScopeLayoutOptions(),
    ): HostedScopeLayout {
        val tree = buildScopeTree(specs)
        val merged = mergeOptions(options, overrides).copy(
            previous = previous,
            active = activeScopePaths(tree, signals),
        )
        val layout = layoutScopes(tree, merged)
        previous = localState(tree, layout)
        val change = computeIncremental(layout.positions, last?.positions)
        last = layout
        return HostedScopeLayout(layout, change, tree)
    }
}

/** Upstream spreads `overrides` over `options`; here an override wins when it is set. */
internal fun mergeOptions(base: ScopeLayoutOptions, over: ScopeLayoutOptions): ScopeLayoutOptions = base.copy(
    strategy = over.strategy ?: base.strategy,
    seed = over.seed ?: base.seed,
    gap = over.gap ?: base.gap,
    occupancy = over.occupancy ?: base.occupancy,
    targetOccupancy = over.targetOccupancy ?: base.targetOccupancy,
    depthStep = over.depthStep ?: base.depthStep,
    nodePadding = over.nodePadding ?: base.nodePadding,
    previous = over.previous ?: base.previous,
    warmStart = over.warmStart || base.warmStart,
    pinned = over.pinned ?: base.pinned,
    active = over.active ?: base.active,
)
