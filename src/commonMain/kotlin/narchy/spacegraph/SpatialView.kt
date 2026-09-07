package narchy.spacegraph

import borg.trikeshed.lib.*
import kotlin.math.*

/** Caller-owned presentation state. No document mutations, effects, or platform objects. */
class SpatialView(var viewport: Viewport = Viewport(1, 1)) {
    var scene = ExtrudedScene(emptySeriesOf(), emptySeriesOf(), emptySeriesOf())
        private set
    var camera = scene.camera(viewport)
        private set
    var selected: String? = null
    private var fitted = camera
    fun update(scene: ExtrudedScene, initial: GraphCamera, reset: Boolean) {
        this.scene = scene
        fitted = initial
        if (reset) camera = initial
    }
    fun frame() = ExtrudedSceneProjection.frame(scene, camera, viewport, selected)
    fun fit() { camera = fitted }
    fun front() { camera = camera.copy(position = camera.center + Vec3(z = (camera.position - camera.center).length)) }
    fun focus(id: String) {
        val n = scene.nodes.view.find { it.id == id } ?: return
        val angle = atan(tan(camera.fieldOfView * PI / 360) * min(1.0, viewport.width.toDouble() / viewport.height))
        val distance = max(n.size.length / 2 / sin(angle) * 1.15, 1e-9)
        camera = camera.copy(center = n.position, position = n.position + (camera.position - camera.center).normalized() * distance,
            zoom = 1.0, near = max(distance * 1e-6, 1e-12), far = max(scene.bounds.size.length * 8, distance * 8))
    }
    fun orbit(dx: Double, dy: Double) {
        val offset = camera.position - camera.center; val r = offset.length
        val yaw = atan2(offset.x, offset.z) - dx * 2 * PI / viewport.height
        val pitch = (asin((offset.y / r).coerceIn(-1.0, 1.0)) + dy * PI / viewport.height).coerceIn(-PI / 2 + .01, PI / 2 - .01)
        camera = camera.copy(position = camera.center + Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch)) * r)
    }
    fun pan(dx: Double, dy: Double) {
        val forward = (camera.center - camera.position).normalized()
        val right = forward.cross(Vec3(0.0, 1.0)).normalized(); val up = right.cross(forward)
        val unit = 2 * (camera.position - camera.center).length * tan(camera.fieldOfView * PI / 360) / (viewport.height * camera.zoom)
        val delta = (right * -dx + up * dy) * unit
        camera = camera.copy(center = camera.center + delta, position = camera.position + delta)
    }
    fun zoom(factor: Double, point: Vec3 = viewport.bounds.center) {
        require(factor.isFinite() && factor > 0)
        val before = camera.unproject(point, viewport, camera.center.z)
        val offset = camera.position - camera.center
        val distance = (offset.length / factor).coerceIn(1e-9, 1e12)
        camera = camera.copy(position = camera.center + offset.normalized() * distance,
            near = max(distance * 1e-6, 1e-12), far = max(scene.bounds.size.length * 8, distance * 8))
        val after = camera.unproject(point, viewport, camera.center.z)
        if (before != null && after != null) {
            val delta = before - after
            camera = camera.copy(center = camera.center + delta, position = camera.position + delta)
        }
    }
    fun point(x: Double, y: Double, planeZ: Double) = camera.unproject(Vec3(x, y), viewport, planeZ)
    fun pick(x: Double, y: Double): Join<String, ExtrudedPort?>? {
        val point = Vec3(x, y); val ray = camera.ray(point, viewport)
        var nearest = Double.POSITIVE_INFINITY; var hit: Join<String, ExtrudedPort?>? = null
        for (node in scene.nodes.view) {
            for (solid in node.solids.view) {
                val d = solid.hit(ray) ?: continue
                if (d < nearest) { nearest = d; hit = node.id j null }
            }
        }
        // Screen-sized port targets remain usable at deep zoom without changing world geometry.
        for (node in scene.nodes.view) for (port in node.ports.view) {
            val p = camera.project(port.position, viewport) ?: continue
            val d = (port.position - ray.origin).length
            if (hypot(p.x - x, p.y - y) <= 7 && d <= nearest + node.size.z + 7 * node.scale) {
                nearest = d; hit = node.id j port
            }
        }
        return hit
    }
}
