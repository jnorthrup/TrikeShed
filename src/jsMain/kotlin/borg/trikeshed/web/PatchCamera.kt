package borg.trikeshed.web

import borg.trikeshed.landscape.LandscapeNavigation
import kotlinx.browser.document
import kotlinx.browser.window
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * patch-camera.js: the camera both construction surfaces use; navigation never edits a graph.
 * [harness] is the blackboard's Harness object on /blackboard, null on /panels.
 */
class PatchCamera(private val s: PatchSurface, private val harness: () -> dynamic = { null }) {
    private val view get() = s.view
    private val viewport get() = s.viewport

    fun scopeZoomCeiling(px: Double, py: Double, camera: dynamic = view): Double {
        // Screen pixels of slop the pointer gets when aiming at a scope, so a deeply nested
        // one stays reachable after it shrinks below a pixel.
        val pickMinPx = 12.0
        val viewportBox = viewport.getBoundingClientRect()
        val wx = (px - num(camera.x)) / num(camera.z); val wy = (py - num(camera.y)) / num(camera.z)
        val regions = HashMap<Any, dynamic>()
        fun region(n: dynamic): dynamic {
            if (regions.containsKey(n)) return regions[n]
            val host = n._childHost
            val r = host?.getBoundingClientRect()
            val box: dynamic = if (r != null && num(host.offsetWidth) > 0 && num(r.width) > 0 && num(r.height) > 0 && !truthy(n.collapsed) &&
                n.el?.classList?.contains("collapsed") != true) {
                val b = obj()
                b.x = (num(r.left) - viewportBox.left - num(view.x)) / num(view.z)
                b.y = (num(r.top) - viewportBox.top - num(view.y)) / num(view.z)
                b.w = num(r.width) / num(view.z); b.h = num(r.height) / num(view.z)
                b.scale = num(r.width) / num(host.offsetWidth) / num(view.z) * (if (truthy(n._view?.z)) num(n._view.z) else 1.0)
                b
            } else null
            regions[n.unsafeCast<Any>()] = box
            return box
        }
        fun contains(b: dynamic): Boolean {
            if (b == null) return false
            val slop = pickMinPx / num(camera.z)
            val padX = max(0.0, (slop - num(b.w)) / 2); val padY = max(0.0, (slop - num(b.h)) / 2)
            return wx >= num(b.x) - padX && wy >= num(b.y) - padY && wx <= num(b.x) + num(b.w) + padX && wy <= num(b.y) + num(b.h) + padY
        }
        var scale = 1.0; var depth = -1
        for (n in s.nodes()) {
            if (!truthy(n._childHost)) continue
            val box = region(n); if (!contains(box)) continue
            var level = 0; var visible = true
            var p = n._parentScope
            while (truthy(p)) { level++; if (!contains(region(p))) { visible = false; break }; p = p._parentScope }
            if (visible && level > depth) { scale = num(box.scale); depth = level }
        }
        return LandscapeNavigation.maxZoom(scale)
    }

    fun restoreCameraView(camera: dynamic) {
        killMomentum()
        if (camera == null || !listOf(camera.x, camera.y, camera.z).all { typeOf(it) == "number" && num(it).isFinite() } || num(camera.z) <= 0) return
        val r = viewport.getBoundingClientRect(); val ax = r.width / 2; val ay = r.height / 2
        val c = LandscapeNavigation.zoomAt(PatchNavigation.camera(camera), num(camera.z), borg.trikeshed.landscape.Pt(ax, ay), scopeZoomCeiling(ax, ay, camera))
        view.x = c.x; view.y = c.y; view.z = c.z
    }

    private var cameraDetailRoot: dynamic = null
    fun restoreCameraDetail() {
        if (cameraDetailRoot == null) return
        val n = cameraDetailRoot; val v = n._view
        n._childHost.appendChild(n._ringWorld)
        n._ringWorld.style.transform = "translate(${v.x}px,${v.y}px) scale(${v.z})"
        n._ringWorld.style.zIndex = ""; cameraDetailRoot = null
    }

