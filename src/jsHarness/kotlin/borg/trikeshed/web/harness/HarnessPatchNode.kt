package borg.trikeshed.web.harness

import borg.trikeshed.web.PatchNavigation
import borg.trikeshed.web.api
import borg.trikeshed.web.arr
import borg.trikeshed.web.isArray
import borg.trikeshed.web.jsArray
import borg.trikeshed.web.num
import borg.trikeshed.web.off
import borg.trikeshed.web.on
import borg.trikeshed.web.str
import borg.trikeshed.web.typeOf
import borg.trikeshed.web.patch.PatchKinds
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.math.hypot

private fun dragTypes(e: dynamic): List<String> = arr(js("Array.from(e.dataTransfer.types)")).map { str(it) }

@OptIn(DelicateCoroutinesApi::class)
internal fun launchJs(block: suspend () -> Unit) { GlobalScope.launch { block() } }

/** Build a node's DOM: header, ring window or lane captions, ports, param editors, resize grip, drag handle. */
fun HarnessPatch.buildNode(n: dynamic) {
    val t = contract(n.type) ?: return // vocabulary is server-owned
    n.params = nodeParams(n.type, n.params)
    val el = document.createElement("div").asDynamic(); el.className = "node"; el.dataset.id = n.id; el.dataset.type = n.type; n.el = el
    // CONCENTRIC CONTEXT: a scope node IS a ring square; its children render INSIDE it.
    val isScope = n.type == "scope" || (truthy(n.children) && num(n.children.length) > 0)
    if (isScope) {
        el.classList.add("scope")
        val lane = laneOf(n)
        if (lane != null) el.dataset.lane = lane.id
    }
    val collapsed = truthy(n.collapsed)
    val hd = document.createElement("div").asDynamic(); hd.className = "hd"
    hd.innerHTML = "<i></i><b>${t.title}</b><button type=\"button\" class=\"node-collapse\" aria-expanded=\"${!collapsed}\" aria-label=\"${if (collapsed) "Expand" else "Collapse"} node\" title=\"${if (collapsed) "Expand node" else "Collapse node (keep contents)"}\">${if (collapsed) "\u25b8" else "\u25be"}</button><button type=\"button\" class=\"node-delete\" aria-label=\"Delete node\" title=\"Delete node and its cables\">✕</button>"
    el.appendChild(hd)
    // the hover blip: asserted / inferred / graal for THIS node, from the daemon
    on(el, "pointerenter", { e -> HarnessBlip.enter(n, e) })
    on(el, "pointerleave", { e -> HarnessBlip.leave(e) })
    on(el, "pointermove", { e -> HarnessBlip.move(e) })
    on(el, "pointerdown", { e -> HarnessBlip.leave(e) })
    val collapse = hd.querySelector(".node-collapse")
    on(collapse, "pointerdown", { e -> e.stopPropagation() })
    on(collapse, "click", { e -> e.stopPropagation(); toggleNodeCollapsed(n, collapse) })
    on(collapse, "dblclick", { e -> e.stopPropagation() })
    if (isScope) {
        // the ring label: the frame identity — name + depth + the Key it runs under
        val depth = ringDepth(n)
        val lbl = document.createElement("div").asDynamic(); lbl.className = "ringlbl"
        lbl.innerHTML = "◍ D$depth <i>LcncScopeFrame${if (depth > 0) " r$depth" else " root"} · bindings·outputs</i>"
        el.appendChild(lbl)
        val seed = document.createElement("div").asDynamic(); seed.className = "seed"; seed.textContent = arr(t.ins).joinToString(" · ") { str(it) }
        val emit = document.createElement("div").asDynamic(); emit.className = "emit"; emit.textContent = arr(t.outs).joinToString(" · ") { str(it) }
        el.appendChild(seed); el.appendChild(emit)
        // the grid is the WINDOW; the ringworld inside it is this ring's own canvas with its own camera
        val grid = document.createElement("div").asDynamic(); grid.className = "childgrid"
        val rw = document.createElement("div").asDynamic(); rw.className = "ringworld"; grid.appendChild(rw)
        el.appendChild(grid)
        n._childHost = grid; n._ringWorld = rw; n._view = obj { it.x = 0; it.y = 0; it.z = 1 }
        // On the blackboard the shared camera traverses the nest: the ring's own wheel/pan gestures stand down.
        on(lbl, "pointerdown", { ev -> if (ev.button == 0) { window.asDynamic().Harness.selectParent(n); window.asDynamic().Harness.dragParent(ev) } })
        on(lbl, "dblclick", { ev -> ev.stopPropagation(); window.asDynamic().Harness.focusNode(n) })
    } else {
        // leaf nodes name their Key factory's upstream state and hand-off
        val lane = laneOf(n)
        if (lane != null) el.dataset.lane = lane.id
        val seed = document.createElement("div").asDynamic(); seed.className = "seed"; seed.textContent = if (lane != null) lane.seed else t.title
        val emit = document.createElement("div").asDynamic(); emit.className = "emit"; emit.textContent = if (lane != null) lane.emit else arr(t.outs).joinToString(", ") { str(it) }
        el.appendChild(seed); el.appendChild(emit)
    }
    val body = document.createElement("div").asDynamic(); body.className = "body"
    el.appendChild(body)
    renderNodePorts(n)
    val ps = document.createElement("div").asDynamic(); ps.className = "params"
    for (k in keysOf(t.params)) {
        val def = t.params[k]
        val lab = document.createElement("label").asDynamic(); lab.textContent = k; ps.appendChild(lab)
        if (truthy(def.cols) && num(def.cols.length) > 0) { ps.appendChild(listWidget(n, k, def)); continue }
        val inp: dynamic
        if (truthy(def.opts) || truthy(def.optsFrom)) {
            inp = document.createElement("select").asDynamic()
            val fill: (Array<dynamic>) -> Unit = fill@{ list ->
                val keep = n.params[k]; inp.textContent = ""
                for (o in list) { val op = document.createElement("option").asDynamic(); op.value = o; op.textContent = o; inp.appendChild(op) }
                if (truthy(keep) && list.none { it == keep }) {
                    val op = document.createElement("option").asDynamic(); op.value = keep; op.textContent = keep; op.dataset.stale = "1"; inp.appendChild(op)
                }
                // A LIVE list with no value yet takes its first entry — and records it.
                if (truthy(def.optsFrom) && !truthy(keep) && list.isNotEmpty()) { n.params[k] = list[0]; inp.value = list[0]; save(); return@fill }
                inp.value = keep ?: list.getOrNull(0) ?: ""
            }
            fill(arr(def.opts))
            // LIVE PICKLIST: optsFrom names a running source; the current value survives, marked stale.
            if (truthy(def.optsFrom)) {
                inp.classList.add("live")
                livePicklist(str(def.optsFrom)).then({ list -> if (list.isNotEmpty()) fill(list.toTypedArray()) }, { })
            }
            inp.value = n.params[k] ?: ""
        } else if (truthy(def.ta)) {
            inp = document.createElement("textarea").asDynamic(); inp.value = n.params[k] ?: ""
            // Size to the text
            inp.rows = maxOf(3, minOf(16, str(n.params[k] ?: "").split("\n").size))
        } else {
            inp = document.createElement("input").asDynamic(); if (truthy(def.ph) && str(def.ph).startsWith("secret:")) inp.type = "password"; inp.value = n.params[k] ?: ""
        }
        if (truthy(def.ph)) inp.placeholder = if (str(def.ph).startsWith("secret:")) str(def.ph).substring(7) else def.ph
        on(inp, "input", {
            n.params[k] = inp.value
            refreshBindingCaption(n)
            if (truthy(n._parentScope) && (n.type == "scope.in" || n.type == "scope.out") && (k == "name" || k == "kind")) {
                refreshRingChrome(n._parentScope); layoutRing(topRingOf(n._parentScope)); redraw()
            }
            save()
        })
        on(inp, "pointerdown", { e -> e.stopPropagation() })
        // any dragged value drops into a plain param too
        on(inp, "dragover", { e -> if ("text/x-lcnc-value" in dragTypes(e)) { e.preventDefault(); e.stopPropagation() } })
        on(inp, "drop", { e ->
            val txt = str(e.dataTransfer.getData("text/x-lcnc-value"))
            if (txt.isNotEmpty()) {
                e.preventDefault(); e.stopPropagation()
                val v: dynamic = try { JSON.parse(txt) } catch (x: dynamic) { txt }
                inp.value = if (typeOf(v) == "string") v else JSON.stringify(v); n.params[k] = inp.value; save()
            }
        })
        ps.appendChild(inp)
    }
    el.appendChild(ps)
    if (n.type == "gauge") {
        val g = document.createElement("div").asDynamic(); g.className = "gaugeval"; g.textContent = "—"; el.appendChild(g)
        val gl = document.createElement("div").asDynamic(); gl.className = "gaugelabel"; gl.style.cssText = "text-align:center;color:var(--dim);font-size:10px;padding-bottom:8px"; el.appendChild(gl)
    }
    if (n.type == "note") el.classList.add("note")
    if (n.type == "program.ref") el.classList.add("ref")
    if (truthy(t.wide)) el.classList.add("wide")
    if (collapsed) el.classList.add("collapsed")
    val frame = nodeFrames.get(str(n.id))
    if (frame != null && frame.type == n.type) { n._frame = obj { it.w = frame.w; it.h = frame.h }; applyNodeFrame(n) }
    val grip = document.createElement("div").asDynamic(); grip.className = "node-resize"
    grip.title = "Resize node"; grip.setAttribute("aria-label", "Resize node")
    on(grip, "pointerdown", { e -> resizeNode(e, n) })
    on(grip, "click", { e -> e.stopPropagation() })
    on(grip, "dblclick", { e -> e.stopPropagation() }); el.appendChild(grip)
    el.style.left = "${n.x}px"; el.style.top = "${n.y}px"
    world.appendChild(el)
    on(hd, "dblclick", { e -> e.stopPropagation(); toggleNodeCollapsed(n, collapse) })
    val sBtn = hd.querySelector(".node-delete")
    on(sBtn, "pointerdown", { e -> e.stopPropagation() })
    on(sBtn, "dblclick", { e -> e.stopPropagation() })
    on(sBtn, "click", { e -> e.stopPropagation(); window.asDynamic().Harness.selectParent(n); removeNode(n.id) })
    /* The BODY is the handle, not the topline. Everything the node draws that is not a control, a port,
       or the ring WINDOW drags it; on the blackboard the drag moves the selected parent. */
    val handsOff = "button,input,textarea,select,option,a,.port,.node-resize,.childgrid,.result,.csheet,.ctree,.kboard,.dagchip,.ref-dive,[contenteditable]"
    on(el, "pointerdown", { e ->
        val offEl = e.target.closest(handsOff)
        // el.contains, because closest() walks past this node into its ancestors
        if (offEl != null && truthy(el.contains(offEl))) return@on
        if (e.target.closest(".node") !== el) return@on // a child inside a ring drags itself
        if (e.button == 0) { val h = window.asDynamic().Harness; h.selectParent(n); h.dragParent(e, if (truthy(n._childHost)) null else n) }
    })
}

