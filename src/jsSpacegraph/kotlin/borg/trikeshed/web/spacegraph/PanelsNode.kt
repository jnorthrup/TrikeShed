package borg.trikeshed.web.spacegraph

import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchKinds
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async

private fun types(e: dynamic): List<String> = arr(js("Array.from(e.dataTransfer.types)")).map { str(it) }

/** Build a node's DOM: header, ring window or lane captions, ports, param editors, drag handle. */
fun Panels.buildNode(n: dynamic) {
    val t = contract(n.type) ?: return // vocabulary is server-owned
    val el = document.createElement("div").asDynamic(); el.className = "node"; el.dataset.id = n.id; el.dataset.type = n.type; n.el = el
    // CONCENTRIC CONTEXT: a scope node IS a ring square; its children render INSIDE it.
    val isScope = n.type == "scope" || (truthy(n.children) && num(n.children.length) > 0)
    if (isScope) {
        el.classList.add("scope")
        val lane = laneOf(n)
        if (lane != null) el.dataset.lane = lane.id
    }
    val hd = document.createElement("div").asDynamic(); hd.className = "hd"
    hd.innerHTML = "<i></i><b>${t.title}</b><s role=\"button\" tabindex=\"0\" aria-label=\"Collapse node\" title=\"collapse\">▤</s><s role=\"button\" tabindex=\"0\" aria-label=\"Delete node\" title=\"delete\">✕</s>"
    el.appendChild(hd)
    // the hover blip: asserted / inferred / graal for THIS node, from the daemon
    on(el, "pointerenter", { e -> blip.enter(n, e) })
    on(el, "pointerleave", { e -> blip.leave(e) })
    on(el, "pointermove", { e -> blip.move(e) })
    on(el, "pointerdown", { e -> blip.leave(e) })
    val collapse = hd.querySelectorAll("s")[0]
    on(collapse, "pointerdown", { e -> e.stopPropagation() })
    on(collapse, "click", { e -> e.stopPropagation(); n.collapsed = el.classList.toggle("collapsed"); save() })
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
        n._childHost = grid; n._ringWorld = rw; n._view = objOf { it.x = 0; it.y = 0; it.z = 1 }
        // Let the shared camera traverse the nest. Resizing still owns its corner.
        on(grid, "pointerdown", { ev ->
            val r = grid.getBoundingClientRect()
            if (ev.target === grid && num(ev.clientX) > num(r.right) - 20 && num(ev.clientY) > num(r.bottom) - 20) ev.stopPropagation()
        })
        on(lbl, "dblclick", { ev ->
            ev.stopPropagation()
            camera.killMomentum()
            val r = n.el.getBoundingClientRect(); val vp = viewport.getBoundingClientRect()
            val z0 = num(view.z)
            val x = (num(r.left) - vp.left - num(view.x)) / z0; val y = (num(r.top) - vp.top - num(view.y)) / z0
            val w = num(r.width) / z0; val h = num(r.height) / z0; val scale = num(r.width) / num(n.el.offsetWidth) / z0
            view.z = maxOf(LandscapeNavigation.minZoom, minOf(LandscapeNavigation.maxZoom(scale), (vp.width - 60) / w, (vp.height - 60) / h))
            view.x = (vp.width - w * num(view.z)) / 2 - x * num(view.z); view.y = (vp.height - h * num(view.z)) / 2 - y * num(view.z)
            applyView(); camera.saveCameraSoon()
        })
    } else {
        // leaf nodes name their Key factory's upstream state and hand-off
        val lane = laneOf(n)
        if (lane != null) el.dataset.lane = lane.id
        val seed = document.createElement("div").asDynamic(); seed.className = "seed"; seed.textContent = if (lane != null) lane.seed else t.title
        val emit = document.createElement("div").asDynamic(); emit.className = "emit"; emit.textContent = if (lane != null) lane.emit else arr(t.outs).joinToString(", ") { str(it) }
        el.appendChild(seed); el.appendChild(emit)
    }
    val body = document.createElement("div").asDynamic(); body.className = "body"
    for (name in arr(t.ins).map { str(it) }) {
        val kd = kindOf(n.type, "in", name)
        val r = document.createElement("div").asDynamic(); r.className = "row in"
        r.innerHTML = "<span class=\"port ${portClass(n.type, "in", name)}\" data-dir=\"in\" data-port=\"$name\" data-kind=\"${kd ?: ""}\" title=\"$name: ${kd ?: "untyped — mates with nothing"}\"></span>$name"
        body.appendChild(r)
    }
    for (name in arr(t.outs).map { str(it) }) {
        val kd = kindOf(n.type, "out", name)
        val r = document.createElement("div").asDynamic(); r.className = "row out"
        r.innerHTML = "$name<span class=\"port ${portClass(n.type, "out", name)}\" data-dir=\"out\" data-port=\"$name\" data-kind=\"${kd ?: ""}\" title=\"$name: ${kd ?: "untyped — mates with nothing"}\"></span>"
        body.appendChild(r)
    }
    el.appendChild(body)
    val ps = document.createElement("div").asDynamic(); ps.className = "params"
    for (k in keysOf(t.params)) {
        val def = t.params[k]
        if (n.params[k] === undefined) n.params[k] = def.v ?: "" // loaded docs may omit params
        val lab = document.createElement("label").asDynamic(); lab.textContent = k; ps.appendChild(lab)
        if (truthy(def.cols) && num(def.cols.length) > 0) { ps.appendChild(listWidget(n, k, def)); continue }
        val inp: dynamic
        if (truthy(def.opts) || truthy(def.optsFrom)) {
            inp = document.createElement("select").asDynamic()
            lateinit var fill: (Array<dynamic>) -> Unit
            fill = { list ->
                val keep = n.params[k]; inp.textContent = ""
                for (o in list) { val op = document.createElement("option").asDynamic(); op.value = o; op.textContent = o; inp.appendChild(op) }
                if (truthy(keep) && list.none { it == keep }) {
                    val op = document.createElement("option").asDynamic(); op.value = keep; op.textContent = keep; op.dataset.stale = "1"; inp.appendChild(op)
                }
                // A LIVE list with no value yet takes its first entry — and records it.
                if (truthy(def.optsFrom) && !truthy(keep) && list.isNotEmpty()) { n.params[k] = list[0]; inp.value = list[0]; save() }
                else inp.value = keep ?: list.getOrNull(0) ?: ""
            }
            fill(arr(def.opts))
            // LIVE PICKLIST: optsFrom names a running source; the current value survives, marked stale.
            if (truthy(def.optsFrom)) {
                inp.classList.add("live")
                launchJs { try { val list = livePicklist(str(def.optsFrom), n); if (list.isNotEmpty()) fill(list.toTypedArray()) } catch (e: CancellationException) { throw e } catch (e: dynamic) {} }
            }
            inp.value = n.params[k]
        } else if (truthy(def.ta)) {
            inp = document.createElement("textarea").asDynamic(); inp.value = n.params[k]
            // Size to the text
            inp.rows = maxOf(3, minOf(16, str(n.params[k] ?: "").split("\n").size))
        } else {
            inp = document.createElement("input").asDynamic(); if (truthy(def.ph) && str(def.ph).startsWith("secret:")) inp.type = "password"; inp.value = n.params[k]
        }
        if (truthy(def.ph)) inp.placeholder = if (str(def.ph).startsWith("secret:")) str(def.ph).substring(7) else def.ph
        on(inp, "input", { n.params[k] = inp.value; save() })
        on(inp, "pointerdown", { e -> e.stopPropagation() })
        // any dragged value drops into a plain param too
        on(inp, "dragover", { e -> if ("text/x-lcnc-value" in types(e)) { e.preventDefault(); e.stopPropagation() } })
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
    if (truthy(n.collapsed)) el.classList.add("collapsed")
    el.style.left = "${n.x}px"; el.style.top = "${n.y}px"
    world.appendChild(el)
    on(hd, "dblclick", { e -> e.stopPropagation(); n.collapsed = !truthy(n.collapsed); el.classList.toggle("collapsed", n.collapsed); save() })
    val sBtn = hd.querySelector("s")
    on(sBtn, "click", { removeNode(n.id) })
    on(sBtn, "keydown", { e -> if (e.key == "Enter" || e.key == " ") { e.preventDefault(); removeNode(n.id) } })
    /* The BODY is the handle, not the topline. Everything the node draws that is not a control, a port,
       or the ring WINDOW drags it. */
    val handsOff = "button,input,textarea,select,option,a,s,.port,.node-resize,.childgrid,.result,.csheet,.ctree,.kboard,.dagchip,.ref-dive,[contenteditable]"
    on(el, "pointerdown", { e ->
        // el.contains, because closest() walks past this node into its ancestors
        val offEl = e.target.closest(handsOff)
        if (offEl != null && truthy(el.contains(offEl))) return@on
        if (e.target.closest(".node") !== el) return@on // a child inside a ring drags itself
        e.stopPropagation()
        val sx = num(e.clientX); val sy = num(e.clientY); val ox = num(n.x); val oy = num(n.y)
        val sc = num(view.z) * ringScaleOf(n) // a child in a zoomed ring drags in RING units
        lateinit var mv: (dynamic) -> Unit
        lateinit var up: (dynamic) -> Unit
        mv = { ev ->
            n.x = ox + (num(ev.clientX) - sx) / sc; n.y = oy + (num(ev.clientY) - sy) / sc
            // a ring CONTAINS its children: past the top/left edge there is only clipping, so the edge holds
            if (truthy(n._parentScope)) { n.x = maxOf(0.0, num(n.x)); n.y = maxOf(0.0, num(n.y)); growRingWorldFor(n) }
            el.style.left = "${n.x}px"; el.style.top = "${n.y}px"; redraw()
        }
        up = { off(window, "pointermove", mv); off(window, "pointerup", up); save() }
        on(window, "pointermove", mv); on(window, "pointerup", up)
    })
    // effect nodes carry the shape on every one of their ports
    if (truthy(contract(n.type)?.effect)) for (pe in arr(el.querySelectorAll(".port"))) pe.classList.add("fx")
    for (port in arr(el.querySelectorAll(".port"))) {
        on(port, "pointerdown", { e ->
            e.stopPropagation(); e.preventDefault()
            startWireDrag(n, str(port.dataset.dir), str(port.dataset.port), e)
        })
    }
}

