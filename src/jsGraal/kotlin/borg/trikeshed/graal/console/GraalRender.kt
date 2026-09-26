package borg.trikeshed.graal.console

import borg.trikeshed.web.graal.perfNow
import borg.trikeshed.web.graal.str
import kotlinx.browser.window
import org.w3c.dom.LEFT
import org.w3c.dom.RIGHT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/* ── render ─────────────────────────────────────────────── */

fun GraalConsole.draw() {
    tickMomentum() // kinetic pan/zoom glide (no-op when still, off under reduced motion)
    val dpr = window.devicePixelRatio
    ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
    ctx.fillStyle = Skin.ALL.getValue(skin).bg; ctx.fillRect(0.0, 0.0, innerWidth, innerHeight)
    var sx = 0.0; var sy = 0.0
    if (shake > 0 && !reduced) { sx = (Random.nextDouble() - 0.5) * shake; sy = (Random.nextDouble() - 0.5) * shake; shake *= 0.85; if (shake < 0.4) shake = 0.0 }
    ctx.save(); ctx.translate(sx, sy)
    // Layouts are zoom-independent: NEVER re-lay on zoom — a re-lay moves world rects out from under the wheel's anchor.
    val r = root
    if (r != null) { if (mode == "map") drawMapNode(r, 0) else if (mode == "graph") drawGraph() else drawTree() }
    // pings
    val now = perfNow()
    pings = ArrayList(pings.filter { now - it.t0 < 1400 })
    for (p in pings) {
        val t = (now - p.t0) / 1400; val rr = 6 + t * 46; val a = 1 - t
        val x = (p.x - cam.ox) * cam.s; val y = (p.y - cam.oy) * cam.s
        ctx.strokeStyle = p.color; ctx.globalAlpha = a * 0.9; ctx.lineWidth = 2.0
        ctx.beginPath(); ctx.arc(x, y, rr, 0.0, 7.0); ctx.stroke()
        ctx.globalAlpha = a; ctx.fillStyle = p.color; ctx.beginPath(); ctx.arc(x, y, 3.0, 0.0, 7.0); ctx.fill()
        if (p.label != null && p.label.isNotEmpty() && t < 0.6) { ctx.font = "10px monospace"; ctx.fillText(p.label, x + 8, y - 8) }
        ctx.globalAlpha = 1.0
    }
    // DAG edge overlay for the selection: arcs the prefix tree cannot show
    val s = sel
    if (s != null && selEdges.isNotEmpty() && s.placed) {
        val sx0 = (s.x + s.w / 2 - cam.ox) * cam.s; val sy0 = (s.y + s.h / 2 - cam.oy) * cam.s
        val dash = (perfNow() / 50) % 16
        for (e in selEdges) {
            val to = e.to ?: continue
            if (!to.placed) continue
            val tx = (to.x + to.w / 2 - cam.ox) * cam.s; val ty = (to.y + to.h / 2 - cam.oy) * cam.s
            val color = if (e.kind == "shared-blob") "rgba(63,208,255,.65)" else "rgba(255,79,88,.7)"
            ctx.strokeStyle = color
            ctx.lineWidth = 1.2; ctx.setLineDash(arrayOf(6.0, 10.0)); ctx.lineDashOffset = -dash
            ctx.beginPath(); ctx.moveTo(sx0, sy0)
            ctx.quadraticCurveTo((sx0 + tx) / 2, min(sy0, ty) - 40, tx, ty); ctx.stroke()
            ctx.setLineDash(arrayOf())
            ctx.fillStyle = color; ctx.beginPath(); ctx.arc(tx, ty, 2.5, 0.0, 7.0); ctx.fill()
        }
    }
    ctx.restore()
    drawMini()
    window.requestAnimationFrame { draw() }
}

fun GraalConsole.drawMapNode(n: TerrainNode, depth: Int) {
    val r = root ?: return
    painter.drawNode(borg.trikeshed.web.graal.TerrainPainter.Frame(ctx, cam, innerWidth, innerHeight, Skin.ALL.getValue(skin), r, hover, fog,
        { heatOf(it) }, layer, maxSeq, Topology.ALL), n, depth)
}

