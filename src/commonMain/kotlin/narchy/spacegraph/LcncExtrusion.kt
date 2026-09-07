package narchy.spacegraph

import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/** Browser measurements are presentation values in LCNC world units, not replacement document state. */
data class MeasuredPort(val name: String, val input: Boolean, val position: Vec3)
data class MeasuredNode(val id: String, val bounds: Rect, val ports: Series<MeasuredPort>, val visible: Boolean = true)
data class ExtrudedPort(val nodeId: String, val name: String, val input: Boolean, val position: Vec3, val kind: String?)
data class ExtrudedNode(
    val id: String, val parent: String?, val title: String, val type: String,
    val position: Vec3, val size: Vec3, val level: Int, val scope: Boolean,
    val measured: Boolean, val color: Rgba, val ports: Series<ExtrudedPort>,
) {
    val bounds get() = Bounds3(position - size * .5, position + size * .5)
    val corners: Series<Vec3> get() = 8 j { i -> position + Vec3(
        if (i and 1 == 0) -size.x / 2 else size.x / 2,
        if (i and 2 == 0) -size.y / 2 else size.y / 2,
        if (i and 4 == 0) -size.z / 2 else size.z / 2,
    ) }
}
data class ExtrudedCable(val id: String, val from: ExtrudedPort, val to: ExtrudedPort, val points: Series<Vec3>)
data class ExtrudedScene(val nodes: Series<ExtrudedNode>, val cables: Series<ExtrudedCable>, val issues: Series<String>) {
    val bounds: Bounds3 get() {
        if (nodes.size == 0) return Bounds3(Vec3(-200.0, -150.0), Vec3(200.0, 150.0, 40.0))
        val boxes = nodes.view.map { it.bounds }
        return Bounds3(Vec3(boxes.minOf { it.min.x }, boxes.minOf { it.min.y }, boxes.minOf { it.min.z }),
            Vec3(boxes.maxOf { it.max.x }, boxes.maxOf { it.max.y }, boxes.maxOf { it.max.z }))
    }
    fun camera(viewport: Viewport): GraphCamera {
        val radius = max(bounds.size.length / 2, 100.0)
        val limitingAngle = atan(tan(PI / 8) * min(1.0, viewport.width.toDouble() / viewport.height))
        val distance = radius / sin(limitingAngle) * 1.12
        return GraphCamera(center = bounds.center, mode = CameraMode.PERSPECTIVE,
            position = bounds.center + Vec3(.22, -.38, 1.0).normalized() * distance,
            fieldOfView = 45.0, far = max(10000.0, distance * 8))
    }
}

object LcncExtrusion {
    fun measurements(value: Any?): Series<MeasuredNode> {
        if (value == null) return emptySeriesOf()
        val rows = value as? List<*> ?: throw IllegalArgumentException("geometry must be an array")
        require(rows.size <= 4096) { "geometry exceeds 4096 nodes" }
        val ids = mutableSetOf<String>()
        fun number(row: Map<*, *>, key: String): Double {
            val n = (row[key] as? Number)?.toDouble() ?: throw IllegalArgumentException("geometry.$key must be numeric")
            require(n.isFinite() && abs(n) <= 1e8) { "geometry.$key is outside the finite coordinate range" }
            return n
        }
        return rows.map { item ->
            val row = item as? Map<*, *> ?: throw IllegalArgumentException("geometry node must be an object")
            val id = row["id"] as? String ?: throw IllegalArgumentException("geometry node requires id")
            require(ids.add(id)) { "duplicate geometry node $id" }
            val ports = row["ports"] as? List<*> ?: emptyList<Any>()
            require(ports.size <= 256) { "too many measured ports on $id" }
            val portIds = mutableSetOf<String>()
            MeasuredNode(id, Rect(number(row, "x"), number(row, "y"), number(row, "width"), number(row, "height")),
                ports.map { entry ->
                    val port = entry as? Map<*, *> ?: throw IllegalArgumentException("port must be an object")
                    val name = port["name"] as? String ?: throw IllegalArgumentException("port requires name")
                    val input = port["input"] as? Boolean ?: throw IllegalArgumentException("port requires direction")
                    require(portIds.add("$input:$name")) { "duplicate measured port $id:$name" }
                    MeasuredPort(name, input, Vec3(number(port, "x"), number(port, "y")))
                }.toSeries(), row["visible"] != false)
        }.toSeries()
    }

