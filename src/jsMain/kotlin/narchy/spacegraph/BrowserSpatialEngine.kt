@file:OptIn(ExperimentalJsExport::class)

package narchy.spacegraph

import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.parse.json.JsonSupport
import narchy.spacegraph.graphics.spi.*

/** JS is a value adapter. Geometry, camera, traversal and picking all execute commonMain code. */
@JsExport
class BrowserSpatialEngine {
    private val view = SpatialView()
    private var cachedFrame: FramePlan? = null
    private var cachedData: dynamic = null
    private var cachedSvg: String? = null
    fun project(name: String, document: String, geometry: String, spacing: Double, reset: Boolean): dynamic {
        val program = LcncProgramConfix.fromJson(name, document)
        val scene = LcncExtrusion.project(program, LcncExtrusion.measurements(JsonSupport.parse(geometry)), spacing)
        val camera = scene.camera(view.viewport)
        view.update(scene, camera, reset)
        return value(LcncExtrusion.value(scene, camera))
    }
    fun resize(width: Int, height: Int) { view.viewport = Viewport(width.coerceAtLeast(1), height.coerceAtLeast(1)) }
    fun frame(gl: Boolean): dynamic {
        val frame = view.frame()
        if (cachedFrame !== frame) {
            cachedFrame = frame; cachedData = value(FrameJson.value(frame)); cachedSvg = null
        }
        val data = cachedData
        if (gl && data.vertices == null) {
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
    fun svg(): String {
        frame(false)
        return cachedSvg ?: SvgGraphicsProvider.encode(cachedFrame!!).a.also { cachedSvg = it }
    }
    fun camera(): dynamic = value(mapOf("position" to xyz(view.camera.position), "center" to xyz(view.camera.center),
        "zoom" to view.camera.zoom, "mode" to view.camera.mode.name, "near" to view.camera.near, "far" to view.camera.far, "fov" to view.camera.fieldOfView))
    fun pick(x: Double, y: Double): dynamic {
        val hit = view.pick(x, y) ?: return null
        val p = hit.b
        return value(mapOf("nodeId" to hit.a, "port" to p?.let { mapOf("nodeId" to it.nodeId, "name" to it.name,
            "input" to it.input, "kind" to it.kind, "position" to xyz(it.position)) }))
    }
    fun unproject(x: Double, y: Double, z: Double): dynamic = view.point(x, y, z)?.let { value(xyz(it)) }
    fun select(id: String?) { view.selected = id }
    fun fit() = view.fit()
    fun focus(id: String) = view.focus(id)
    fun pan(dx: Double, dy: Double) = view.pan(dx, dy)
    fun zoom(factor: Double, x: Double, y: Double) = view.zoom(factor, Vec3(x, y))
    private fun xyz(p: Vec3) = listOf(p.x, p.y, p.z)
    private fun value(v: Any?): dynamic = when (v) {
        is Map<*, *> -> { val result = js("({})"); v.forEach { (k, item) -> result[k.toString()] = value(item) }; result }
        is List<*> -> v.map { value(it) }.toTypedArray()
        else -> v
    }
}
