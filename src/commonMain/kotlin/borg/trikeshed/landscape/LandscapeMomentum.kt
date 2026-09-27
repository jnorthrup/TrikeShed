package borg.trikeshed.landscape

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * The spatial surfaces' momentum (patch-camera's): pan velocity in screen px/ms sampled from the
 * drag and released as a glide, zoom velocity in log-scale per 16.7 ms anchored at the last wheel
 * point, both decaying frame-rate independently. Off under reduced motion. The adapter owns the
 * camera: it applies each [Glide] step through [LandscapeNavigation.zoomAt] and reports back.
 */
class LandscapeMomentum(val reducedMotion: Boolean = false) {
    /** One animation frame of glide: pan by ([dx], [dy]) screen px, then zoom by [factor] at ([ax], [ay]). */
    class Glide(val dx: Double, val dy: Double, val factor: Double, val ax: Double, val ay: Double, internal val k: Double)

    private var vx = 0.0; private var vy = 0.0; private var zv = 0.0
    private var ax = 0.0; private var ay = 0.0; private var t = 0.0
    var panning = false; private set
    private var lx = 0.0; private var ly = 0.0; private var velX = 0.0; private var velY = 0.0; private var velT = 0.0

    fun kill() { vx = 0.0; vy = 0.0; zv = 0.0 }

    /** True while a glide is still moving the camera. */
    val live: Boolean get() = abs(vx) > PAN_REST || abs(vy) > PAN_REST || zv != 0.0

    fun press(x: Double, y: Double, now: Double) { kill(); panning = true; lx = x; ly = y; velX = 0.0; velY = 0.0; velT = now }

    fun drag(x: Double, y: Double, now: Double) {
        val dt = max(1.0, now - velT)
        velX = 0.75 * velX + 0.25 * ((x - lx) / dt); velY = 0.75 * velY + 0.25 * ((y - ly) / dt)
        lx = x; ly = y; velT = now
    }

    /** Ends a drag; true when the release was a flick that should glide. */
    fun release(now: Double): Boolean {
        panning = false
        if (reducedMotion || now - velT >= FLICK_MS) return false
        vx = velX; vy = velY; t = now
        return true
    }

    /**
     * Records a wheel step already applied (zoom [before] → [after] by [factor], clamped at
     * [ceiling]); true when the zoom should keep gliding.
     */
    fun wheel(px: Double, py: Double, factor: Double, before: Double, after: Double, ceiling: Double, now: Double): Boolean {
        if (reducedMotion) return false
        ax = px; ay = py; vx = 0.0; vy = 0.0
        if (stuck(before, after, ceiling)) zv = 0.0
        else {
            if (sign(zv) != sign(ln(factor))) zv = 0.0
            zv = max(-ZOOM_MAX, min(ZOOM_MAX, zv + ln(factor) * WHEEL_GAIN))
        }
        t = now
        return zv != 0.0
    }

    /** The next glide step at [now], decaying pan velocity; null under reduced motion (and kills it). */
    fun frame(now: Double): Glide? {
        val dt = min(50.0, now - t); t = now
        if (reducedMotion) { kill(); return null }
        val k = dt / 16.7
        var dx = 0.0; var dy = 0.0
        if (!panning && (abs(vx) > PAN_REST || abs(vy) > PAN_REST)) {
            dx = vx * dt; dy = vy * dt
            val fr = PAN_FRICTION.pow(k); vx *= fr; vy *= fr
            if (abs(vx) <= PAN_REST && abs(vy) <= PAN_REST) { vx = 0.0; vy = 0.0 }
        }
        val factor = if (abs(zv) > ZOOM_REST) exp(zv * k) else 1.0
        return Glide(dx, dy, factor, ax, ay, k)
    }

    /** Reports where a glide's zoom landed, so a clamped zoom stops and a free one decays. */
    fun landed(glide: Glide, before: Double, after: Double, ceiling: Double) {
        if (glide.factor == 1.0) return
        if (stuck(before, after, ceiling)) zv = 0.0 else zv *= ZOOM_FRICTION.pow(glide.k)
        if (abs(zv) <= ZOOM_REST) zv = 0.0
    }

    private fun stuck(before: Double, after: Double, ceiling: Double) =
        after == before || after == ceiling || after == LandscapeNavigation.minZoom

    companion object {
        const val PAN_FRICTION = 0.93
        const val ZOOM_FRICTION = 0.88
        const val PAN_REST = 0.002
        const val ZOOM_REST = 0.0008
        const val ZOOM_MAX = 0.12
        const val WHEEL_GAIN = 0.28
        const val FLICK_MS = 80.0

        /** Wheel delta in pixels. deltaMode: 0=pixel, 1=line (Firefox), 2=page. */
        fun wheelPixels(deltaY: Double, deltaMode: Int, pageHeight: Double): Double =
            deltaY * when (deltaMode) { 1 -> 16.0; 2 -> pageHeight; else -> 1.0 }
    }
}