    fun projectCameraDetail() {
        val h = harness() ?: return
        val root = cameraDetailRoot
        if (root != null && (!truthy(root.el.isConnected) || truthy(root.collapsed) || root._program != h.selected)) restoreCameraDetail()
        val vr = viewport.getBoundingClientRect()
        fun project(n: dynamic): Boolean {
            val host = n._childHost; val r = host.getBoundingClientRect(); val scale = num(r.width) / num(host.offsetWidth); val v = n._view
            if (!scale.isFinite() || scale <= 0) return false
            n._ringWorld.style.transform = "matrix(${scale * num(v.z)},0,0,${scale * num(v.z)},${num(r.left) - vr.left + num(v.x) * scale},${num(r.top) - vr.top + num(v.y) * scale})"
            return true
        }
        if (cameraDetailRoot != null && !project(cameraDetailRoot)) restoreCameraDetail()
        var next: dynamic = null; var depth = -1
        if (num(view.z) > DETAIL_LIFT_ZOOM) for (n in s.nodes()) {
            if (!truthy(n._childHost) || !truthy(n.el.isConnected) || truthy(n.collapsed) || n._program != h.selected) continue
            val r = n._childHost.getBoundingClientRect()
            if (num(r.left) > vr.left || num(r.top) > vr.top || num(r.right) < vr.right || num(r.bottom) < vr.bottom) continue
            var level = 0; var visible = true
            var p = n._parentScope
            while (truthy(p)) {
                level++; val b = p._childHost.getBoundingClientRect()
                if (truthy(p.collapsed) || num(b.left) > vr.left || num(b.top) > vr.top || num(b.right) < vr.right || num(b.bottom) < vr.bottom) { visible = false; break }
                p = p._parentScope
            }
            if (visible && level > depth) { next = n; depth = level }
        }
        if (next === cameraDetailRoot) return
        restoreCameraDetail()
        if (next != null) {
            // Only lift a window covering the entire viewport, so clipping is intact.
            cameraDetailRoot = next; viewport.appendChild(next._ringWorld)
            next._ringWorld.style.zIndex = "3"; project(next)
        }
    }

    /** set after construction by the shake module: badges ride the camera */
    var projectVerdicts: () -> Unit = {}
    /** set by the blackboard: its landscape repaints on every camera move */
    var landscapeSchedule: () -> Unit = {}

    fun applyView() {
        s.blipLeave()
        // Matrix translations are numbers, not CSS lengths (which clamp around 33 million px).
        s.world.style.transform = "matrix(${view.z},0,0,${view.z},${view.x},${view.y})"
        projectCameraDetail()
        s.applyWireBox()
        projectVerdicts()
        document.body!!.classList.toggle("zoomed-out", num(view.z) < .45)
        landscapeSchedule()
        val h = harness()
        if (h != null) {
            h.rememberView()
            val breakout = document.getElementById("panelsBreakout").asDynamic()
            if (breakout != null) breakout.href = "/panels" + (if (truthy(h.selected))
                "?" + (if (truthy(h.previews.has(h.selected))) "example" else "load") + "=" + encodeURIComponent(str(h.selected)) else "")
        }
    }

    /* momentum — pan velocity in screen px/ms, zoom velocity in log-scale per 16.7ms anchored at the
       last wheel point. Frame-rate independent decay. Off under prefers-reduced-motion. */
    val reducedMotion: Boolean = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    private var vx = 0.0; private var vy = 0.0; private var zv = 0.0; private var ax = 0.0; private var ay = 0.0
    private var momT = 0.0; private var momFrame = 0; private var panning = false

    fun killMomentum() { vx = 0.0; vy = 0.0; zv = 0.0 }

    private fun now(): Double = window.performance.now()

    private fun tickMomentum() {
        momFrame = 0
        val t = now(); val dt = min(50.0, t - momT); momT = t
        if (reducedMotion) { killMomentum(); return }
        val k = dt / 16.7
        var live = false
        if (!panning && (abs(vx) > 0.002 || abs(vy) > 0.002)) {
            view.x = num(view.x) + vx * dt; view.y = num(view.y) + vy * dt
            val fr = 0.93.pow(k); vx *= fr; vy *= fr
            if (abs(vx) <= 0.002 && abs(vy) <= 0.002) { vx = 0.0; vy = 0.0 } else live = true
        }
        if (abs(zv) > 0.0008) {
            val f = exp(zv * k)
            val ceiling = scopeZoomCeiling(ax, ay)
            val before = num(view.z)
            PatchNavigation.zoomAt(view, num(view.z) * f, ax, ay, ceiling)
            val h = harness()
            if (num(view.z) > before && h != null) h.observeZoom(ax, ay)
            if (num(view.z) == before || num(view.z) == ceiling || num(view.z) == LandscapeNavigation.minZoom) zv = 0.0
            else zv *= 0.88.pow(k)
            if (abs(zv) <= 0.0008) zv = 0.0 else live = true
        }
        applyView()
        if (live) glide() else saveCameraSoon()
    }

