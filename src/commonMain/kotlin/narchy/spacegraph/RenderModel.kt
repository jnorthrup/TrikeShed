package narchy.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf

/*
 * The SpaceGraphJS host contract, `@spacegraphjs/renderer` (autonull/spacegraphjs7 `packages/renderer/src/index.ts`,
 * ported at upstream ead8c84; ledger: docs/spacegraph/UPSTREAM.md). A host builds a [RenderModel], any contract
 * renderer (the SpaceGraph adapter, Cytoscape, SVG) draws it, and events return as string ids. Positions are
 * authoritative and the renderer never lays out, so the geometry this package already authors in commonMain is
 * what crosses the wire. Field names and string values are the contract's own.
 */

enum class NodeStatus(val wire: String) { NEUTRAL("neutral"), PENDING("pending"), RUNNING("running"), SUCCESS("success"), WARNING("warning"), ERROR("error") }

enum class NodeShape(val wire: String) { CARD("card"), PANEL("panel"), DOT("dot"), BADGE("badge"), GROUP("group") }

enum class EdgeConfidence(val wire: String) { EXPLICIT("explicit"), STRUCTURAL("structural"), INFERRED("inferred") }

enum class EdgeStyle(val wire: String) { SOLID("solid"), DASHED("dashed"), DOTTED("dotted") }

data class RenderNode(
    val id: String,
    val kind: String,
    val label: String,
    val position: Vec3,
    val summary: String? = null,
    /** Host-sanitized markup for [NodeShape.CARD]; the renderer positions it and never evaluates it. */
    val html: String? = null,
    val size: Vec3? = null,
    val status: NodeStatus? = null,
    val visible: Boolean? = null,
    val interactive: Boolean? = null,
    val shape: NodeShape? = null,
    /** The group this node belongs to; a group node may itself name a parent group. */
    val parent: String? = null,
    /** Serializable values only: no library objects cross the boundary. */
    val data: Map<String, Any?>? = null,
) {
    init {
        require(id.isNotBlank()) { "a render node needs an id" }
        require(parent != id) { "a node cannot be its own group" }
        require(position.finite && size?.finite != false) { "render coordinates must be finite" }
    }
}

data class RenderEdge(
    val id: String,
    val source: String,
    val target: String,
    val kind: String,
    val label: String? = null,
    val confidence: EdgeConfidence? = null,
    val visible: Boolean? = null,
    val directed: Boolean? = null,
    val style: EdgeStyle? = null,
) {
    init { require(id.isNotBlank() && source.isNotBlank() && target.isNotBlank()) { "a render edge needs id, source and target" } }
}

data class RenderExtent(val width: Double, val height: Double, val depth: Double? = null)

class RenderModel(
    val nodes: Series<RenderNode> = emptySeriesOf(),
    val edges: Series<RenderEdge> = emptySeriesOf(),
    val extent: RenderExtent? = null,
)

/** `RenderModelPatch`: what the next model adds, drops by id, and changes in place. */
class RenderModelPatch(
    val addedNodes: Series<RenderNode>,
    val removedNodes: Series<String>,
    val changedNodes: Series<RenderNode>,
    val addedEdges: Series<RenderEdge>,
    val removedEdges: Series<String>,
    val changedEdges: Series<RenderEdge>,
) {
    val empty: Boolean
        get() = addedNodes.size + removedNodes.size + changedNodes.size + addedEdges.size + removedEdges.size + changedEdges.size == 0
}

val Vec3.finite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

val Vec3.wire: Series<Double> get() = s_[x, y, z]

val RenderNode.wire: Map<String, Any?>
    get() = _m[
        "id" j id, "kind" j kind, "label" j label, "summary" j summary, "html" j html,
        "position" j position.wire.view, "size" j size?.wire?.view, "status" j status?.wire,
        "visible" j visible, "interactive" j interactive, "shape" j shape?.wire, "parent" j parent, "data" j data,
    ].filterValues { it != null }