/** LIST widget: rows of {col:value}; the param VALUE is the JSON array text; a drop target for dragged values. */
private fun HarnessPatch.listWidget(n: dynamic, k: String, def: dynamic): dynamic {
    val cols = arr(def.cols).map { str(it) }
    val wrap = document.createElement("div").asDynamic(); wrap.className = "plist"
    fun rowsOf(): dynamic = try { val a = JSON.parse<dynamic>(if (truthy(n.params[k])) str(n.params[k]) else "[]"); if (isArray(a)) a else jsArray() } catch (e: dynamic) { jsArray() }
    fun commit(rows: dynamic) { n.params[k] = JSON.stringify(rows); save() }
    lateinit var paint: () -> Unit
    paint = {
        val rows = rowsOf(); wrap.innerHTML = ""
        val head = document.createElement("div").asDynamic(); head.className = "plr plh"
        for (c2 in cols) { val sp = document.createElement("span").asDynamic(); sp.textContent = c2; head.appendChild(sp) }
        head.appendChild(document.createElement("span"))
        wrap.appendChild(head)
        arr(rows).forEachIndexed { ri, row ->
            val r = document.createElement("div").asDynamic(); r.className = "plr"
            for (c2 in cols) {
                val inp2 = document.createElement("input").asDynamic(); inp2.value = row[c2] ?: ""
                on(inp2, "input", { val rs = rowsOf(); if (!truthy(rs[ri])) rs[ri] = jsObject(); rs[ri][c2] = inp2.value; commit(rs) })
                on(inp2, "pointerdown", { e -> e.stopPropagation() })
                r.appendChild(inp2)
            }
            val del = document.createElement("s").asDynamic(); del.textContent = "✕"
            on(del, "pointerdown", { e -> e.stopPropagation() })
            on(del, "click", { val rs = rowsOf(); rs.splice(ri, 1); commit(rs); paint() })
            r.appendChild(del)
            wrap.appendChild(r)
        }
        val add = document.createElement("div").asDynamic(); add.className = "pladd"; add.textContent = "＋ row"
        on(add, "pointerdown", { e -> e.stopPropagation() })
        on(add, "click", { val rs = rowsOf(); rs.push(jsObject()); commit(rs); paint() })
        wrap.appendChild(add)
    }
    on(wrap, "dragover", { e -> if ("text/x-lcnc-value" in dragTypes(e)) { e.preventDefault(); e.stopPropagation(); wrap.classList.add("dropready") } })
    on(wrap, "dragleave", { wrap.classList.remove("dropready") })
    on(wrap, "drop", { e ->
        val txt = str(e.dataTransfer.getData("text/x-lcnc-value"))
        if (txt.isNotEmpty()) {
            e.preventDefault(); e.stopPropagation(); wrap.classList.remove("dropready")
            val v: dynamic = try { JSON.parse(txt) } catch (x: dynamic) { txt }
            val rs = rowsOf()
            fun toRow(o: dynamic): dynamic {
                val row = jsObject()
                if (truthy(o) && typeOf(o) == "object" && !isArray(o)) {
                    for (c2 in cols) if (o[c2] !== undefined) row[c2] = str(o[c2])
                    if (keysOf(row).isEmpty()) { val vals = arr(js("Object.entries(o)")); cols.forEachIndexed { i, c2 -> if (i < vals.size) row[c2] = str(vals[i][1]) } }
                } else cols.forEachIndexed { i, c2 -> if (i == 0) row[c2] = str(o) }
                return row
            }
            if (isArray(v)) for (o in arr(v)) rs.push(toRow(o)) else rs.push(toRow(v))
            commit(rs); paint()
            status("dropped " + (if (isArray(v)) "${v.length} rows" else "a row") + " into " + str(n.type) + "." + k)
        }
    })
    paint()
    return wrap
}