    private fun glide() { if (momFrame == 0) momFrame = window.requestAnimationFrame { tickMomentum() } }

    private var camSaveT = 0
    /** Blackboard navigation saves only its bookmark; /panels saves and rewrites the hash. */
    fun saveCameraSoon() {
        window.clearTimeout(camSaveT)
        val h = harness()
        if (h != null) { h.rememberView(); return }
        camSaveT = window.setTimeout({
            s.save()
            val url = jsNew(js("URL"), window.location.href)
            url.hash = PatchNavigation.encode(view)
            window.history.replaceState(null, "", url)
        }, 250)
    }

    /** deltaMode: 0=pixel, 1=line, 2=page. Firefox sends lines. */
    private fun wheelPixels(e: dynamic, rectHeight: Double): Double {
        val k = if (e.deltaMode == 1) 16.0 else if (e.deltaMode == 2) rectHeight else 1.0
        return num(e.deltaY) * k
    }

    fun install() {
        on(viewport, "pointerdown", { e ->
            if (e.button != 0) return@on
            val hb = harness(); if (hb != null) hb.terrainBookmark = null
            killMomentum(); panning = true
            viewport.classList.add("panning")
            val sx = num(e.clientX); val sy = num(e.clientY); val ox = num(view.x); val oy = num(view.y)
            var lx = sx; var ly = sy; var velX = 0.0; var velY = 0.0; var velT = now()
            lateinit var mv: (dynamic) -> Unit
            lateinit var up: (dynamic) -> Unit
            mv = { ev ->
                view.x = ox + num(ev.clientX) - sx; view.y = oy + num(ev.clientY) - sy; applyView()
                val t = now(); val dt = max(1.0, t - velT)
                velX = 0.75 * velX + 0.25 * ((num(ev.clientX) - lx) / dt); velY = 0.75 * velY + 0.25 * ((num(ev.clientY) - ly) / dt)
                lx = num(ev.clientX); ly = num(ev.clientY); velT = t
            }
            up = {
                panning = false; viewport.classList.remove("panning"); off(window, "pointermove", mv); off(window, "pointerup", up); saveCameraSoon()
                if (!reducedMotion && now() - velT < 80) { vx = velX; vy = velY; momT = now(); glide() }
            }
            on(window, "pointermove", mv); on(window, "pointerup", up)
        })
        val passive = obj(); passive.passive = false
        on(viewport, "wheel", { e ->
            e.preventDefault()
            val r = viewport.getBoundingClientRect()
            val px = num(e.clientX) - r.left; val py = num(e.clientY) - r.top
            val dy = wheelPixels(e, r.height)
            // proportional step: a trackpad sends many small deltas where a mouse sends few large ones
            val f = LandscapeNavigation.wheelFactor(dy); val before = num(view.z)
            val ceiling = scopeZoomCeiling(px, py)
            PatchNavigation.zoomAt(view, num(view.z) * f, px, py, ceiling)
            val h = harness()
            if (num(view.z) > before && h != null) h.observeZoom(px, py)
            applyView(); saveCameraSoon()
            if (!reducedMotion) {
                ax = px; ay = py; vx = 0.0; vy = 0.0
                if (num(view.z) == before || num(view.z) == ceiling || num(view.z) == LandscapeNavigation.minZoom) zv = 0.0
                else {
                    if (sign(zv) != sign(ln(f))) zv = 0.0
                    zv = max(-0.12, min(0.12, zv + ln(f) * 0.28))
                }
                momT = now(); if (zv != 0.0) glide()
            }
        }, passive)
    }

    companion object {
        /** zoom past which a covering scope is re-projected at its own scale instead of magnifying #world's raster */
        const val DETAIL_LIFT_ZOOM = 8.0
    }
}
