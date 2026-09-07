package narchy.spacegraph

import kotlin.math.*

data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
    operator fun plus(v: Vec3) = Vec3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vec3) = Vec3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(v: Vec3) = x * v.x + y * v.y + z * v.z
    fun cross(v: Vec3) = Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)
    val length get() = sqrt(dot(this))
    fun normalized(): Vec3 = if (length < 1e-12) ZERO else this * (1.0 / length)
    fun lerp(to: Vec3, t: Double) = this + (to - this) * t
    companion object { val ZERO = Vec3(); val ONE = Vec3(1.0, 1.0, 1.0) }
}

data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    init { require(listOf(x, y, width, height).all { it.isFinite() } && width >= 0 && height >= 0) }
    val right get() = x + width
    val bottom get() = y + height
    val center get() = Vec3(x + width / 2, y + height / 2)
    fun contains(p: Vec3) = p.x >= x && p.x <= right && p.y >= y && p.y <= bottom
    fun intersects(r: Rect) = x <= r.right && right >= r.x && y <= r.bottom && bottom >= r.y
    fun intersection(r: Rect): Rect? {
        val l = max(x, r.x); val t = max(y, r.y)
        val w = min(right, r.right) - l; val h = min(bottom, r.bottom) - t
        return if (w < 0 || h < 0) null else Rect(l, t, w, h)
    }
    fun expanded(padding: Double) = Rect(x - padding, y - padding, width + padding * 2, height + padding * 2)
}

data class Ray(val origin: Vec3, val direction: Vec3)
data class Bounds3(val min: Vec3, val max: Vec3) {
    init { require(min.x <= max.x && min.y <= max.y && min.z <= max.z) }
    val center get() = (min + max) * 0.5
    val size get() = max - min
    fun hit(ray: Ray): Double? {
        var near = 0.0; var far = Double.POSITIVE_INFINITY
        val o = doubleArrayOf(ray.origin.x, ray.origin.y, ray.origin.z)
        val d = doubleArrayOf(ray.direction.x, ray.direction.y, ray.direction.z)
        val lo = doubleArrayOf(min.x, min.y, min.z); val hi = doubleArrayOf(max.x, max.y, max.z)
        for (axis in 0..2) {
            if (abs(d[axis]) < 1e-12) { if (o[axis] < lo[axis] || o[axis] > hi[axis]) return null }
            else {
                val a = (lo[axis] - o[axis]) / d[axis]; val b = (hi[axis] - o[axis]) / d[axis]
                near = maxOf(near, minOf(a, b)); far = minOf(far, maxOf(a, b))
                if (near > far) return null
            }
        }
        return near
    }
}

/** Column-major affine and projective transforms. */
class Matrix4 private constructor(private val values: DoubleArray) {
    operator fun get(row: Int, col: Int): Double = values[col * 4 + row]
    operator fun times(other: Matrix4) = Matrix4(DoubleArray(16) { i ->
        val row = i % 4; val col = i / 4
        (0..3).sumOf { k -> this[row, k] * other[k, col] }
    })
    fun transform(p: Vec3, direction: Boolean = false): Vec3 {
        val w0 = if (direction) 0.0 else 1.0
        val w = this[3, 0] * p.x + this[3, 1] * p.y + this[3, 2] * p.z + this[3, 3] * w0
        require(direction || abs(w) > 1e-12) { "Point lies on the projection plane" }
        val divisor = if (direction) 1.0 else w
        return Vec3(
            (this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z + this[0, 3] * w0) / divisor,
            (this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z + this[1, 3] * w0) / divisor,
            (this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z + this[2, 3] * w0) / divisor,
        )
    }
    fun inverse(): Matrix4 {
        val rows = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) this[r, c] else if (c - 4 == r) 1.0 else 0.0 } }
        for (c in 0..3) {
            val pivot = (c..3).maxBy { abs(rows[it][c]) }
            require(abs(rows[pivot][c]) > 1e-12) { "Singular transform" }
            val swap = rows[c]; rows[c] = rows[pivot]; rows[pivot] = swap
            val divisor = rows[c][c]
            for (j in 0..7) rows[c][j] /= divisor
            for (r in 0..3) if (r != c) {
                val f = rows[r][c]
                for (j in 0..7) rows[r][j] -= f * rows[c][j]
            }
        }
        return Matrix4(DoubleArray(16) { i -> rows[i % 4][i / 4 + 4] })
    }
    fun toArray() = values.copyOf()
    companion object {
        val IDENTITY = Matrix4(DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 })
        fun translation(v: Vec3) = Matrix4(IDENTITY.values.copyOf().apply { this[12] = v.x; this[13] = v.y; this[14] = v.z })
        fun scale(v: Vec3) = Matrix4(doubleArrayOf(v.x, 0.0, 0.0, 0.0, 0.0, v.y, 0.0, 0.0, 0.0, 0.0, v.z, 0.0, 0.0, 0.0, 0.0, 1.0))
        fun rotation(v: Vec3): Matrix4 {
            val a = cos(v.x); val b = sin(v.x); val c = cos(v.y); val d = sin(v.y); val e = cos(v.z); val f = sin(v.z)
            return Matrix4(doubleArrayOf(c * e, a * f + b * e * d, b * f - a * e * d, 0.0,
                -c * f, a * e - b * f * d, b * e + a * f * d, 0.0,
                d, -b * c, a * c, 0.0, 0.0, 0.0, 0.0, 1.0))
        }
    }
}