private val picklistCache = JsMap<String, Promise<List<String>>>()

/**
 * Resolve a live picklist "<runner>#<path>": run the node through the ordinary /api/lcnc/run lane, then
 * walk the path out of its outputs (`models[].id` = the id of every element of models). Cached per
 * source for the session; a reload is a refresh.
 */
@OptIn(DelicateCoroutinesApi::class)
fun livePicklist(spec: String): Promise<List<String>> {
    picklistCache.get(spec)?.let { return it }
    val parts = spec.split("#")
    val type = parts.getOrNull(0) ?: ""; val path = parts.getOrNull(1) ?: ""
    if (type.isEmpty() || path.isEmpty()) return Promise.resolve(emptyList())
    val p = GlobalScope.promise {
        val res = api("POST", "/api/lcnc/run", obj { it.type = type; it.inputs = jsObject() })
        if (!truthy(res) || !truthy(res.outputs)) return@promise emptyList<String>()
        var cur: dynamic = res.outputs
        for (seg in path.split(".")) {
            if (cur == null) return@promise emptyList<String>()
            if (seg.endsWith("[]")) {
                val key = seg.dropLast(2)
                cur = if (key.isNotEmpty()) cur[key] else cur
                if (!isArray(cur)) return@promise emptyList<String>()
                val list = cur; cur = obj { it.__list = list }
            } else if (truthy(cur) && truthy(cur.__list)) {
                val list = arr(cur.__list).map { x -> if (truthy(x)) x[seg] else x }.filter { it != null }.toTypedArray()
                cur = obj { it.__list = list }
            } else cur = cur[seg]
        }
        val last: dynamic = cur
        val out: Array<dynamic> = if (truthy(last) && truthy(last.__list)) arr(last.__list) else if (isArray(last)) arr(last) else emptyArray()
        out.map { str(it) }.filter { it.isNotEmpty() }
    }
    picklistCache.set(spec, p)
    return p
}