/* graph mode: territories as bodies, shared-blob hubs as springs */
fun GraalConsole.drawGraph() {
    val S = Skin.ALL.getValue(skin); graph.step(gDrag)
    for (e in graph.edges) {
        val a = graph.nodes[e.a]; val b = graph.nodes[e.b]
        val ax = (a.x - cam.ox) * cam.s; val ay = (a.y - cam.oy) * cam.s; val bx = (b.x - cam.ox) * cam.s; val by = (b.y - cam.oy) * cam.s
        ctx.strokeStyle = if (skin == "marker") "rgba(60,70,90,.28)" else "rgba(140,160,180,.22)"
        ctx.lineWidth = min(5.0, 0.6 + log2(1 + e.weight) * 0.35)
        ctx.beginPath(); ctx.moveTo(ax, ay); ctx.lineTo(bx, by); ctx.stroke()
    }
    ctx.font = "10px monospace"
    for (g in graph.nodes) {
        val x = (g.x - cam.ox) * cam.s; val y = (g.y - cam.oy) * cam.s; val rr = g.r * sqrt(cam.s)
        val h = heatOf(g.n)
        val dimmed = fog && h <= 0
        ctx.globalAlpha = if (dimmed) 0.16 else 1.0
        ctx.fillStyle = S.fill(g.n.hue, 26); ctx.strokeStyle = S.stroke(g.n.hue, 30); ctx.lineWidth = S.lw
        ctx.beginPath(); ctx.arc(x, y, rr, 0.0, 7.0); ctx.fill(); ctx.stroke()
        if (h > 0) {
            ctx.strokeStyle = S.hot(g.n.hue); ctx.globalAlpha = h * 0.9
            ctx.beginPath(); ctx.arc(x, y, rr + 3, 0.0, 7.0); ctx.stroke(); ctx.globalAlpha = if (dimmed) 0.16 else 1.0
        }
        ctx.fillStyle = S.text; ctx.fillText(g.k, x + rr + 3, y + 3)
        ctx.globalAlpha = 1.0
    }
}

/* tree mode: radial drilldown of the target territory */
fun GraalConsole.drawTree() {
    val S = Skin.ALL.getValue(skin)
    val center = targetNode() ?: return
    val cx = innerWidth / 2; val cy = (innerHeight + 60) / 2; val R = min(cx, cy) - 70
    val radial = Radial(center)
    val angleOf = radial.angleOf
    ctx.font = "10px monospace"
    fun drawAt(n: TerrainNode, depth: Int) {
        val a = angleOf.getValue(n); val r = depth * R / 3
        val x = cx + cos(a) * r; val y = cy + sin(a) * r
        for (c in n.children.values) {
            if (depth >= 3) break
            val ca = angleOf.getValue(c); val cr = (depth + 1) * R / 3
            val cxx = cx + cos(ca) * cr; val cyy = cy + sin(ca) * cr
            ctx.strokeStyle = if (skin == "marker") "rgba(60,70,90,.3)" else "rgba(140,160,180,.25)"; ctx.lineWidth = 1.0
            ctx.beginPath(); ctx.moveTo(x, y)
            ctx.quadraticCurveTo(cx + cos((a + ca) / 2) * (r + cr) / 2 * 0.72, cy + sin((a + ca) / 2) * (r + cr) / 2 * 0.72, cxx, cyy); ctx.stroke()
            drawAt(c, depth + 1)
        }
        val dimmed = fog && heatOf(n) <= 0 && n !== center
        ctx.globalAlpha = if (dimmed) 0.18 else 1.0
        ctx.fillStyle = S.fill(n.hue, 26); ctx.strokeStyle = S.stroke(n.hue, 32)
        val rr = if (n === center) 7.0 else 4 + min(3.0, log2(1.0 + n.docs) / 3)
        ctx.beginPath(); ctx.arc(x, y, rr, 0.0, 7.0); ctx.fill(); ctx.stroke()
        if (depth < 2 || n.docs > 50 || angleOf.size < 80) {
            ctx.fillStyle = S.text
            val lx = x + cos(a) * (rr + 4); val ly = y + sin(a) * (rr + 4)
            ctx.save(); ctx.translate(lx, ly)
            if (cos(a) < 0) { ctx.rotate(a + PI); ctx.textAlign = org.w3c.dom.CanvasTextAlign.RIGHT } else ctx.rotate(a)
            ctx.fillText(n.name.take(22), 0.0, 3.0); ctx.restore(); ctx.textAlign = org.w3c.dom.CanvasTextAlign.LEFT
        }
        ctx.globalAlpha = 1.0
    }
    drawAt(center, 0)
    ctx.fillStyle = S.sub; ctx.font = "11px monospace"
    ctx.fillText("radial drilldown — " + (if (center === root) "/" else pathOf(center)) + "  (" + radial.leaves.size + " arms, depth 3)  ·  T for the sheet form", 12.0, innerHeight - 26)
}

fun GraalConsole.drawMini() {
    val S = Skin.ALL.getValue(skin)
    mctx.setTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); mctx.fillStyle = S.mini; mctx.fillRect(0.0, 0.0, 200.0, 124.0)
    val r = root ?: return
    val kx = 200 / Terrain.W; val ky = 124 / Terrain.H
    for (c in r.children.values) {
        if (!c.placed) continue
        mctx.fillStyle = S.miniTile(c.hue)
        mctx.fillRect(c.x * kx + 1, c.y * ky + 1, max(1.0, c.w * kx - 2), max(1.0, c.h * ky - 2))
    }
    mctx.strokeStyle = "#f29111"; mctx.lineWidth = 1.0
    mctx.strokeRect(cam.ox * kx, cam.oy * ky, (innerWidth / cam.s) * kx, (innerHeight / cam.s) * ky)
}

fun GraalConsole.pingAt(n0: TerrainNode?, color: String, label: String? = null) {
    var n = n0
    if (n == null || !n.placed) n = root
    if (n == null || !n.placed) return
    pings.add(Ping(n.x + n.w / 2, n.y + n.h / 2, color, label, perfNow()))
}