val RenderEdge.wire: Map<String, Any?>
    get() = _m[
        "id" j id, "source" j source, "target" j target, "kind" j kind, "label" j label,
        "confidence" j confidence?.wire, "visible" j visible, "directed" j directed, "style" j style?.wire,
    ].filterValues { it != null }

val RenderExtent.wire: Map<String, Any?>
    get() = _m["width" j width, "height" j height, "depth" j depth].filterValues { it != null }

val RenderModel.wire: Map<String, Any?>
    get() = _m["nodes" j (nodes α { it.wire }).view, "edges" j (edges α { it.wire }).view, "extent" j extent?.wire]
        .filterValues { it != null }

val RenderModelPatch.wire: Map<String, Any?>
    get() = _m[
        "nodes" j _m["added" j (addedNodes α { it.wire }).view, "removed" j removedNodes.view, "changed" j (changedNodes α { it.wire }).view],
        "edges" j _m["added" j (addedEdges α { it.wire }).view, "removed" j removedEdges.view, "changed" j (changedEdges α { it.wire }).view],
    ]

fun RenderModel.json(): String = jsonOf(wire)

fun RenderModelPatch.json(): String = jsonOf(wire)

/**
 * The difference between two models, as upstream `diffRenderModels` (`packages/adapter/src/diff.ts`) computes it,
 * except that a change is any field differing. Upstream's fingerprint leaves out a node's `visible`,
 * `interactive`, `parent` and `data`, so a node hidden or regrouped there is never sent as changed.
 */
fun diffRenderModels(previous: RenderModel, next: RenderModel): RenderModelPatch {
    val beforeNodes = previous.nodes.view.associateBy { it.id }
    val afterNodes = next.nodes.view.associateBy { it.id }
    val beforeEdges = previous.edges.view.associateBy { it.id }
    val afterEdges = next.edges.view.associateBy { it.id }
    return RenderModelPatch(
        addedNodes = next.nodes.filter { it.id !in beforeNodes },
        removedNodes = previous.nodes.filter { it.id !in afterNodes } α { it.id },
        changedNodes = next.nodes.filter { beforeNodes[it.id]?.let { was -> was != it } == true },
        addedEdges = next.edges.filter { it.id !in beforeEdges },
        removedEdges = previous.edges.filter { it.id !in afterEdges } α { it.id },
        changedEdges = next.edges.filter { beforeEdges[it.id]?.let { was -> was != it } == true },
    )
}

/** Upstream `mergeRenderModelPatch`: removals, then additions, then changes, by id; survivors keep their order. */
fun mergeRenderModelPatch(model: RenderModel, patch: RenderModelPatch): RenderModel {
    val nodesById = LinkedHashMap<String, RenderNode>().also { m -> for (n in model.nodes.view) m[n.id] = n }
    for (id in patch.removedNodes.view) nodesById.remove(id)
    for (n in patch.addedNodes.view) nodesById[n.id] = n
    for (n in patch.changedNodes.view) nodesById[n.id] = n
    val edgesById = LinkedHashMap<String, RenderEdge>().also { m -> for (e in model.edges.view) m[e.id] = e }
    for (id in patch.removedEdges.view) edgesById.remove(id)
    for (e in patch.addedEdges.view) edgesById[e.id] = e
    for (e in patch.changedEdges.view) edgesById[e.id] = e
    return RenderModel(nodesById.values.toSeries(), edgesById.values.toSeries(), model.extent)
}

/**
 * This package's own graph handed to a contract renderer: the common projection's transform is the authoritative
 * position, the node's data names its host kind (falling back to the SpaceGraph node class), and groups carry over.
 */
fun GraphSpec.renderModel(): RenderModel = RenderModel(
    nodes = nodes α { n ->
        RenderNode(
            id = n.id,
            kind = n.data.string("kind", n.kind.name),
            label = n.label,
            position = n.transform.position,
            size = Vec3(n.data.width, n.data.height, n.data.depth),
            shape = if (n.kind == NodeKind.GroupNode) NodeShape.GROUP else null,
            parent = n.parent,
        )
    },
    edges = edges α { e -> RenderEdge(e.id, e.source, e.target, e.data.string("kind", e.kind.name)) },
)