/** Screen-space speed stays manageable at every fractal zoom level. */
fun wireEdgeVelocity(point: dynamic, rect: dynamic): dynamic {
    val zero = obj { it.x = 0; it.y = 0 }
    if (point == null || !listOf(point.x, point.y, rect.left, rect.right, rect.top, rect.bottom).all { typeOf(it) == "number" && num(it).isFinite() }) return zero
    val px = num(point.x); val py = num(point.y)
    if (px < num(rect.left) || px > num(rect.right) || py < num(rect.top) || py > num(rect.bottom)) return zero
    fun axis(p: Double, lo: Double, hi: Double): Double {
        val margin = minOf(56.0, (hi - lo) / 4); if (margin <= 0) return 0.0
        val near = (lo + margin - p) / margin; val far = (p - hi + margin) / margin
        return if (near > 0) near * near else if (far > 0) -far * far else 0.0
    }
    val x = axis(px, num(rect.left), num(rect.right)); val y = axis(py, num(rect.top), num(rect.bottom)); val scale = 320 / maxOf(1.0, hypot(x, y))
    return obj { it.x = x * scale; it.y = y * scale }
}

/** Drag a wire from a port: kind-equal ports light, candidate splines fan out, the view edge scrolls, release lands or offers mates. */
fun HarnessPatch.startWireDrag(n: dynamic, dir: String, name: String, e: dynamic) {
    if (e.button != 0 || e.isPrimary == false) return
    val previous: dynamic = dragWire
    if (previous != null && previous.cancel != null) previous.cancel()
    camera.killMomentum()
    e.preventDefault(); e.stopPropagation()
    val path = svg("path")
    path.classList.add("drag"); wiresSvg.appendChild(path)
    val dw = obj { it.n = n; it.dir = dir; it.name = name; it.path = path }
    dragWire = dw
    // the canvas states the options: while this wire is in hand, kind-equal ports stay lit
    document.body!!.classList.add("wiring")
    val srcKind = nodeKindOf(n, dir, name)
    fun scopePathOf(m: dynamic): List<String> { val q = ArrayList<String>(); var sc = m._parentScope; while (truthy(sc)) { q.add(0, str(sc.id)); sc = sc._parentScope }; return q }
    val srcScope = scopePathOf(n)
    class Cand(val nd: dynamic, val pe: dynamic, val port: String, val pdir: String, var g: dynamic = null, var b: dynamic = null, val blocked: Boolean = false)
    val cands = ArrayList<Cand>()
    val blocked = ArrayList<Cand>() // kind-compatible, ring-refused — shown, not hidden
    for (nd in nodes()) {
        if (!truthy(nd.el)) continue
        var any = false
        for (pe in arr(nd.el.querySelectorAll(".port"))) {
            if (pe.closest(".node") !== nd.el) continue
            val pdir = str(pe.dataset.dir); val pport = str(pe.dataset.port)
            val kindOk = nd._program == n._program && nd.id != n.id && pdir != dir && kindsCompatible(srcKind, nodeKindOf(nd, pdir, pport))
            // Containment is a SEPARATE question from kind — the scope impedance is shown REFUSED, not hidden.
            var scopeOk = true
            if (kindOk) {
                val dp = scopePathOf(nd)
                scopeOk = if (dir == "out") PatchKinds.reaches(srcScope, dp) else PatchKinds.reaches(dp, srcScope)
            }
            val ok = kindOk && scopeOk
            pe.classList.toggle("mateable", ok)
            pe.classList.toggle("scopeblock", kindOk && !scopeOk)
            if (ok) { any = true; cands.add(Cand(nd, pe, pport, pdir)) }
            else if (kindOk) { any = true; blocked.add(Cand(nd, pe, pport, pdir, blocked = true)) }
        }
        nd.el.classList.toggle("nomate", !any && nd.id != n.id)
    }
    // THE SPLINES: drawing the cable says what you would GET. Ports do not move during a drag.
    val anchor = portCenter(str(n.id), dir, name)
    val ghosts = ArrayList<Cand>()
    if (anchor != null) for (c in cands) {
        val b = portCenter(str(c.nd.id), c.pdir, c.port) ?: continue
        val g = svg("path")
        g.classList.add("cand")
        PatchNavigation.wireCurve(g, if (dir == "out") anchor else b, if (dir == "out") b else anchor, viewport, view)
        val kc = PatchKinds.kindColor[portClass(c.nd.type, c.pdir, c.port)] ?: PatchKinds.kindColor[portClass(n.type, dir, name)]
        if (kc != null) g.style.stroke = kc
        wiresSvg.insertBefore(g, wiresSvg.firstChild) // behind the live cable
        c.g = g; c.b = b; ghosts.add(c)
    }
    if (anchor != null) for (c in blocked) {
        val b = portCenter(str(c.nd.id), c.pdir, c.port) ?: continue
        val g = svg("path")
        g.classList.add("cand", "blocked")
        PatchNavigation.wireCurve(g, if (dir == "out") anchor else b, if (dir == "out") b else anchor, viewport, view)
        wiresSvg.insertBefore(g, wiresSvg.firstChild)
        c.g = g; c.b = b; ghosts.add(c)
    }
    if (blocked.isNotEmpty()) status("${blocked.size} kind-compatible port" + (if (blocked.size == 1) " is" else "s are") +
        " out of ring — data flows lateral or inward; thread through scope.in/scope.out")
    // THE SHAKE: when exactly one legal mate survives, say so now.
    val live = ghosts.filter { !it.blocked }
    val only = if (live.size == 1) live[0] else null
    if (only != null) {
        only.g.classList.add("only")
        only.pe.classList.add("mate-only")
        status("one legal mate: " + str(n.type) + "." + name + " → " + str(only.nd.type) + "." + only.port)
    }
    var px = num(e.clientX); var py = num(e.clientY)
    var frame = 0; var last = window.performance.now(); var finished = false; var movedCamera = false
    val harness = window.asDynamic().Harness
    val revision = harness.parentRevision
    fun stale(): Boolean = !nodes().contains(n) || !truthy(n.el.isConnected) || harness.parentRevision != revision
    fun bounds(): dynamic {
        val r = viewport.getBoundingClientRect()
        return obj { it.left = maxOf(0.0, r.left); it.top = maxOf(0.0, r.top); it.right = minOf(window.innerWidth.toDouble(), r.right); it.bottom = minOf(window.innerHeight.toDouble(), r.bottom) }
    }
    fun inside(): Boolean { val r = bounds(); return px >= num(r.left) && px <= num(r.right) && py >= num(r.top) && py <= num(r.bottom) }
    fun paint() {
        val w = world.getBoundingClientRect()
        val m = obj { it.x = (px - w.left) / num(view.z); it.y = (py - w.top) / num(view.z) }
        val a = portCenter(str(n.id), dir, name) ?: m
        if (growWireBox(listOf(a, m))) applyWireBox()
        PatchNavigation.wireCurve(path, if (dir == "out") a else m, if (dir == "out") m else a, viewport, view)
        // the nearest candidate leads: its spline brightens as you approach
        var best: Cand? = null; var bd = Double.POSITIVE_INFINITY
        for (gh in ghosts) {
            if (gh.blocked) continue // never lead the eye to a refusal
            val dx = num(gh.b.x) - num(m.x); val dy = num(gh.b.y) - num(m.y); val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = gh }
        }
        for (gh in ghosts) gh.g.classList.toggle("near", gh === best && bd < 220 * 220)
    }
    lateinit var mv: (dynamic) -> Unit
    lateinit var up: (dynamic) -> Unit
    lateinit var cancelPointer: (dynamic) -> Unit
    lateinit var key: (dynamic) -> Unit
    lateinit var cancelEvt: (dynamic) -> Unit
    lateinit var visibility: (dynamic) -> Unit
    fun cleanup() {
        if (finished) return; finished = true; window.cancelAnimationFrame(frame)
        off(window, "pointermove", mv); off(window, "pointerup", up)
        off(window, "pointercancel", cancelPointer); off(window, "keydown", key); off(window, "blur", cancelEvt)
        off(document, "visibilitychange", visibility)
        path.remove()
        document.body!!.classList.remove("wiring")
        for (x in arr(document.querySelectorAll(".port.mateable"))) x.classList.remove("mateable")
        for (x in arr(document.querySelectorAll(".port.scopeblock"))) x.classList.remove("scopeblock")
        for (x in arr(document.querySelectorAll(".port.mate-only"))) x.classList.remove("mate-only")
        for (x in arr(document.querySelectorAll(".node.nomate"))) x.classList.remove("nomate")
        for (gh in ghosts) gh.g.remove()
        dragWire = null; camera.killMomentum(); if (movedCamera) camera.saveCameraSoon()
    }
    fun cancel() { cleanup(); redraw() }
    cancelEvt = { cancel() }
    cancelPointer = { ev -> if (ev.pointerId == e.pointerId) cancel() }
    key = { ev -> if (ev.key == "Escape") { ev.preventDefault(); cancel() } }
    visibility = { if (document.asDynamic().hidden == true) cancel() }
    mv = { ev ->
        if (ev.pointerId == e.pointerId) {
            if (stale() || (num(ev.buttons).toInt() and 1) == 0) cancel()
            else { px = num(ev.clientX); py = num(ev.clientY); paint() }
        }
    }
    lateinit var tick: (Double) -> Unit
    tick = { now ->
        if (!finished) {
            if (stale() || document.asDynamic().hidden == true) cancel()
            else {
                val dt = maxOf(0.0, minOf(50.0, now - last)) / 1000; last = now
                val velocity = wireEdgeVelocity(obj { it.x = px; it.y = py }, bounds())
                if ((num(velocity.x) != 0.0 || num(velocity.y) != 0.0) && truthy(viewport.contains(document.elementFromPoint(px, py)))) {
                    view.x = num(view.x) + num(velocity.x) * dt; view.y = num(view.y) + num(velocity.y) * dt; movedCamera = true
                    camera.killMomentum(); camera.applyView(); paint()
                }
                frame = window.requestAnimationFrame(tick)
            }
        }
    }
    up = up@{ ev ->
        if (ev.pointerId != e.pointerId) return@up
        px = num(ev.clientX); py = num(ev.clientY)
        if (stale() || !inside()) { cancel(); return@up }
        cleanup()
        val tgt: dynamic = document.elementFromPoint(num(ev.clientX), num(ev.clientY))
        if (!truthy(viewport.contains(tgt))) { redraw(); return@up }
        // Highlighting HIGHLIGHTS: auto-merge lives in the treeshake, where it is the thing you pressed.
        if (tgt != null && truthy(tgt.classList.contains("port")) && tgt.dataset.dir != dir) {
            // the port's OWNING node is its nearest .node ancestor
            val owner = tgt.closest(".node")
            val tn = if (owner != null) nodes().firstOrNull { it.el === owner } else null
            if (tn != null && tn.id != n.id) {
                val src = if (dir == "out") n else tn; val dst = if (dir == "out") tn else n
                val from = if (dir == "out") arrayOf<dynamic>(n.id, name) else arrayOf<dynamic>(tn.id, tgt.dataset.port)
                val to = if (dir == "out") arrayOf<dynamic>(tn.id, tgt.dataset.port) else arrayOf<dynamic>(n.id, name)
                // ENFORCED kinds: a mismatched wire is refused here, not discovered at run time.
                val sk = nodeKindOf(src, "out", str(from[1])); val dk = nodeKindOf(dst, "in", str(to[1]))
                // the daemon's own rule: data flows lateral or inward, never outward
                val lateralOrInward = PatchKinds.reaches(scopePathOf(src), scopePathOf(dst))
                if (src._program != dst._program) status("Different program scopes: connect through a program.ref node")
                else if (!kindsCompatible(sk, dk)) status("kind mismatch: " + str(src.type) + "." + str(from[1]) + " emits " + (sk ?: "untyped") + ", " + str(dst.type) + "." + str(to[1]) + " wants " + (dk ?: "untyped"))
                else if (!lateralOrInward) status("ring boundary: data flows lateral or inward — yields leave through scope.out")
                else {
                    graph.wires = arr(graph.wires).filter { w -> !(w.to[0] == to[0] && w.to[1] == to[1]) }.toTypedArray() // one wire per input
                    graph.wires.push(obj { it.from = from; it.to = to }); save()
                }
            }
        } else {
            // released on EMPTY context: the MATE POPUP, both directions
            val w = world.getBoundingClientRect()
            val wx = (num(ev.clientX) - w.left) / num(view.z); val wy = (num(ev.clientY) - w.top) / num(view.z)
            val scope = scopeAtTarget(tgt)
            launchJs { showMateMenu(num(ev.clientX), num(ev.clientY), n, name, wx, wy, scope, dir) }
        }
        redraw()
    }
    dw.cancel = { cancel() }
    dw.repaint = {
        for (gh in ghosts) {
            gh.b = portCenter(str(gh.nd.id), gh.pdir, gh.port) ?: gh.b
            val a = portCenter(str(n.id), dir, name)
            if (a != null) PatchNavigation.wireCurve(gh.g, if (dir == "out") a else gh.b, if (dir == "out") gh.b else a, viewport, view)
            wiresSvg.appendChild(gh.g)
        }
        wiresSvg.appendChild(path); paint()
    }
    on(window, "pointermove", mv); on(window, "pointerup", up)
    on(window, "pointercancel", cancelPointer); on(window, "keydown", key); on(window, "blur", cancelEvt)
    on(document, "visibilitychange", visibility)
    paint(); frame = window.requestAnimationFrame(tick)
}

