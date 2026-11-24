package narchy.spacegraph

import borg.trikeshed.landscape.LandscapeNavigation
import kotlin.math.*

/** An angle in degrees: the camera keeps its angles in degrees, so the URL form carries them exactly. */
typealias Degree = Double

/** A disc on the ground: center ([x], [y]) and radius [r], world units. */
data class Disc(val x: Double, val y: Double, val r: Double) {
    fun contains(px: Double, py: Double): Boolean = hypot(px - x, py - y) <= r
    /** True when [d] lies wholly inside this disc. */
    fun contains(d: Disc): Boolean = hypot(d.x - x, d.y - y) + d.r <= r * (1 + 1e-12)
}

/**
 * A perspective eye over the ground plane z = 0 (x east, y north, z up), the 2.5D view: things stand up from the
 * plane. ([x], [y]) is the ground point under the middle of the view and the eye always looks at it, so a turn
 * ([yaw], counterclockwise seen from above, about the vertical through that point) and a tilt ([pitch], from straight
 * down at 0 toward the horizon at [PITCH_MAX]) keep it in the middle. [zoom] is the pixels per world unit there.
 * A pointer maps to the ground point under it ([ground]) at every pitch and yaw, and [drag] and [zoomAt] move the
 * camera so that the ground point in hand stays under the pointer.
 */
data class GroundCamera(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
    val pitch: Degree = 0.0,
    val yaw: Degree = 0.0,
) {
    init {
        require(x.isFinite() && y.isFinite() && zoom.isFinite() && zoom > 0 && pitch in 0.0..PITCH_MAX && yaw.isFinite()) {
            "a ground camera needs a finite point, a positive zoom and a pitch in 0..$PITCH_MAX"
        }
    }

    /** Screen right, as a ground direction. */
    val right: Vec3 = Vec3(cos(yaw * PI / 180), sin(yaw * PI / 180))
    /** Screen up while looking straight down, as a ground direction. */
    val heading: Vec3 = Vec3(-sin(yaw * PI / 180), cos(yaw * PI / 180))
    /** From the eye toward ([x], [y]). */
    val forward: Vec3 = heading * sin(pitch * PI / 180) + Vec3(z = -cos(pitch * PI / 180))
    /** Screen up. */
    val up: Vec3 = heading * cos(pitch * PI / 180) + Vec3(z = sin(pitch * PI / 180))

    /** Pixels per world unit at unit distance on [viewport]: the lens. */
    fun focal(viewport: Viewport): Double = viewport.height / (2 * tan(FIELD * PI / 360))

    /** Distance from the eye to ([x], [y]). */
    fun distance(viewport: Viewport): Double = focal(viewport) / zoom

    fun eye(viewport: Viewport): Vec3 = Vec3(x, y) - forward * distance(viewport)

    /** Screen point (pixels, y down) of world point [p], with its depth along [forward] as z; null nearer than [NEAR] of the distance. */
    fun project(p: Vec3, viewport: Viewport): Vec3? {
        val f = focal(viewport); val d = p - eye(viewport); val depth = d.dot(forward)
        if (depth < NEAR * f / zoom) return null
        val s = f / depth
        return Vec3(viewport.width / 2.0 + d.dot(right) * s, viewport.height / 2.0 - d.dot(up) * s, depth)
    }

    /** The ground point under screen point ([sx], [sy]); null where the ray through it misses the ground (above the horizon). */
    fun ground(sx: Double, sy: Double, viewport: Viewport): Vec3? {
        val f = focal(viewport)
        val ray = forward + right * ((sx - viewport.width / 2.0) / f) + up * ((viewport.height / 2.0 - sy) / f)
        if (ray.z > -1e-9) return null
        val e = eye(viewport); val t = -e.z / ray.z
        return Vec3(e.x + ray.x * t, e.y + ray.y * t, 0.0)
    }

    /** The camera that keeps the ground point under ([fromX], [fromY]) under ([toX], [toY]): a pointer drag. */
    fun drag(fromX: Double, fromY: Double, toX: Double, toY: Double, viewport: Viewport): GroundCamera {
        val a = ground(fromX, fromY, viewport) ?: return this
        val b = ground(toX, toY, viewport) ?: return this
        return copy(x = x + a.x - b.x, y = y + a.y - b.y)
    }

    /** Zooms by [factor] within [ZOOM_MIN]..[ZOOM_MAX], holding the ground point under ([sx], [sy]). */
    fun zoomAt(factor: Double, sx: Double, sy: Double, viewport: Viewport): GroundCamera {
        require(factor.isFinite() && factor > 0)
        val next = copy(zoom = (zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX))
        val a = ground(sx, sy, viewport) ?: return next
        val b = next.ground(sx, sy, viewport) ?: return next
        return next.copy(x = next.x + a.x - b.x, y = next.y + a.y - b.y)
    }

    /** Turns about the vertical through ([x], [y]) by [degrees], counterclockwise seen from above; yaw stays in 0..360. */
    fun turn(degrees: Degree): GroundCamera = copy(yaw = ((yaw + degrees) % 360 + 360) % 360)

    /** Tilts toward the horizon by [degrees] (away from it when negative), within 0..[PITCH_MAX]. */
    fun tilt(degrees: Degree): GroundCamera = copy(pitch = (pitch + degrees).coerceIn(0.0, PITCH_MAX))

    /** The same angles over the disc at ([cx], [cy]) of radius [r], the disc filling [FILL] of the view's shorter side. */
    fun frame(cx: Double, cy: Double, r: Double, viewport: Viewport): GroundCamera =
        copy(x = cx, y = cy, zoom = (min(viewport.width, viewport.height) * FILL / (2 * max(r, 1e-9))).coerceIn(ZOOM_MIN, ZOOM_MAX))

    companion object {
        /** Vertical field of view, degrees. */
        const val FIELD = 45.0
        const val PITCH_MAX = 75.0
        const val NEAR = .02
        const val ZOOM_MIN = 1e-4
        const val ZOOM_MAX = 1e6
        const val FILL = .8
    }
}

