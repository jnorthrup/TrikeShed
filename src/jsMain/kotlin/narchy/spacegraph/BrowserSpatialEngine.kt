@file:OptIn(ExperimentalJsExport::class)

package narchy.spacegraph

import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*

/** JS is a value adapter. Geometry, camera, traversal and picking all execute commonMain code. */
@JsExport
class BrowserSpatialEngine {
    private val view = SpatialView()
    private fun point(p: dynamic): Vec3 = Vec3(p[0] as Double, p[1] as Double, p[2] as Double)
    private fun port(p: dynamic) = ExtrudedPort(p.nodeId as String, p.name as String, p.input as Boolean, point(p.position), p.kind as String?)
    fun update(packet: dynamic, reset: Boolean) {
        val nodes: Series<ExtrudedNode> = (packet.nodes.length as Int) j { i ->
            val n = packet.nodes[i]
            val rgba = n.rgba
            val color = if (rgba != null) Rgba(rgba[0] as Int, rgba[1] as Int, rgba[2] as Int, rgba[3] as Double)
                else cssColor(n.color as String)
            val params = n.params ?: js("({})"); val keys: dynamic = js("Object.keys(params)")
            ExtrudedNode(n.id as String, n.parent as String?, n.title as String, n.type as String,
                point(n.position), point(n.size), n.level as Int, n.scope as Boolean, n.measured as Boolean, color,
                (n.ports.length as Int) j { p -> port(n.ports[p]) }, (n.scale as Double?) ?: 1.0,
                (keys.length as Int) j { p -> (keys[p] as String) j (js("String(params[keys[p]])") as String) })
        }
        val cables: Series<ExtrudedCable> = (packet.cables.length as Int) j { i ->
            val c = packet.cables[i]
            ExtrudedCable(c.id as String, port(c.from), port(c.to), 4 j { p -> point(c.points[p]) })
        }
        val c = packet.camera
        view.update(ExtrudedScene(nodes, cables, emptySeriesOf()), GraphCamera(center = point(c.center), position = point(c.position),
            mode = CameraMode.PERSPECTIVE, fieldOfView = (c.fov as Double?) ?: 45.0, near = (c.near as Double?) ?: .1,
            far = (c.far as Double?) ?: 1e6, zoom = (c.zoom as Double?) ?: 1.0), reset)
    }
    fun resize(width: Int, height: Int) { view.viewport = Viewport(width.coerceAtLeast(1), height.coerceAtLeast(1)) }
    fun frame(gl: Boolean): dynamic {
        val frame = view.frame(); val data = value(FrameJson.value(frame))
        if (gl) {
            val vertices = FrameTriangles.vertices(frame)
            val count = vertices.size * 6
            val floats = js("new Float32Array(count)")
            for (i in 0 until vertices.size) {
                val (p, c) = vertices[i]; val offset = i * 6
                floats[offset] = p.x / frame.viewport.width * 2 - 1
                floats[offset + 1] = 1 - p.y / frame.viewport.height * 2
                floats[offset + 2] = c.red / 255.0; floats[offset + 3] = c.green / 255.0
                floats[offset + 4] = c.blue / 255.0; floats[offset + 5] = c.alpha
            }
            data.vertices = floats
        }
        return data
    }
    fun svg(): String = SvgGraphicsProvider.encode(view.frame()).a
    fun camera(): dynamic = value(mapOf("position" to xyz(view.camera.position), "center" to xyz(view.camera.center),
        "zoom" to view.camera.zoom, "near" to view.camera.near, "far" to view.camera.far, "fov" to view.camera.fieldOfView))
    fun pick(x: Double, y: Double): dynamic {
        val hit = view.pick(x, y) ?: return null
        val p = hit.b
        return value(mapOf("nodeId" to hit.a, "port" to p?.let { mapOf("nodeId" to it.nodeId, "name" to it.name,
            "input" to it.input, "kind" to it.kind, "position" to xyz(it.position)) }))
    }
    fun unproject(x: Double, y: Double, z: Double): dynamic = view.point(x, y, z)?.let { value(xyz(it)) }
    fun select(id: String?) { view.selected = id }
    fun fit() = view.fit()
    fun front() = view.front()
    fun focus(id: String) = view.focus(id)
    fun orbit(dx: Double, dy: Double) = view.orbit(dx, dy)
    fun pan(dx: Double, dy: Double) = view.pan(dx, dy)
    fun zoom(factor: Double, x: Double, y: Double) = view.zoom(factor, Vec3(x, y))
    private fun xyz(p: Vec3) = listOf(p.x, p.y, p.z)
    private fun cssColor(css: String): Rgba {
        val parts = css.removePrefix("rgba(").removeSuffix(")").split(',')
        return if (parts.size == 4) Rgba(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toDouble()) else Rgba.parse(css)
    }
    private fun value(v: Any?): dynamic = when (v) {
        is Map<*, *> -> { val result = js("({})"); v.forEach { (k, item) -> result[k.toString()] = value(item) }; result }
        is List<*> -> v.map { value(it) }.toTypedArray()
        else -> v
    }
}