/**
 * The mate popup: release a wire on empty canvas and the daemon offers the kind-compatible
 * continuations (evidence-ordered); from an input it backchains every type whose output kind mates.
 */
suspend fun HarnessPatch.showMateMenu(cx: Double, cy: Double, srcNode: dynamic, srcPort: String, wx: Double, wy: Double, scope: dynamic, dir: String) {
    val all = ArrayList<dynamic>()
    if (dir != "in") {
        // A ring port's kind lives in the NODE (its scope.out child): the surface that knows resolves it.
        val srcKind = nodeKindOf(srcNode, "out", srcPort) ?: ""
        val mq = "/api/lcnc/mating-options?sourceType=" + encodeURIComponent(str(srcNode.type)) + "&sourcePort=" + encodeURIComponent(srcPort.replaceFirst("?", "")) +
            "&kind=" + encodeURIComponent(srcKind) + "&q="
        var generic = false
        try { val r = api("GET", mq); all.addAll(arr(r.options)); generic = truthy(r.generic) }
        catch (x: CancellationException) { throw x } catch (x: dynamic) { status("mate options failed: " + str(x)); return }
        if (generic) status(str(srcNode.type) + "." + srcPort + " is a generic ring parameter — every kind mates; declare a kind on its scope.out to narrow")
    } else {
        // BACKCHAIN: from an input, offer every type whose output kind equals it
        val want = nodeKindOf(srcNode, "in", srcPort)
        if (want == null) { status(str(srcNode.type) + "." + srcPort + " is untyped — it mates with nothing"); return }
        if (want == "*") status(str(srcNode.type) + "." + srcPort + " is a generic ring parameter — every kind mates; declare a kind on its scope.in to narrow")
        for (t in keysOf(contracts).sorted()) {
            val c = contracts[t]
            for (op in arr(c.outs)) if (kindsCompatible(kindOf(t, "out", str(op)), want)) all.add(obj { it.type = t; it.outputPort = op; it.title = c.title })
        }
    }
    if (all.isEmpty()) { status("no kind-compatible mate for " + str(srcNode.type) + "." + srcPort); return }
    menu.innerHTML = "<h5>" + (if (dir == "in") "backchain " else "mate ") + str(srcNode.type) + "." + srcPort + (if (scope != null) " → into ring " + str(scope.id) else "") + "</h5>"
    val qIn = document.createElement("input").asDynamic(); qIn.placeholder = "filter mates…"
    val box = document.createElement("div").asDynamic(); box.className = "items"
    suspend fun pick(opt: dynamic) {
        menu.style.display = "none"
        val nn = (if (scope != null) addChildNode(scope, str(opt.type)) else addNode(str(opt.type), wx - 8, wy - 8)) ?: return
        if (dir == "in") {
            graph.wires = arr(graph.wires).filter { x -> !(x.to[0] == srcNode.id && x.to[1] == srcPort) }.toTypedArray()
            graph.wires.push(obj { it.from = arrayOf<dynamic>(nn.id, opt.outputPort); it.to = arrayOf<dynamic>(srcNode.id, srcPort) })
        } else {
            graph.wires = arr(graph.wires).filter { x -> !(x.to[0] == nn.id && x.to[1] == opt.inputPort) }.toTypedArray()
            graph.wires.push(obj { it.from = arrayOf<dynamic>(srcNode.id, srcPort); it.to = arrayOf<dynamic>(nn.id, opt.inputPort) })
        }
        try {
            val f = api("GET", "/api/lcnc/fills?type=" + encodeURIComponent(str(opt.type)))
            val best = jsObject()
            for (fl in arr(f.fills)) if (!js("fl.param in best").unsafeCast<Boolean>()) best[fl.param] = fl.value
            for (lab in arr(nn.el.querySelectorAll(".params label"))) {
                val k = lab.textContent
                if (best[k] !== undefined) {
                    nn.params[k] = best[k]
                    val inp = lab.nextElementSibling; if (inp != null && js("'value' in inp").unsafeCast<Boolean>()) inp.value = best[k]
                }
            }
        } catch (x: CancellationException) { throw x } catch (x: dynamic) {}
        redraw(); save()
        status("mated " + str(srcNode.type) + "." + srcPort + " ↔ " + str(opt.type) + "." + str(if (truthy(opt.inputPort)) opt.inputPort else opt.outputPort))
    }
    fun render(f: String) {
        box.innerHTML = ""
        for (o in all) {
            if (f.isNotEmpty() && !str(o.type).contains(f) && !str(o.title ?: "").lowercase().contains(f)) continue
            val d = document.createElement("div").asDynamic(); d.className = "item"
            fun esc(x: dynamic): String = borg.trikeshed.web.patch.PatchHtml.attr(if (x == null) "" else str(x))
            d.innerHTML = "<span class=\"ty\">" + esc(o.type) + "</span> <span class=\"pt\">·&nbsp;" +
                esc(if (truthy(o.inputPort)) o.inputPort else o.outputPort) + "</span>" +
                (if (truthy(o.title)) "<span class=\"ti\">" + esc(o.title) + "</span>" else "")
            on(d, "click", { launchJs { pick(o) } })
            box.appendChild(d)
        }
    }
    on(qIn, "input", { render(str(qIn.value).trim().lowercase()) })
    on(qIn, "keydown", { ev -> if (ev.key == "Enter") { val first = box.querySelector(".item"); if (first != null) first.click() } })
    menu.appendChild(qIn); menu.appendChild(box); render("")
    positionCreationMenu(cx, cy)
    window.setTimeout({ qIn.focus() }, 0)
}