/**
 * A view a URL carries: the camera, its angles included, and the hyperarchy path in hand (root first), as the hash
 * `#x=…&y=…&z=…&pitch=…&yaw=…&path=A/B/C`. The form escaping is [LandscapeNavigation]'s, so the hash reads as
 * URLSearchParams does.
 */
data class GroundView(val camera: GroundCamera, val path: List<String> = emptyList()) {
    fun encode(): String {
        val pairs = mutableListOf("x" to camera.x.toString(), "y" to camera.y.toString(), "z" to camera.zoom.toString(),
            "pitch" to camera.pitch.toString(), "yaw" to camera.yaw.toString())
        if (path.isNotEmpty()) pairs += "path" to path.joinToString("/")
        return "#" + LandscapeNavigation.formEncode(pairs)
    }

    companion object {
        /** The view in [hash]; null unless x, y and z are present and make a camera (pitch and yaw default to 0). */
        fun decode(hash: String): GroundView? {
            val p = LandscapeNavigation.formDecode(hash.removePrefix("#"))
            val x = p["x"]?.toDoubleOrNull() ?: return null
            val y = p["y"]?.toDoubleOrNull() ?: return null
            val z = p["z"]?.toDoubleOrNull() ?: return null
            val pitch = p["pitch"]?.let { it.toDoubleOrNull() ?: return null } ?: 0.0
            val yaw = p["yaw"]?.let { it.toDoubleOrNull() ?: return null } ?: 0.0
            if (!(x.isFinite() && y.isFinite() && z.isFinite() && z > 0 && pitch in 0.0..GroundCamera.PITCH_MAX && yaw.isFinite())) return null
            val path = p["path"]?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()
            return GroundView(GroundCamera(x, y, z, pitch, yaw), path)
        }
    }
}
