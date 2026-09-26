package borg.trikeshed.web

import borg.trikeshed.landscape.Cubic
import borg.trikeshed.landscape.Extent
import borg.trikeshed.landscape.LandscapeCamera
import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.landscape.Pt
import borg.trikeshed.landscape.Rect
import org.w3c.dom.Element

/** DOM adapter of commonMain [LandscapeNavigation]: the camera is a mutable JS {x,y,z}. */
object PatchNavigation {
    fun camera(view: dynamic): LandscapeCamera = LandscapeCamera(num(view.x), num(view.y), num(view.z))

    /** Object.assign(view, LandscapeNavigation.zoomAt(view, requested, anchor, ceiling)) */
    fun zoomAt(view: dynamic, requested: Double, ax: Double, ay: Double, ceiling: Double = LandscapeNavigation.detailZoom) {
        val c = LandscapeNavigation.zoomAt(camera(view), requested, Pt(ax, ay), ceiling)
        view.x = c.x; view.y = c.y; view.z = c.z
    }

    fun projectWires(svg: Element, viewport: dynamic, view: dynamic) {
        val width = num(viewport.clientWidth); val height = num(viewport.clientHeight)
        val x = num(view.x); val y = num(view.y); val z = num(view.z)
        svg.setAttribute("width", width.toString()); svg.setAttribute("height", height.toString())
        svg.setAttribute("viewBox", "${-x / z} ${-y / z} ${width / z} ${height / z}")
        svg.asDynamic().style.setProperty("--wire-scale", minOf(1.0, z))
        svg.asDynamic().style.setProperty("--wire-label-scale", 1 / maxOf(1.0, z))
        for (path in arr(svg.asDynamic().querySelectorAll("path"))) if (path._wireCurve != null) projectCurve(path, width, height, view)
    }

    fun wireCurve(path: dynamic, a: dynamic, b: dynamic, viewport: dynamic, view: dynamic) {
        val ax = num(a.x); val ay = num(a.y); val bx = num(b.x); val by = num(b.y)
        val dx = maxOf(40.0, kotlin.math.abs(bx - ax) / 2)
        path._wireCurve = Cubic(Pt(ax, ay), Pt(ax + dx, ay), Pt(bx - dx, by), Pt(bx, by))
        projectCurve(path, num(viewport.clientWidth), num(viewport.clientHeight), view)
    }

    fun projectCurve(path: dynamic, width: Double, height: Double, view: dynamic) {
        val box = LandscapeNavigation.clipBox(width, height, camera(view))
        val d = LandscapeNavigation.pathD(LandscapeNavigation.clipCurve(path._wireCurve.unsafeCast<Cubic>(), box))
        if (path.getAttribute("d") != d) path.setAttribute("d", d)
    }

    fun rect(r: dynamic): Rect? = if (r == null) null else Rect(num(r.left), num(r.top), num(r.right), num(r.bottom))

    /** callout placement as the JS object {x,y,side,tail}; null when nothing fits */
    fun callout(px: Double, py: Double, w: Double, h: Double, bounds: dynamic, subject: dynamic, preferred: dynamic): dynamic {
        val placed = LandscapeNavigation.callout(Pt(px, py), Extent(w, h), rect(bounds)!!, rect(subject),
            if (preferred == null) null else Pt(num(preferred.x), num(preferred.y))) ?: return null
        val o = obj(); o.x = placed.at.x; o.y = placed.at.y; o.side = placed.side.name.lowercase(); o.tail = placed.tail
        return o
    }

    fun encode(view: dynamic): String = LandscapeNavigation.encode(camera(view))

    /** the bookmark camera as a JS {x,y,z}, or null */
    fun decode(hash: String): dynamic {
        val d = LandscapeNavigation.decode(hash) ?: return null
        val o = obj(); o.x = d.camera.x; o.y = d.camera.y; o.z = d.camera.z
        return o
    }
}