fun HarnessPatch.positionCreationMenu(cx: Double, cy: Double) {
    menu.style.display = "block"
    menu.style.left = "${maxOf(8.0, minOf(cx, window.innerWidth - menu.offsetWidth - 8.0))}px"
    menu.style.top = "${maxOf(8.0, minOf(cy, window.innerHeight - menu.offsetHeight - 8.0))}px"
}

/** the add-node menu; a ring target adds INTO the ring — the concentric authoring gesture */
fun HarnessPatch.showMenu(cx: Double, cy: Double, intoScope: dynamic = null) {
    menu.innerHTML = if (intoScope != null) "<h5>add into ring " + str(intoScope.id) + "</h5>" else "<h5>add node</h5>"
    val qIn = document.createElement("input").asDynamic(); qIn.placeholder = "search nodes…"
    val box = document.createElement("div").asDynamic(); box.className = "items"
    fun place(k: String) {
        menu.style.display = "none"
        if (intoScope != null) { addChildNode(intoScope, k); return }
        // client → world: subtract the viewport origin and the pan, divide by zoom
        val vr = viewport.getBoundingClientRect()
        addNode(k, (cx - vr.left - num(view.x)) / num(view.z), (cy - vr.top - num(view.y)) / num(view.z))
    }
    fun render(filter: String) {
        box.innerHTML = ""
        for (k in keysOf(contracts).sorted()) {
            val c = contracts[k]
            if (filter.isNotEmpty() && !k.contains(filter) && !str(c.title).lowercase().contains(filter)) continue
            val d = document.createElement("div").asDynamic(); d.className = "item"; d.textContent = k + "  ·  " + str(c.title)
            on(d, "click", { place(k) })
            box.appendChild(d)
        }
    }
    on(qIn, "input", { render(str(qIn.value).trim().lowercase()) })
    on(qIn, "keydown", { ev -> if (ev.key == "Enter") { val first = box.querySelector(".item"); if (first != null) first.click() } })
    menu.appendChild(qIn); menu.appendChild(box); render("")
    positionCreationMenu(cx, cy)
    window.setTimeout({ qIn.focus() }, 0)
}
