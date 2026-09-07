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
        require(initial.mode == CameraMode.ORTHOGRAPHIC) { "The LCNC surface uses a flat camera" }
        this.scene = scene
        fitted = initial
        if (reset) camera = initial
    }
    fun frame() = ExtrudedSceneProjection.frame(scene, camera, viewport, selected)
    fun fit() { camera = fitted }
    fun focus(id: String) {
        val subtree = scene.subtree(id)
        if (subtree.size == 0) return
        camera = scene.copy(nodes = subtree).camera(viewport)
    }
    fun pan(dx: Double, dy: Double) {
        val delta = Vec3(-dx / camera.zoom, dy / camera.zoom)
        camera = camera.copy(center = camera.center + delta, position = camera.position + delta)
    }
    fun zoom(factor: Double, point: Vec3 = viewport.bounds.center) {
        require(factor.isFinite() && factor > 0)
        val before = camera.unproject(point, viewport, camera.center.z)
        camera = camera.copy(zoom = (camera.zoom * factor).coerceIn(1e-6, 4e9))
        val after = camera.unproject(point, viewport, camera.center.z)
        if (before != null && after != null) {
            val delta = before - after
            camera = camera.copy(center = camera.center + delta, position = camera.position + delta)
        }
    }
    fun point(x: Double, y: Double, planeZ: Double) = camera.unproject(Vec3(x, y), viewport, planeZ)
    fun pick(x: Double, y: Double): Join<String, ExtrudedPort?>? {
        val point = Vec3(x, y); val ray = camera.ray(point, viewport)
        var level = -1; var hit: Join<String, ExtrudedPort?>? = null
        for (node in scene.nodes.view) {
            for (solid in node.solids.view) {
                solid.hit(ray) ?: continue
                if (node.level >= level) { level = node.level; hit = node.id j null }
            }
        }
        // Screen-sized port targets remain usable at deep zoom without changing world geometry.
        for (node in scene.nodes.view) for (port in node.ports.view) {
            val p = camera.project(port.position, viewport) ?: continue
            if (hypot(p.x - x, p.y - y) <= 7 && node.level >= level) {
                level = node.level; hit = node.id j port
            }
        }
        return hit
    }
}