/** LIST widget: rows of {col:value}; the param VALUE is the JSON array text; a drop target for dragged values. */
private fun Panels.listWidget(n: dynamic, k: String, def: dynamic): dynamic {
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
                on(inp2, "input", { val rs = rowsOf(); if (!truthy(rs[ri])) rs[ri] = obj(); rs[ri][c2] = inp2.value; commit(rs) })
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
        on(add, "click", { val rs = rowsOf(); rs.push(obj()); commit(rs); paint() })
        wrap.appendChild(add)
    }
    on(wrap, "dragover", { e -> if ("text/x-lcnc-value" in types(e)) { e.preventDefault(); e.stopPropagation(); wrap.classList.add("dropready") } })
    on(wrap, "dragleave", { wrap.classList.remove("dropready") })
    on(wrap, "drop", { e ->
        val txt = str(e.dataTransfer.getData("text/x-lcnc-value"))
        if (txt.isNotEmpty()) {
            e.preventDefault(); e.stopPropagation(); wrap.classList.remove("dropready")
            val v: dynamic = try { JSON.parse(txt) } catch (x: dynamic) { txt }
            val rs = rowsOf()
            fun toRow(o: dynamic): dynamic {
                val row = obj()
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

/** Drag a wire from a port: kind-equal ports light, candidate splines fan out, release lands or offers mates. */
fun Panels.startWireDrag(n: dynamic, dir: String, name: String, @Suppress("UNUSED_PARAMETER") e: dynamic) {
    val path = svg("path")
    path.classList.add("drag"); wiresSvg.appendChild(path)
    document.body!!.classList.add("wiring")
    val srcKind = nodeKindOf(n, dir, name)
    val srcScope = scopePath(n)
    class Cand(val nd: dynamic, val pe: dynamic, val port: String, val pdir: String, var g: dynamic = null, var b: dynamic = null, val blocked: Boolean = false)
    val cands = ArrayList<Cand>()
    val blocked = ArrayList<Cand>() // kind-compatible, ring-refused — shown, not hidden
    for (nd in nodes()) {
        if (!truthy(nd.el)) continue
        var any = false
        for (pe in arr(nd.el.querySelectorAll(".port"))) {
            if (pe.closest(".node") !== nd.el) continue
            val pdir = str(pe.dataset.dir); val pport = str(pe.dataset.port)
            val kindOk = nd.id != n.id && pdir != dir && kindsCompatible(srcKind, nodeKindOf(nd, pdir, pport))
            // Containment is a SEPARATE question from kind — the scope impedance is shown REFUSED, not hidden.
            var scopeOk = true
            if (kindOk) {
                val dp = scopePath(nd)
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
    lateinit var mv: (dynamic) -> Unit
    lateinit var up: (dynamic) -> Unit
    mv = { ev ->
        val w = world.getBoundingClientRect()
        val m = obj(); m.x = (num(ev.clientX) - w.left) / num(view.z); m.y = (num(ev.clientY) - w.top) / num(view.z)
        val a = portCenter(str(n.id), dir, name) ?: m
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
    up = { ev ->
        off(window, "pointermove", mv); off(window, "pointerup", up)
        path.remove()
        document.body!!.classList.remove("wiring")
        for (x in arr(document.querySelectorAll(".port.mateable"))) x.classList.remove("mateable")
        for (x in arr(document.querySelectorAll(".port.scopeblock"))) x.classList.remove("scopeblock")
        for (x in arr(document.querySelectorAll(".port.mate-only"))) x.classList.remove("mate-only")
        for (x in arr(document.querySelectorAll(".node.nomate"))) x.classList.remove("nomate")
        for (gh in ghosts) gh.g.remove()
        val tgt = document.elementFromPoint(num(ev.clientX), num(ev.clientY)).asDynamic()
        // Highlighting HIGHLIGHTS: a lone legal mate is pointed at, never auto-landed.
        if (tgt != null && truthy(tgt.classList.contains("port")) && tgt.dataset.dir != dir) {
            // the port's OWNING node is its nearest .node ancestor
            val owner = tgt.closest(".node")
            val tn = if (owner != null) nodes().firstOrNull { it.el === owner } else null
            if (tn != null && tn.id != n.id) {
                val from = if (dir == "out") arrayOf<dynamic>(n.id, name) else arrayOf<dynamic>(tn.id, tgt.dataset.port)
                val to = if (dir == "out") arrayOf<dynamic>(tn.id, tgt.dataset.port) else arrayOf<dynamic>(n.id, name)
                connectPanelPorts(from, to)
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
    on(window, "pointermove", mv); on(window, "pointerup", up)
}

/** the mate popup: release a wire on empty canvas and the daemon offers the kind-compatible continuations */
suspend fun Panels.showMateMenu(cx: Double, cy: Double, srcNode: dynamic, srcPort: String, wx: Double, wy: Double, scope: dynamic, dir: String) {
    val all = ArrayList<dynamic>()
    if (dir != "in") {
        // A ring port's kind lives in the NODE (its scope.out child), not in the `scope` contract.
        val srcKind = nodeKindOf(srcNode, "out", srcPort) ?: ""
        val mq = "/api/lcnc/mating-options?sourceType=" + encodeURIComponent(str(srcNode.type)) + "&sourcePort=" + encodeURIComponent(PatchKinds.bare(srcPort)) +
            "&kind=" + encodeURIComponent(srcKind) + "&q="
        var generic = false
        try { val r = api("GET", mq); all.addAll(arr(r.options)); generic = truthy(r.generic) }
        catch (e: CancellationException) { throw e }
        catch (e: dynamic) { status("mate options failed: " + str(e)); return }
        if (generic) status(str(srcNode.type) + "." + srcPort + " is a generic ring parameter — every kind mates; declare a kind on its scope.out to narrow")
    } else {
        // BACKCHAIN: from an input, offer every type whose output kind equals it
        val want = nodeKindOf(srcNode, "in", srcPort)
        if (want == null) { status(str(srcNode.type) + "." + srcPort + " is untyped — it mates with nothing"); return }
        if (want == "*") status(str(srcNode.type) + "." + srcPort + " is a generic ring parameter — every kind mates; declare a kind on its scope.in to narrow")
        for (t in keysOf(contracts).sorted()) {
            val c = contracts[t]
            for (op in arr(c.outs)) if (kindsCompatible(kindOf(t, "out", str(op)), want)) {
                val o = obj(); o.type = t; o.outputPort = op; o.title = c.title; all.add(o)
            }
        }
    }
    if (all.isEmpty()) { status("no kind-compatible mate for " + str(srcNode.type) + "." + srcPort); return }
    menu.innerHTML = "<h5>" + (if (dir == "in") "backchain " else "mate ") + str(srcNode.type) + "." + srcPort + (if (scope != null) " → into ring " + str(scope.id) else "") + "</h5>"
    val qIn = document.createElement("input").asDynamic(); qIn.placeholder = "filter mates…"
    val box = document.createElement("div").asDynamic(); box.className = "items"
    suspend fun pick(opt: dynamic) {
        menu.style.display = "none"
        val nn = if (scope != null) addChildNode(scope, str(opt.type)) else addNode(str(opt.type), wx - 8, wy - 8)
        if (nn == null) return
        if (dir == "in") {
            graph.wires = arr(graph.wires).filter { x -> !(x.to[0] == srcNode.id && x.to[1] == srcPort) }.toTypedArray()
            graph.wires.push(wire(nn.id, opt.outputPort, srcNode.id, srcPort))
        } else {
            graph.wires = arr(graph.wires).filter { x -> !(x.to[0] == nn.id && x.to[1] == opt.inputPort) }.toTypedArray()
            graph.wires.push(wire(srcNode.id, srcPort, nn.id, opt.inputPort))
        }
        try {
            val f = api("GET", "/api/lcnc/fills?type=" + encodeURIComponent(str(opt.type)))
            val best = obj()
            for (fl in arr(f.fills)) if (!js("fl.param in best").unsafeCast<Boolean>()) best[fl.param] = fl.value
            for (lab in arr(nn.el.querySelectorAll(".params label"))) {
                val k = str(lab.textContent)
                if (best[k] !== undefined) {
                    nn.params[k] = best[k]
                    val inp = lab.nextElementSibling; if (inp != null && js("'value' in inp").unsafeCast<Boolean>()) inp.value = best[k]
                }
            }
        } catch (e: CancellationException) { throw e } catch (e: dynamic) {}
        redraw(); save()
        status("mated " + str(srcNode.type) + "." + srcPort + " ↔ " + str(opt.type) + "." + str(if (truthy(opt.inputPort)) opt.inputPort else opt.outputPort))
    }
    fun render(f: String) {
        box.innerHTML = ""
        for (o in all) {
            if (f.isNotEmpty() && !str(o.type).contains(f) && !str(o.title ?: "").lowercase().contains(f)) continue
            val d = document.createElement("div").asDynamic(); d.className = "item"
            d.innerHTML = "<span class=\"ty\">" + escNullable(o.type) + "</span> <span class=\"pt\">·&nbsp;" +
                escNullable(if (truthy(o.inputPort)) o.inputPort else o.outputPort) + "</span>" +
                (if (truthy(o.title)) "<span class=\"ti\">" + escNullable(o.title) + "</span>" else "")
            on(d, "click", { launchJs { pick(o) } })
            box.appendChild(d)
        }
    }
    on(qIn, "input", { render(str(qIn.value).trim().lowercase()) })
    on(qIn, "keydown", { e -> if (e.key == "Enter") { val first = box.querySelector(".item"); if (first != null) first.click() } })
    menu.appendChild(qIn); menu.appendChild(box); render("")
    menu.style.left = "${cx}px"; menu.style.top = "${cy}px"; menu.style.display = "block"
    later(0) { qIn.focus() }
}

/** add-node menu; a ring target adds INTO the ring — the concentric authoring gesture */
fun Panels.showMenu(cx: Double, cy: Double, intoScope: dynamic = null) {
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
    on(qIn, "keydown", { e -> if (e.key == "Enter") { val first = box.querySelector(".item"); if (first != null) first.click() } })
    menu.appendChild(qIn); menu.appendChild(box); render("")
    menu.style.left = "${cx}px"; menu.style.top = "${cy}px"; menu.style.display = "block"
    later(0) { qIn.focus() }
}

/**
 * SCOPED live picklist. `spec` is "<type>#<path>" or "<type>?k=v&k2=$other#<path>": the query is sent as
 * the runner's params, and a `$name` value is read from THIS node's params. Cached per resolved source.
 */
private val picklistCache = HashMap<String, kotlinx.coroutines.Deferred<List<String>>>()

suspend fun livePicklist(spec: String, node: dynamic): List<String> {
    val hash = spec.indexOf('#')
    if (hash < 0) return emptyList()
    val head = spec.substring(0, hash); val path = spec.substring(hash + 1)
    val qi = head.indexOf('?')
    val type = if (qi < 0) head else head.substring(0, qi)
    val params = obj()
    if (qi >= 0) for (pair in head.substring(qi + 1).split("&")) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('='); if (eq < 0) continue
        val key = decodeURIComponent(pair.substring(0, eq))
        var v = decodeURIComponent(pair.substring(eq + 1))
        if (v.startsWith("$")) { val pv = if (node != null && node.params != null) node.params[v.substring(1)] else null; v = if (truthy(pv)) str(pv) else "" }
        if (v != "") params[key] = v
    }
    if (type.isEmpty() || path.isEmpty()) return emptyList()
    val key = type + "?" + JSON.stringify(params) + "#" + path
    val cached = picklistCache[key]
    if (cached != null) return cached.await()
    val deferred = kotlinx.coroutines.MainScope().async {
        val body = obj(); body.type = type; body.params = params; body.inputs = obj()
        val res = api("POST", "/api/lcnc/run", body)
        if (!truthy(res) || !truthy(res.outputs)) return@async emptyList<String>()
        var cur: dynamic = res.outputs
        for (seg in path.split(".")) {
            if (cur == null) return@async emptyList<String>()
            if (seg.endsWith("[]")) {
                val k = seg.dropLast(2)
                cur = if (k.isNotEmpty()) cur[k] else cur
                if (!isArray(cur)) return@async emptyList<String>()
                val wrapped = obj(); wrapped.__list = cur; cur = wrapped
            } else if (truthy(cur) && truthy(cur.__list)) {
                val wrapped = obj(); wrapped.__list = arr(cur.__list).map { x -> if (truthy(x)) x[seg] else x }.filter { it != null }.toTypedArray(); cur = wrapped
            } else cur = cur[seg]
        }
        val fin: dynamic = cur
        val out = if (truthy(fin) && truthy(fin.__list)) arr(fin.__list) else if (isArray(fin)) arr(fin) else emptyArray()
        out.map { str(it) }.filter { it.isNotEmpty() }
    }
    picklistCache[key] = deferred
    return deferred.await()
}