    fun project(program: LcncProgram, measurements: Series<MeasuredNode> = emptySeriesOf(), spacing: Double = 110.0): ExtrudedScene {
        require(spacing.isFinite() && spacing in 0.0..1000.0)
        val shadow = LcncSpaceGraph.project(program)
        val graph = SpaceGraph(shadow.graph, historyLimit = 0)
        val measured = measurements.view.associateBy { it.id }
        val specs = shadow.graph.nodes.view.associateBy { it.id }
        require(measured.keys.all { it in specs }) { "geometry names a node outside this document" }
        val issues = mutableListOf<String>()
        val nodes = mutableListOf<ExtrudedNode>()
        fun hidden(id: String): Boolean {
            val node = specs.getValue(id)
            return measured[id]?.visible == false || (node.parent?.let { hidden(it) || specs.getValue(it).data.flag("collapsed") } ?: false)
        }
        for (spec in shadow.graph.nodes.view) {
            if (hidden(spec.id)) continue
            var level = 0; var ancestor = spec.parent
            while (ancestor != null) { level++; ancestor = specs.getValue(ancestor).parent }
            val m = measured[spec.id]
            val fallback = graph.worldPosition(spec.id)
            val r = m?.bounds ?: Rect(fallback.x - spec.data.width / 2, -fallback.y - spec.data.height / 2, spec.data.width, spec.data.height)
            if (r.width == 0.0 || r.height == 0.0) continue
            val scope = spec.kind == NodeKind.GroupNode || spec.data.string("lcncType") == "scope"
            val depth = if (scope) 12.0 else 28.0
            val z = level * spacing
            val center = Vec3(r.center.x, -r.center.y, z + depth / 2)
            val contracts = shadow.ports.view.toList().filter { it.nodeId == spec.id }
            val ports = if (m != null) m.ports.view.map { p ->
                val contract = contracts.find { it.name == p.name.removeSuffix("?") && it.input == p.input }
                ExtrudedPort(spec.id, p.name, p.input, Vec3(p.position.x, -p.position.y, z + depth + 3), contract?.kind)
            } else contracts.mapIndexed { i, p -> ExtrudedPort(spec.id, p.name, p.input,
                Vec3(if (p.input) r.x else r.right, -r.y - 36 - i * 18.0, z + depth + 3), p.kind) }
            val color = when { scope -> Rgba(71, 119, 111); spec.data.flag("effect") -> Rgba(176, 70, 83)
                spec.data.flag("source") -> Rgba(47, 116, 166); spec.data.flag("sink") -> Rgba(121, 98, 155)
                else -> Rgba(82, 103, 117) }
            nodes.add(ExtrudedNode(spec.id, spec.parent, spec.label, spec.data.string("lcncType"), center,
                Vec3(r.width, r.height, depth), level, scope, m != null, color, ports.toSeries()))
        }
        val byId = nodes.associateBy { it.id }
        val cables = mutableListOf<ExtrudedCable>()
        program.wires.view.forEachIndexed { index, wire ->
            val from = byId[wire.fromNode]?.ports?.view?.find { !it.input && it.name == wire.fromPort }
            val to = byId[wire.toNode]?.ports?.view?.find { it.input && it.name.removeSuffix("?") == wire.toPort.removeSuffix("?") }
            if (from == null || to == null) {
                issues.add("Unprojected cable ${wire.fromNode}#${wire.fromPort} -> ${wire.toNode}#${wire.toPort}")
            } else {
                val bend = max(50.0, abs(to.position.x - from.position.x) * .45)
                cables.add(ExtrudedCable("lcnc-wire-$index", from, to,
                    s_[from.position, from.position + Vec3(bend), to.position - Vec3(bend), to.position]))
            }
        }
        return ExtrudedScene(nodes.toSeries(), cables.toSeries(), issues.toSeries())
    }

    fun camera(value: Any?, fallback: GraphCamera): GraphCamera {
        if (value == null) return fallback
        val row = value as? Map<*, *> ?: throw IllegalArgumentException("camera must be an object")
        fun vector(key: String): Vec3 {
            val p = row[key] as? List<*> ?: throw IllegalArgumentException("camera.$key must be a vector")
            require(p.size == 3)
            val v = p.map { (it as? Number)?.toDouble() ?: throw IllegalArgumentException("camera vector must be numeric") }
            require(v.all { it.isFinite() && abs(it) <= 1e9 })
            return Vec3(v[0], v[1], v[2])
        }
        val position = vector("position"); val center = vector("center")
        require((position - center).length > .1) { "camera cannot occupy its target" }
        val zoom = (row["zoom"] as? Number)?.toDouble() ?: 1.0
        require(zoom.isFinite() && zoom in .01..100.0)
        return fallback.copy(position = position, center = center, zoom = zoom,
            far = max(fallback.far, (position - center).length * 8))
    }

    fun value(scene: ExtrudedScene, camera: GraphCamera): Map<String, Any?> {
        fun point(p: Vec3) = listOf(p.x, p.y, p.z)
        fun port(p: ExtrudedPort) = mapOf("nodeId" to p.nodeId, "name" to p.name, "input" to p.input, "kind" to p.kind, "position" to point(p.position))
        return mapOf("coordinateSystem" to "right-handed-y-up-z-extrusion", "nodes" to scene.nodes.view.map { n ->
            mapOf("id" to n.id, "parent" to n.parent, "title" to n.title, "type" to n.type, "position" to point(n.position),
                "size" to point(n.size), "level" to n.level, "scope" to n.scope, "measured" to n.measured,
                "color" to n.color.css, "ports" to n.ports.view.map(::port))
        }, "cables" to scene.cables.view.map { c -> mapOf("id" to c.id, "from" to port(c.from), "to" to port(c.to), "points" to c.points.view.map(::point)) },
            "camera" to mapOf("position" to point(camera.position), "center" to point(camera.center), "zoom" to camera.zoom,
                "fov" to camera.fieldOfView, "near" to camera.near, "far" to camera.far), "issues" to scene.issues.view.toList())
    }

    fun frame(scene: ExtrudedScene, camera: GraphCamera, viewport: Viewport): FramePlan =
        ExtrudedSceneProjection.frame(scene, camera, viewport)
}
