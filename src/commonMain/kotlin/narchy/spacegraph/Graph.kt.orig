package narchy.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.parse.json.JsonSupport

/** Full upstream node catalog, including types outside the default registration. */
enum class NodeKind {
    ShapeNode, InstancedNode, InstancedShapeNode, TexturedMeshNode, TextMeshNode,
    GlobeNode, SceneNode, HtmlNode, DOMNode, IFrameNode, ImageNode, AudioNode,
    VideoNode, CanvasNode, ChartNode, CodeEditorNode, MarkdownNode, MathNode,
    GroupNode, NoteNode, DataNode, ProcessNode, WidgetNode, ButtonNode, SliderNode,
    ToggleNode, ColorPickerNode, DropdownNode, ProgressBarNode, MeterNode,
    LayoutNode, GridNode, BorderNode, SplitNode, StackingNode, SwitchNode,
    VirtualGridNode, PanelNode, PortNode, TabContainerNode, ScrollPanelNode,
    SphereLayoutNode, MermaidNode,
}

enum class EdgeKind { Edge, CurvedEdge, FlowEdge, LabeledEdge, DottedEdge, DynamicThicknessEdge, AnimatedEdge, BundledEdge, InterGraphEdge, Wire }

/** Extension data stays a Confix document; accessors never expose platform objects. */
class NodeData(val document: ConfixDoc = confixDoc("{}")) {
    fun string(key: String, default: String = "") = document.value(key)?.toString() ?: default
    fun number(key: String, default: Double = 0.0) = (document.value(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: default
    fun flag(key: String, default: Boolean = false) = document.value(key) as? Boolean ?: default
    val width get() = number("width", number("size", 120.0)).coerceAtLeast(1.0)
    val height get() = number("height", number("size", 64.0)).coerceAtLeast(1.0)
    val depth get() = number("depth", 1.0).coerceAtLeast(0.001)
    fun with(key: String, value: Any?): NodeData {
        val copy = linkedMapOf<String, Any?>()
        (document.value() as? Map<*, *>)?.forEach { (k, v) -> if (k is String) copy[k] = v }
        copy[key] = value
        return of(copy)
    }
    companion object { fun of(values: Map<String, Any?>) = NodeData(confixDoc(JsonSupport.stringify(values))) }
}

data class NodeSpec(
    val id: String,
    val kind: NodeKind = NodeKind.ShapeNode,
    val label: String = id,
    val transform: Transform = Transform(),
    val data: NodeData = NodeData(),
    val parent: String? = null,
) { init { require(id.isNotBlank()); require(parent != id) } }

data class EdgeSpec(
    val id: String, val source: String, val target: String,
    val kind: EdgeKind = EdgeKind.Edge, val data: NodeData = NodeData(),
) { init { require(id.isNotBlank() && source.isNotBlank() && target.isNotBlank()) } }

data class GraphSpec(
    val nodes: Series<NodeSpec> = emptySeriesOf(),
    val edges: Series<EdgeSpec> = emptySeriesOf(),
    val camera: GraphCamera = GraphCamera(),
)

sealed interface GraphCommand {
    data class PutNode(val node: NodeSpec) : GraphCommand
    data class PutEdge(val edge: EdgeSpec) : GraphCommand
    data class RemoveNode(val id: String) : GraphCommand
    data class RemoveEdge(val id: String) : GraphCommand
    data class SetCamera(val camera: GraphCamera) : GraphCommand
    data class Batch(val commands: Series<GraphCommand>) : GraphCommand
    data object Clear : GraphCommand
}

data class GraphChange(val revision: Long, val command: GraphCommand)

/** Caller-owned graph state; mutations validate the whole transaction before publishing. */
class SpaceGraph(initial: GraphSpec = GraphSpec(), val historyLimit: Int = 128) {
    private var nodes = linkedMapOf<String, NodeSpec>()
    private var edges = linkedMapOf<String, EdgeSpec>()
    private val undo = mutableListOf<GraphSpec>()
    private val redo = mutableListOf<GraphSpec>()
    private val listeners = mutableListOf<(GraphChange) -> Unit>()
    var camera = initial.camera; private set
    var revision = 0L; private set
    init { require(historyLimit >= 0); install(initial) }
    val nodeCount get() = nodes.size
    val edgeCount get() = edges.size
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    fun node(id: String) = nodes[id]
    fun edge(id: String) = edges[id]
    fun snapshot() = GraphSpec(nodes.values.toList().toSeries(), edges.values.toList().toSeries(), camera)
    fun subscribe(listener: (GraphChange) -> Unit): () -> Unit { listeners.add(listener); return { listeners.remove(listener) } }
    fun execute(command: GraphCommand, recordHistory: Boolean = true) {
        val before = snapshot()
        val nextNodes = LinkedHashMap(nodes); val nextEdges = LinkedHashMap(edges)
        var nextCamera = camera
        fun apply(cmd: GraphCommand) {
            when (cmd) {
                is GraphCommand.PutNode -> nextNodes[cmd.node.id] = cmd.node
                is GraphCommand.PutEdge -> nextEdges[cmd.edge.id] = cmd.edge
                is GraphCommand.RemoveNode -> {
                    val removed = mutableSetOf(cmd.id)
                    var changed = true
                    while (changed) {
                        changed = false
                        for (n in nextNodes.values) if (n.parent in removed && removed.add(n.id)) changed = true
                    }
                    removed.forEach(nextNodes::remove)
                    nextEdges.entries.removeAll { it.value.source in removed || it.value.target in removed }
                }
                is GraphCommand.RemoveEdge -> nextEdges.remove(cmd.id)
                is GraphCommand.SetCamera -> nextCamera = cmd.camera
                is GraphCommand.Batch -> cmd.commands.view.forEach(::apply)
                GraphCommand.Clear -> { nextNodes.clear(); nextEdges.clear() }
            }
        }
        apply(command)
        validate(nextNodes, nextEdges)
        if (recordHistory && historyLimit > 0) {
            undo.add(before); if (undo.size > historyLimit) undo.removeAt(0); redo.clear()
        }
        nodes = nextNodes; edges = nextEdges; camera = nextCamera
        publish(command)
    }
    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        redo.add(snapshot()); install(undo.removeAt(undo.lastIndex)); publish(GraphCommand.Clear)
        return true
    }
    fun redo(): Boolean {
        if (redo.isEmpty()) return false
        undo.add(snapshot()); install(redo.removeAt(redo.lastIndex)); publish(GraphCommand.Clear)
        return true
    }
    fun worldMatrix(id: String): Matrix4 {
        val node = requireNotNull(nodes[id]) { "Unknown node: $id" }
        return node.parent?.let { worldMatrix(it) * node.transform.matrix } ?: node.transform.matrix
    }
    fun worldPosition(id: String) = worldMatrix(id).transform(Vec3.ZERO)
    fun children(parent: String?): Series<NodeSpec> = nodes.values.filter { it.parent == parent }.toSeries()
    fun incident(id: String): Series<EdgeSpec> = edges.values.filter { it.source == id || it.target == id }.toSeries()
    fun bounds(id: String): Bounds3 {
        val node = requireNotNull(nodes[id]); val m = worldMatrix(id)
        val half = Vec3(node.data.width / 2, node.data.height / 2, node.data.depth / 2)
        val corners = (0..7).map { i -> m.transform(Vec3(if (i and 1 == 0) -half.x else half.x,
            if (i and 2 == 0) -half.y else half.y, if (i and 4 == 0) -half.z else half.z)) }
        return Bounds3(Vec3(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
            Vec3(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
    }
    fun hitTest(point: Vec3, viewport: Viewport): NodeSpec? {
        val ray = camera.ray(point, viewport)
        return nodes.values.asSequence().filter { isVisible(it.id) && it.data.flag("selectable", true) }
            .mapNotNull { n ->
                val inverse = worldMatrix(n.id).inverse()
                val localRay = Ray(inverse.transform(ray.origin), inverse.transform(ray.direction, true))
                val h = Vec3(n.data.width / 2, n.data.height / 2, n.data.depth / 2)
                Bounds3(h * -1.0, h).hit(localRay)?.let { distance -> n to distance }
            }.minByOrNull { it.second }?.first
    }
    fun isVisible(id: String): Boolean {
        val n = nodes[id] ?: return false
        return n.data.flag("visible", true) && (n.parent?.let(::isVisible) ?: true)
    }
    fun fit(viewport: Viewport, padding: Double = 48.0): GraphCamera {
        val visible = nodes.values.filter { isVisible(it.id) }
        if (visible.isEmpty()) return camera
        val boxes = visible.map { bounds(it.id) }
        val left = boxes.minOf { it.min.x }; val right = boxes.maxOf { it.max.x }
        val bottom = boxes.minOf { it.min.y }; val top = boxes.maxOf { it.max.y }
        val zoom = minOf((viewport.width - 2 * padding).coerceAtLeast(1.0) / (right - left).coerceAtLeast(1.0),
            (viewport.height - 2 * padding).coerceAtLeast(1.0) / (top - bottom).coerceAtLeast(1.0))
        return camera.copy(center = Vec3((left + right) / 2, (bottom + top) / 2), zoom = zoom)
    }
    private fun install(spec: GraphSpec) {
        val nn = linkedMapOf<String, NodeSpec>(); val ee = linkedMapOf<String, EdgeSpec>()
        spec.nodes.view.forEach { require(nn.put(it.id, it) == null) { "Duplicate node: ${it.id}" } }
        spec.edges.view.forEach { require(ee.put(it.id, it) == null) { "Duplicate edge: ${it.id}" } }
        validate(nn, ee); nodes = nn; edges = ee; camera = spec.camera
    }
    private fun publish(command: GraphCommand) { revision++; listeners.toList().forEach { it(GraphChange(revision, command)) } }
    private fun validate(nn: Map<String, NodeSpec>, ee: Map<String, EdgeSpec>) {
        for (n in nn.values) {
            val visited = mutableSetOf(n.id); var parent = n.parent
            while (parent != null) {
                require(visited.add(parent)) { "Parent cycle at ${n.id}" }
                parent = requireNotNull(nn[parent]) { "Missing parent: $parent" }.parent
            }
        }
        for (e in ee.values) require(e.source in nn && e.target in nn) { "Dangling edge: ${e.id}" }
    }
}

class GraphBuilder {
    private val nodes = mutableListOf<NodeSpec>(); private val edges = mutableListOf<EdgeSpec>()
    var camera = GraphCamera()
    fun node(id: String, kind: NodeKind = NodeKind.ShapeNode, label: String = id, position: Vec3 = Vec3.ZERO,
             data: NodeData = NodeData(), parent: String? = null) { nodes.add(NodeSpec(id, kind, label, Transform(position), data, parent)) }
    fun edge(id: String, source: String, target: String, kind: EdgeKind = EdgeKind.Edge, data: NodeData = NodeData()) {
        edges.add(EdgeSpec(id, source, target, kind, data))
    }
    fun build() = GraphSpec(nodes.toList().toSeries(), edges.toList().toSeries(), camera)
}
fun graph(build: GraphBuilder.() -> Unit) = GraphBuilder().apply(build).build()
