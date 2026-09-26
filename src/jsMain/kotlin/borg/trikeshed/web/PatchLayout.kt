package borg.trikeshed.web

import borg.trikeshed.web.patch.CenterBox
import borg.trikeshed.web.patch.PatchLimits
import borg.trikeshed.web.patch.PatchPlacement
import borg.trikeshed.web.patch.PatchRing
import borg.trikeshed.web.patch.RingChild
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.math.sqrt

/** The owner a layout settles within: its selected parent scope, document and hint source. */
interface PatchLayoutOwner {
    val selected: String?
    val parentRevision: Int
    fun parentTarget(): dynamic
    fun document(): dynamic
    suspend fun layoutHints(document: dynamic, parentId: dynamic): Array<dynamic>
    fun layoutNodeId(id: dynamic): String
    fun resizeParent(parent: dynamic)
    /** blackboard: mounted program anchors; null on /panels */
    val mounts: dynamic get() = null
    fun bounds(name: String): dynamic = null
}

/**
 * patch-layout.js: the bounded d3-force placement both construction surfaces share. Input
 * geometry is copied; neither the solver nor its hints can install a cable.
 */
class PatchLayout(private val s: PatchSurface) {
    private var busy = false

    suspend fun layout(boxes: List<dynamic>, patches: List<dynamic>, valid: (() -> Boolean)?): dynamic {
        if (boxes.size > PatchLimits.nodes || patches.size > PatchLimits.edges) throw Error("Layout size budget exceeded")
        val now = { window.performance.now() }
        val start = now()
        var steps = 0
        fun check(validate: Boolean = true) {
            if (validate && valid != null && !valid()) throw Error("Layout discarded: document or selected parent changed")
            if (now() - start > PatchLimits.milliseconds) throw Error("Layout time budget exceeded; select a smaller scope")
        }
        fun work() { if (++steps > PatchLimits.steps) throw Error("Layout work budget exceeded; select a smaller scope") }
        var lastYield = start
        suspend fun breathe() {
            check(false)
            if (now() - lastYield >= 12) { Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 0) }.await(); lastYield = now(); check() }
        }
        val nodes = boxes.mapIndexed { i, b ->
            val vals = listOf(num(b.x), num(b.y), num(b.w), num(b.h))
            if (!vals.all { it.isFinite() } || num(b.w) <= 0 || num(b.h) <= 0 || maxOf(num(b.w), num(b.h)) > 100000) throw Error("Invalid layout geometry")
            val n = spread(b); n.index = i; n.x = num(b.x) + num(b.w) / 2; n.y = num(b.y) + num(b.h) / 2; n.degree = 0; n.component = i
            n
        }
        val byId = HashMap<String, dynamic>(); for (n in nodes) byId[str(n.id)] = n
        if (byId.size != nodes.size) throw Error("Duplicate layout identity")
        fun rootOf(n0: dynamic): Int {
            val n = n0
            while (n.component != nodes[n.component as Int].component) n.component = nodes[n.component as Int].component
            return n.component as Int
        }
        val links = ArrayList<dynamic>()
        for (p in patches) {
            val a = byId[str(p.from)]; val b = byId[str(p.to)]; if (a == null || b == null || a === b) continue
            fun offset(v: dynamic, fx: Double, fy: Double): dynamic =
                if (v != null && num(v.x).isFinite() && num(v.y).isFinite()) v else { val o = obj(); o.x = fx; o.y = fy; o }
            val l = obj(); l.source = a; l.target = b; l.hint = truthy(p.hint)
            l.out = offset(p.out, num(a.w) / 2, 0.0); l["in"] = offset(p["in"], -num(b.w) / 2, 0.0)
            links.add(l)
            a.degree = num(a.degree) + 1; b.degree = num(b.degree) + 1; nodes[rootOf(b)].component = rootOf(a)
        }
        val groups = LinkedHashMap<Int, ArrayList<dynamic>>()
        for (n in nodes) groups.getOrPut(rootOf(n)) { ArrayList() }.add(n)
        val groupsLinks = HashMap<Int, ArrayList<dynamic>>()
        for (l in links) groupsLinks.getOrPut(rootOf(l.source)) { ArrayList() }.add(l)
        class Packed(val group: List<dynamic>, val w: Double, val h: Double, val cx: Double, val cy: Double)
        val packed = ArrayList<Packed>()
        var ticks = 0
        for ((key, group) in groups) {
            check(false)
            val local = groupsLinks[key] ?: ArrayList()
            val cx = group.sumOf { num(it.x) } / group.size; val cy = group.sumOf { num(it.y) } / group.size
            val extent = maxOf(1.0, group.maxOf { maxOf(abs(num(it.x) - cx), abs(num(it.y) - cy)) })
            val radius = sqrt(group.sumOf { (num(it.w) + PatchLimits.gap) * (num(it.h) + PatchLimits.gap) })
            val scale = minOf(1.0, radius / extent)
            for (n in group) { n.x = (num(n.x) - cx) * scale; n.y = (num(n.y) - cy) * scale }
            val maxW = group.maxOf { num(it.w) }; val maxH = group.maxOf { num(it.h) }
            val xOf: (dynamic) -> Double = { num(it.x) }; val yOf: (dynamic) -> Double = { num(it.y) }
            fun separate(): Int {
                val tree = borg.trikeshed.web.d3.quadtree(group.toTypedArray(), xOf, yOf)
                var overlaps = 0
                for (a in group) {
                    tree.visit { q0: dynamic, x0: Double, y0: Double, x1: Double, y1: Double ->
                        work()
                        val rx = (num(a.w) + maxW) / 2 + PatchLimits.gap; val ry = (num(a.h) + maxH) / 2 + PatchLimits.gap
                        if (x0 > num(a.x) + rx || x1 < num(a.x) - rx || y0 > num(a.y) + ry || y1 < num(a.y) - ry) return@visit true
                        var q = q0
                        if (!truthy(q.length)) do {
                            val b = q.data
                            if (num(b.index) > num(a.index)) {
                                val dx = num(b.x) - num(a.x); val dy = num(b.y) - num(a.y)
                                val ox = (num(a.w) + num(b.w)) / 2 + PatchLimits.gap - abs(dx); val oy = (num(a.h) + num(b.h)) / 2 + PatchLimits.gap - abs(dy)
                                if (ox > 0 && oy > 0) {
                                    overlaps++
                                    val axis = if (ox < oy) "x" else "y"; val sign = if ((if (axis == "x") dx else dy) < 0) -1 else 1
                                    val push = (minOf(ox, oy) + .1) * sign * .5
                                    a["v$axis"] = num(a["v$axis"]) - push; b["v$axis"] = num(b["v$axis"]) + push
                                }
                            }
                            q = q.next
                        } while (q != null)
                        false
                    }
                }
                return overlaps
            }
            if (group.size > 1) {
                val simulation = borg.trikeshed.web.d3.forceSimulation(group.toTypedArray()).stop().velocityDecay(.55).alphaDecay(.035)
                    .force("links", borg.trikeshed.web.d3.forceLink(local.toTypedArray())
                        .distance { l: dynamic -> (num(l.source.w) + num(l.target.w)) / 2 + PatchLimits.cable }
                        .strength { l: dynamic -> (if (truthy(l.hint)) .18 else .35) / maxOf(num(l.source.degree), num(l.target.degree)) })
                    .force("x", borg.trikeshed.web.d3.forceX(0.0).strength(.006)).force("y", borg.trikeshed.web.d3.forceY(0.0).strength(.006))
                    .force("ports", { alpha: Double ->
                        for (l in local) {
                            work()
                            val a = l.source; val b = l.target; val k = alpha * (if (truthy(l.hint)) .14 else .24) / maxOf(num(a.degree), num(b.degree))
                            val dx = num(b.x) + num(l["in"].x) - num(a.x) - num(l.out.x) - PatchLimits.cable
                            val dy = num(b.y) + num(l["in"].y) - num(a.y) - num(l.out.y)
                            a.vx = num(a.vx) + dx * k; b.vx = num(b.vx) - dx * k; a.vy = num(a.vy) + dy * k; b.vy = num(b.vy) - dy * k
                        }
                    }).force("clearance", { _: Double -> separate() })
                try {
                    for (i in 0 until PatchLimits.ticks) {
                        simulation.tick(); ticks++
                        if (i % 8 == 7) breathe()
                    }
                } finally { simulation.stop() }
                // Settle residual collisions at the nearest vacant rectangle, hub first.
                val placed = borg.trikeshed.web.d3.quadtree(jsArray(), xOf, yOf)
                val order = group.withIndex().sortedWith(compareBy<IndexedValue<dynamic>>({ -num(it.value.degree) }, { num(it.value.index) })).map { it.value }
                for (n in order) {
                    val p = PatchPlacement.vacancy(CenterBox(num(n.x), num(n.y), num(n.w), num(n.h)), PatchLimits.gap) { px, py ->
                        var hit: dynamic = null
                        placed.visit { q0: dynamic, x0: Double, y0: Double, x1: Double, y1: Double ->
                            work()
                            val rx = (num(n.w) + maxW) / 2 + PatchLimits.gap; val ry = (num(n.h) + maxH) / 2 + PatchLimits.gap
                            if (hit != null || x0 > px + rx || x1 < px - rx || y0 > py + ry || y1 < py - ry) return@visit true
                            var q = q0
                            if (!truthy(q.length)) do {
                                val b = q.data
                                if (abs(px - num(b.x)) < (num(n.w) + num(b.w)) / 2 + PatchLimits.gap && abs(py - num(b.y)) < (num(n.h) + num(b.h)) / 2 + PatchLimits.gap) { hit = b; break }
                                q = q.next
                            } while (q != null)
                            false
                        }
                        if (hit == null) null else CenterBox(num(hit.x), num(hit.y), num(hit.w), num(hit.h))
                    }
                    n.x = p.first; n.y = p.second; placed.add(n)
                    breathe()
                }
            }
            val x = group.minOf { num(it.x) - num(it.w) / 2 }; val y = group.minOf { num(it.y) - num(it.h) / 2 }
            val w = group.maxOf { num(it.x) + num(it.w) / 2 } - x; val h = group.maxOf { num(it.y) + num(it.h) / 2 } - y
            for (n in group) { n.x = num(n.x) - (x + num(n.w) / 2); n.y = num(n.y) - (y + num(n.h) / 2) }
            packed += Packed(group, w, h, cx, cy)
        }
        // Disconnected segments share space, not springs or execution dependencies.
        val width = maxOf(1.0, packed.maxOfOrNull { it.w } ?: 1.0, sqrt(packed.sumOf { (it.w + PatchLimits.cable) * (it.h + PatchLimits.cable) } * 1.5))
        var x = 40.0; var y = 40.0; var row = 0.0
        packed.sortWith(compareBy<Packed>({ it.cy }, { it.cx }))
        for (p in packed) {
            if (x > 40 && x + p.w > width + 40) { x = 40.0; y += row + PatchLimits.cable; row = 0.0 }
            for (n in p.group) { n.x = num(n.x) + x; n.y = num(n.y) + y }
            x += p.w + PatchLimits.cable; row = maxOf(row, p.h)
        }
        check()
        val result = obj()
        result.positions = nodes.map { n -> val o = obj(); o.id = n.id; o.x = n.x; o.y = n.y; o }.toTypedArray()
        result.segments = packed.size; result.links = links.size; result.hints = links.count { truthy(it.hint) }
        result.steps = steps; result.ticks = ticks; result.elapsedMs = now() - start
        return result
    }

    /** Proximity-ranked layout hints across the document; these proposals move boxes only. */
    suspend fun hints(document: dynamic, parentId: dynamic): Array<dynamic> {
        val controller = jsNew(js("AbortController")); val timer = window.setTimeout({ controller.abort() }, 8000)
        try {
            val pending = ArrayList<Pair<dynamic, Int>>(); for (n in arr(document.nodes)) pending.add(Pair(n, 0))
            var count = 0
            while (pending.isNotEmpty()) {
                val (n, depth) = pending.removeAt(pending.size - 1)
                if (++count > 1500 || depth > 32) throw Error("Layout matching size budget exceeded")
                for (child in arr(n.children)) pending.add(Pair(child, depth + 1))
            }
            val options = obj(); options.parentId = parentId; options.reach = js("Number.MAX_SAFE_INTEGER")
            val req = obj(); req.program = document; req.options = options
            val body = JSON.stringify(req)
            if (num(js("new TextEncoder()").encode(body).length) > 1048576) throw Error("Layout request payload limit exceeded")
            val init = jsonInit("POST", body); init.signal = controller.signal
            val response = fetchJs("/api/lcnc/treeshake", init)
            val result = JSON.parse<dynamic>(PatchRead.text(response, 2097152))
            if (!truthy(response.ok) || !truthy(result.ok)) throw Error(str(result.detail ?: result.error ?: response.status))
            if (parentId != null && result.parentId != parentId) throw Error("Server did not confirm the selected parent")
            return arr(result.made)
        } finally { window.clearTimeout(timer) }
    }

    /** one-shot layout: layer by cable polarity, relax the rest, then STOP */
    suspend fun settle(owner: PatchLayoutOwner?) {
        if (busy) return
        val target: dynamic = owner?.parentTarget()
        if (owner != null && target == null) return
        val nodes = (if (target != null) arr(target.nodes).toList() else s.nodes().filter { !truthy(it._parentScope) }).filter { truthy(it.el) }
        val origin: dynamic = if (target?.origin != null) target.origin else { val o = obj(); o.x = 0; o.y = 0; o }
        val parent: dynamic = target?.node
        val revision = owner?.parentRevision ?: 0
        if (nodes.size < 2) { s.status("fd: nothing to place"); return }
        val name: String? = if (target != null) target.handle.program?.unsafeCast<String>() else null
        val doc: dynamic = if (target != null) owner!!.document() else null
        val snapshot = JSON.stringify(doc)
        fun ow(n: dynamic) = if (truthy(n.el.offsetWidth)) num(n.el.offsetWidth) else 220.0
        fun oh(n: dynamic) = if (truthy(n.el.offsetHeight)) num(n.el.offsetHeight) else 120.0
        val boxes = nodes.map { n -> val b = obj(); b.id = n.id; b.x = num(n.x) - num(origin.x); b.y = num(n.y) - num(origin.y); b.w = ow(n); b.h = oh(n); b }
        val current = {
            nodes.withIndex().all { (i, n) -> s.nodes().contains(n) && ow(n) == num(boxes[i].w) && oh(n) == num(boxes[i].h) } &&
                (target == null || (owner!!.selected == name && owner.parentRevision == revision &&
                    owner.parentTarget()?.handle?.nodeId == target.handle.nodeId && JSON.stringify(owner.document()) == snapshot))
        }
        val fd = document.getElementById("fdBtn").asDynamic()
        busy = true; fd?.disabled = true; s.status("Checking nearby patches")
        try {
            if (nodes.size > PatchLimits.nodes) throw Error("Layout size budget exceeded; select a smaller scope")
            var hints: Array<dynamic> = emptyArray(); var warning = ""
            if (target != null) try { hints = owner!!.layoutHints(doc, target.handle.nodeId) }
            catch (e: CancellationException) { throw e }
            catch (e: dynamic) { warning = "; existing cables only: " + failureText(e) }
            if (!current()) throw Error("Layout discarded: document or selected parent changed")
            val byId = HashMap<String, dynamic>(); for (n in s.nodes()) byId[str(n.id)] = n
            val selected = LinkedHashMap<String, dynamic>(); for (n in nodes) selected[str(n.id)] = n
            fun topOf(id: dynamic): dynamic {
                var n = byId[str(id)]
                while (n != null && truthy(n._parentScope) && n._parentScope !== parent) n = n._parentScope
                return n
            }
            val edges = ArrayList<dynamic>(); val seen = HashSet<String>()
            // DOM coordinates are divided by the full enclosing scale, including ring zoom.
            fun pin(id: dynamic, dir: String, port: dynamic, top: dynamic): dynamic {
                val n = byId[str(id)]; if (n !== top) return null
                val el = arr(n.el.querySelectorAll(".port")).firstOrNull { it.dataset.dir == dir && it.dataset.port == port && it.closest(".node") === n.el } ?: return null
                val a = el.getBoundingClientRect(); val b = n.el.getBoundingClientRect(); val scale = num(s.view.z) * s.ringScaleOf(n)
                val o = obj()
                o.x = (num(a.left) + num(a.width) / 2 - num(b.left)) / scale - ow(n) / 2
                o.y = (num(a.top) + num(a.height) / 2 - num(b.top)) / scale - oh(n) / 2
                return o
            }
            fun add(from: dynamic, to: dynamic, hint: Boolean) {
                val a = topOf(from[0]); val b = topOf(to[0])
                if (a == null || b == null || a === b || !selected.containsKey(str(a.id)) || !selected.containsKey(str(b.id))) return
                val key = JSON.stringify(arrayOf(from, to)); if (!seen.add(key)) return
                val e = obj(); e.from = a.id; e.to = b.id; e.out = pin(from[0], "out", from[1], a); e["in"] = pin(to[0], "in", to[1], b); e.hint = hint
                edges.add(e)
            }
            for (w in arr(s.graph.wires)) add(w.from, w.to, false)
            for (h in hints) add(arrayOf(owner!!.layoutNodeId(h.fromNode), h.fromPort), arrayOf(owner.layoutNodeId(h.toNode), h.toPort), true)
            s.status("Settling nearby patches")
            val result = layout(boxes, edges, current)
            if (!current()) throw Error("Layout discarded: document or selected parent changed")
            val positions = arr(result.positions)
            if (target != null && parent == null && owner!!.mounts != null) {
                val left = positions.minOf { num(it.x) }; val top = positions.minOf { num(it.y) }
                val bx = num(origin.x) + left - 24; val by = num(origin.y) + top - 64
                val bw = positions.withIndex().maxOf { (i, p) -> num(p.x) + num(boxes[i].w) } - left + 48
                val bh = positions.withIndex().maxOf { (i, p) -> num(p.y) + num(boxes[i].h) } - top + 88
                val obstacles = ArrayList<CenterBox>()
                val entries: Array<dynamic> = js("Array.from(owner.mounts)").unsafeCast<Array<dynamic>>()
                for (entry in entries) {
                    val other = str(entry[0]); val anchor = entry[1]; if (other == name) continue
                    val b = owner.bounds(other)
                    obstacles += CenterBox(num(anchor.x) + num(b.left) - 24, num(anchor.y) + num(b.top) - 64, num(b.w) + 48, num(b.h) + 88)
                }
                val position = PatchPlacement.placement(bx, by, bw, bh, obstacles)
                origin.x = num(origin.x) + position.first - bx; origin.y = num(origin.y) + position.second - by
            }
            for (p in positions) {
                val n = selected.getValue(str(p.id)); n.x = num(origin.x) + num(p.x); n.y = num(origin.y) + num(p.y)
                n.el.style.left = "${n.x}px"; n.el.style.top = "${n.y}px"
            }
            if (parent != null) { if (owner != null) owner.resizeParent(parent) else s.resizeParentFrames(parent) }
            s.redraw(); s.save()
            window.requestAnimationFrame { s.redraw(); if (owner == null || (owner.selected == name && owner.parentRevision == revision)) s.fitToContent() }
            s.status("fd: ${nodes.size} boxes, ${(result.links as Int) - (result.hints as Int)} cable links, ${result.segments} connected groups, ${result.hints} candidate pulls; cables unchanged$warning")
        } catch (e: CancellationException) {
            throw e
        } catch (e: dynamic) {
            s.status(failureText(e))
        } finally { busy = false; fd?.disabled = false }
    }

    /**
     * The concentric pass (commonMain [PatchRing.plan]) applied to a ring's DOM: post-order, so inner
     * rings are sized before their parent measures them.
     */
    fun ringLayout(n: dynamic, preserve: Boolean = false, resize: ((dynamic, Boolean) -> Unit)? = null,
                   frame: ((dynamic) -> Unit)? = null, applyRingView: (dynamic) -> Unit): dynamic {
        val kids = arr(n.children).filter { truthy(it.el) }
        val rings = kids.filter { it.children != null && num(it.children.length) > 0 }
        for (r in rings) ringLayout(r, preserve, resize, frame, applyRingView)
        // Moving into a scaled host must not change shrink-to-fit widths mid-layout.
        for (c in kids) c.el.style.width = "${if (truthy(c.el.offsetWidth)) c.el.offsetWidth else 210}px"
        if (preserve && truthy(n._ringWorld)) {
            for (c in kids) {
                c.el.classList.add("inring"); c.el.style.position = "absolute"; c.el.style.left = "${c.x}px"; c.el.style.top = "${c.y}px"
                n._ringWorld.appendChild(c.el)
            }
            resize?.invoke(n, false)
            val o = obj(); o.w = js("parseFloat")(n._ringWorld.style.width); o.h = js("parseFloat")(n._ringWorld.style.height); return o
        }
        val plan = PatchRing.plan(kids.map { c ->
            RingChild(if (truthy(c.el.offsetWidth)) num(c.el.offsetWidth) else 210.0, if (truthy(c.el.offsetHeight)) num(c.el.offsetHeight) else 96.0,
                c.type == "scope.in", c.type == "scope.out", c.children != null && num(c.children.length) > 0)
        })
        val host = n._childHost
        val rw = if (truthy(n._ringWorld)) n._ringWorld else host
        if (truthy(n._ringWorld)) {
            rw.style.width = "${plan.width}px"; rw.style.height = "${plan.height}px"
            val v = PatchRing.view(plan.width, plan.height)
            val view = obj(); view.x = v.x; view.y = v.y; view.z = v.z; n._view = view
            // applyRingView sizes the frame from the world × this ring's zoom
            applyRingView(n)
            frame?.invoke(n)
        } else {
            host.style.minWidth = "${plan.width}px"; host.style.minHeight = "${plan.height}px"
        }
        for (p in plan.placements) {
            val c = kids[p.index]
            c.x = p.x; c.y = p.y; c.el.classList.add("inring"); c.el.style.position = "absolute"
            c.el.style.left = "${p.x}px"; c.el.style.top = "${p.y}px"; rw.appendChild(c.el)
        }
        val o = obj(); o.w = plan.width; o.h = plan.height; return o
    }
}