data class Transform(val position: Vec3 = Vec3.ZERO, val rotation: Vec3 = Vec3.ZERO, val scale: Vec3 = Vec3.ONE) {
    init { require(abs(scale.x) > 1e-12 && abs(scale.y) > 1e-12 && abs(scale.z) > 1e-12) }
    val matrix get() = Matrix4.translation(position) * Matrix4.rotation(rotation) * Matrix4.scale(scale)
}

enum class CameraMode { ORTHOGRAPHIC, PERSPECTIVE }
data class Viewport(val width: Int, val height: Int, val pixelRatio: Double = 1.0) {
    init { require(width > 0 && height > 0 && pixelRatio.isFinite() && pixelRatio > 0) }
    val bounds get() = Rect(0.0, 0.0, width.toDouble(), height.toDouble())
}

data class GraphCamera(
    val center: Vec3 = Vec3.ZERO,
    val zoom: Double = 1.0,
    val mode: CameraMode = CameraMode.ORTHOGRAPHIC,
    val position: Vec3 = Vec3(0.0, 0.0, 500.0),
    val fieldOfView: Double = 75.0,
    val near: Double = 0.1,
    val far: Double = 10000.0,
) {
    init { require(zoom.isFinite() && zoom > 0 && fieldOfView > 0 && fieldOfView < 180 && near > 0 && far > near) }
    private val forward get() = (center - position).normalized()
    private val right get() = forward.cross(Vec3(0.0, 1.0)).let { if (it.length < 1e-10) Vec3(1.0) else it.normalized() }
    private val up get() = right.cross(forward).normalized()
    fun project(point: Vec3, viewport: Viewport): Vec3? {
        if (mode == CameraMode.ORTHOGRAPHIC) return Vec3(viewport.width / 2.0 + (point.x - center.x) * zoom,
            viewport.height / 2.0 - (point.y - center.y) * zoom, point.z)
        val delta = point - position; val depth = delta.dot(forward)
        if (depth < near || depth > far) return null
        val scale = viewport.height / (2 * tan(fieldOfView * PI / 360)) * zoom / depth
        return Vec3(viewport.width / 2.0 + delta.dot(right) * scale, viewport.height / 2.0 - delta.dot(up) * scale, depth)
    }
    fun ray(point: Vec3, viewport: Viewport): Ray {
        if (mode == CameraMode.ORTHOGRAPHIC) return Ray(Vec3(center.x + (point.x - viewport.width / 2.0) / zoom,
            center.y - (point.y - viewport.height / 2.0) / zoom, far), Vec3(0.0, 0.0, -1.0))
        val unit = 2 * tan(fieldOfView * PI / 360) / (viewport.height * zoom)
        return Ray(position, (forward + right * ((point.x - viewport.width / 2.0) * unit) + up * ((viewport.height / 2.0 - point.y) * unit)).normalized())
    }
    fun unproject(point: Vec3, viewport: Viewport, planeZ: Double = 0.0): Vec3? {
        val ray = ray(point, viewport)
        if (mode == CameraMode.ORTHOGRAPHIC) return Vec3(ray.origin.x, ray.origin.y, planeZ)
        if (abs(ray.direction.z) < 1e-12) return null
        val distance = (planeZ - ray.origin.z) / ray.direction.z
        return if (distance < 0) null else ray.origin + ray.direction * distance
    }
    fun zoomAt(factor: Double, point: Vec3, viewport: Viewport): GraphCamera {
        require(factor.isFinite() && factor > 0)
        val before = unproject(point, viewport) ?: return this
        val next = copy(zoom = (zoom * factor).coerceIn(1e-5, 1e5))
        val after = next.unproject(point, viewport) ?: return next
        val delta = before - after
        return next.copy(center = center + delta, position = position + delta)
    }
}
