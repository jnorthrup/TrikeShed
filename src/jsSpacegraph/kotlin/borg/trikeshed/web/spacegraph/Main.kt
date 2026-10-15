package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.web.graal.AllocationInspector
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/** Entry point of the `spacegraph` Kotlin/JS app: the LCNC patch-panel editor served at `/panels`. */
fun main() {
    MuxIcons.install()
    AppNav.mount()
    AllocationInspector.install()
    if (document.readyState.toString() == "loading") document.addEventListener("DOMContentLoaded", { boot() }) else boot()
}

private fun boot() {
    if (document.getElementById("curator") != null) { CuratorPage.mount(); return }
    if (document.getElementById("world") == null) return
    val p = Panels()
    p.blip.install()
    p.camera.install()
    wire(p)
    val ws = SpaceGraphWorkspace(p); p.workspace = ws; ws.install()
    p.facets.install()
    exposeWindow(p)
    // The BLACKBOARD: a delta on the vocabulary entry re-hydrates the palette from what is on the board.
    try {
        val es = jsNew(js("EventSource"), "/blackboard/facts")
        es.onmessage = { e: dynamic ->
            try { val d = JSON.parse<dynamic>(str(e.data)); if (truthy(d) && d.key == "lcnc/vocabulary") launchJs { p.syncContracts(announce = false); p.buildPalette() } } catch (x: dynamic) {}
        }
    } catch (x: dynamic) {}
    launchJs { start(p) }
}

/** boot: restore, or seed the starter panel. Boot waits for contract hydration. */
private suspend fun start(p: Panels, revision: Int = p.documentRevision) {
    val search = URLSearchParams(window.location.search)
    if (DebugNavigation.requested(search)) {
        // Reconnect to an existing run before contracts, scratch, timers, or source activation.
        p.autosave = false
        p.graph.controls = objOf { it.humanOversight = true; it.inspectionOnly = true }
        byId("runBtn").disabled = true; byId("liveBtn")?.disabled = true; byId("debugBtn")?.disabled = true
        byId("palette").classList.remove("open")
        p.applyView(); window.requestAnimationFrame { p.redraw() }
        try {
            val (token, run) = DebugNavigation.source(search)
            p.status("Observing an existing run — no construction loaded or executed; saved scratch is unchanged")
            p.debugger.attach(token, run)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: dynamic) { p.refuseLoad("Debugger recovery unavailable: " + failureText(e)) }
        return
    }
    if (ConfixNavigation.requested(search)) {
        // Inspect before vocabulary hydration or scratch restoration. Even an invalid link
        // stays inert: no fallback to the live ops board, timers, or a remembered construction.
        p.autosave = false
        p.graph.controls = objOf { it.humanOversight = true; it.inspectionOnly = true }
        byId("runBtn").disabled = true; byId("liveBtn")?.disabled = true; byId("debugBtn")?.disabled = true
        byId("palette").classList.remove("open")
        try {
            val (cid, path) = ConfixNavigation.source(search)
            p.facets.inspectContent(cid, path)
            p.status("Retained content inspection — no construction loaded or executed; saved scratch is unchanged")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: dynamic) { p.refuseLoad("Invalid retained-content link: " + failureText(e)) }
        p.applyView(); window.requestAnimationFrame { p.redraw() }
        return
    }
    val documentTask = search.get("documentTask")
    if (documentTask != null && p.documentRevision == revision) {
        // Keep task links inert even while the vocabulary and saved task are still loading.
        p.autosave = false
        p.graph.controls = objOf { it.humanOversight = true; it.inspectionOnly = true }
        byId("runBtn").disabled = true; byId("liveBtn")?.disabled = true; byId("debugBtn")?.disabled = true
    }
    if (!p.syncContracts()) {
        p.refuseLoad("Contract vocabulary is unavailable; startup is paused") { launchJs { start(p, revision) } }
        return
    }
    p.syncLanes()
    launchJs { p.buildPalette() }
    if (p.documentRevision != revision) {
        byId("panelLoadRefusal")?.remove()
        p.status("Contracts ready; canvas changed during startup, so automatic restore was skipped")
        return
    }
    if (!truthy(document.body!!.classList.contains("sg-spatial"))) byId("palette").classList.add("open")
    if (search.get("dlg") == "keys") launchJs { p.openKeyMuxDlg() }
    if (documentTask != null) {
        // A failed task link must never fall through to a live default or restored local canvas.
        try { if (p.loadDocumentTask(documentTask)) p.fit() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: dynamic) { p.status("Document task not loaded: " + failureText(e)) }
        p.applyView(); window.requestAnimationFrame { p.redraw() }
        return
    }
    // /panels?load=<name> IS a link to a document: presets first, then the store. Link views are ephemeral.
    val qload = search.get("load")
    if (!qload.isNullOrEmpty()) {
        p.autosave = false
        try {
            val pr = fetchJson("/api/panels/presets")
            if (p.documentRevision != revision) return
            val hit = arr(pr.presets).firstOrNull { it.name == qload || it.name == "preset-$qload" }
            if (hit != null && truthy(hit.document)) {
                if (!p.load(fromConfix(hit.document))) return
                p.panelName().value = str(hit.name).replace(Regex("^preset-"), "")
                p.status("viewing " + str(hit.name) + " — link view, ⇪ store to keep changes"); p.syncBoard(str(hit.name))
            } else {
                val resp = fetchJs("/api/panels/" + encodeURIComponent(qload))
                if (!truthy(resp.ok)) { p.refuseLoad("No such program: $qload"); return }
                val doc = awaitJs(resp.json())
                if (p.documentRevision != revision || !p.load(doc)) return
                p.panelName().value = qload; p.syncBoard(qload)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: dynamic) { p.refuseLoad("Link load failed: " + failureText(e)); return }
    } else {
        val stored: dynamic
        try { stored = JSON.parse(localStorage.getItem("trikeshed.panels.v3") ?: "null") }
        catch (e: dynamic) { p.refuseLoad("Saved scratch is unreadable: " + failureText(e)); return }
        if (stored != null) { if (!p.load(stored)) return }
        else if (!seedOpsBoard(p)) return
    }
    val bookmark = PatchNavigation.decode(window.location.hash)
    if (bookmark != null) p.camera.restoreCameraView(bookmark)
    else if (!qload.isNullOrEmpty()) p.fit()
    p.applyView(); window.requestAnimationFrame { p.redraw() }
    // The ops board remains live; constructions requiring oversight wait for Run.
    p.armSources(automatic = true)
    p.runAll(null, automatic = true)
}

/** the default construction is the PRODUCTION OPS BOARD: live system state, not an aspirational plan */
private fun seedOpsBoard(p: Panels): Boolean {
    // Assemble first: a partial vocabulary must not leave a half-seeded, autosaved board.
    val draft = objOf { it.nodes = jsArray(); it.wires = jsArray(); it.seq = 1 }
    fun add(type: String, x: Double, y: Double, params: dynamic = obj()): dynamic {
        val n = objOf { it.id = "n${str(draft.seq)}"; it.type = type; it.x = x; it.y = y; it.params = params }
        draft.seq = num(draft.seq) + 1; draft.nodes.push(n); return n
    }
    fun o(vararg kv: Pair<String, String>): dynamic { val r = obj(); for ((k, v) in kv) r[k] = v; return r }
    val t = add("timer", 30.0, 60.0, o("seconds" to "10"))
    val v = add("graal.vitals", 250.0, 30.0)
    val pk = add("pick", 470.0, 30.0, o("path" to "memory.heapUsed"))
    val g = add("gauge", 650.0, 20.0, o("label" to "heap used"))
    val pk2 = add("pick", 470.0, 180.0, o("path" to "gc.collections"))
    val g2 = add("gauge", 650.0, 170.0, o("label" to "gc collections"))
    val sc = add("project.list", 250.0, 330.0)
    val scd = add("display", 470.0, 330.0)
    val bi = add("beliefs.introspect", 250.0, 520.0)
    val bid = add("display", 470.0, 520.0)
    val vs = add("vms.list", 250.0, 700.0)
    val vsd = add("display", 470.0, 700.0)
    val ks = add("keys.status", 250.0, 880.0)
    val ksd = add("display", 470.0, 880.0)
    // kanban: not a node, a COMPOSITION on the atomic vocabulary
    val kas = add("kanban.activeSheets", 30.0, 1060.0)
    val ksc = add("sheet.concentric", 300.0, 1030.0)
    val bpi = add("pick", 300.0, 1430.0, o("path" to "items"))
    val bpc = add("pick", 300.0, 1570.0, o("path" to "columns"))
    val bgb = add("list.groupBy", 520.0, 1430.0, o("key" to "status"))
    val bdb = add("dom.board", 740.0, 1430.0, o("idField" to "id", "titleField" to "title", "subtitleField" to "id", "badgeField" to "priority"))
    val bmv = add("js", 1460.0, 1430.0, o("expr" to "{jobId:x.itemId,toColumn:x.to,expectedRevision:x.item.revision,idempotencyKey:'panel-'+x.itemId+'-'+x.item.revision+'-'+x.to}"))
    val bkm = add("kanban.move", 1680.0, 1430.0)
    add("kanban.submit", 30.0, 1430.0, o("jobId" to "", "title" to "", "priority" to "2", "idempotencyKey" to ""))
    // continents: the heap treemap and the quota-legion standings live on the same canvas
    val hp = add("graal.heap", 250.0, 1760.0, o("lane" to "allocation"))
    val ms = add("mux.standings", 250.0, 2000.0)
    add("note", 650.0, 340.0, o("text" to "production ops board — live planes, no fiction.\nright-click/double-click: add nodes.\ndrop a folder anywhere: mount it as a scope.\nkanban below: concentric treesheets (click ▤ refs to\ndive, crumb climbs out) + draggable board — a drop\nonly DESCRIBES the gesture; js + kanban.move decide.\nkanban.submit: fill params, ▶ — a real card lands.\nfurther down: heap continent + quota legion."))
    val links: List<Array<dynamic>> = listOf(
        arrayOf(t, v, "tick", "trigger?"), arrayOf(v, pk, "json", "x"), arrayOf(pk, g, "y", "x"),
        arrayOf(v, pk2, "json", "x"), arrayOf(pk2, g2, "y", "x"),
        arrayOf(t, sc, "tick", "trigger?"), arrayOf(sc, scd, "scopes", "x"),
        arrayOf(t, bi, "tick", "trigger?"), arrayOf(bi, bid, "field", "x"),
        arrayOf(t, vs, "tick", "trigger?"), arrayOf(vs, vsd, "rows", "x"),
        arrayOf(t, ks, "tick", "trigger?"), arrayOf(ks, ksd, "roster", "x"),
        arrayOf(t, kas, "tick", "trigger?"),
        arrayOf(kas, ksc, "board", "board"), arrayOf(kas, ksc, "byStatus", "byStatus?"),
        arrayOf(kas, ksc, "byPriority", "byPriority?"), arrayOf(kas, ksc, "orchestration", "orchestration?"),
        arrayOf(kas, bpi, "boardView", "x"), arrayOf(kas, bpc, "boardView", "x"),
        arrayOf(bpi, bgb, "y", "x"),
        arrayOf(bgb, bdb, "groups", "groups"), arrayOf(bpc, bdb, "y", "columns?"),
        arrayOf(bdb, bmv, "move", "x"), arrayOf(bmv, bkm, "y", "command?"),
        arrayOf(t, hp, "tick", "trigger?"),
        arrayOf(t, ms, "tick", "trigger?"),
    )
    for (l in links) draft.wires.push(wire(l[0].id, l[2], l[1].id, l[3]))
    return p.load(draft)
}

/** every control on the bar, the canvas gestures, keys */
private fun wire(p: Panels) {
    val vp = p.viewport
    on(p.world, "pointerdown", { e ->
        val hitEl = if (e.target.closest != null) e.target.closest(".node") else undefined
        val picked = p.nodes().firstOrNull { it.el === hitEl }
        val target = if (picked != null && truthy(picked._childHost)) picked else picked?._parentScope ?: p.scopeAtTarget(e.target)
        p.layoutOwner.parentId = if (truthy(target)) target.id else null
    })
    on(vp, "dblclick", { e ->
        val sc = p.scopeAtTarget(e.target)
        if (sc != null) p.showMenu(num(e.clientX), num(e.clientY), sc)
        else if (e.target === vp || e.target.id == "world" || e.target.id == "wires") p.showMenu(num(e.clientX), num(e.clientY))
    })
    on(vp, "contextmenu", { e -> e.preventDefault(); p.showMenu(num(e.clientX), num(e.clientY), p.scopeAtTarget(e.target)) })
    on(byId("qsBtn"), "click", { p.openQuickstart() })
    on(byId("fbBtn"), "click", { p.openFeedback() })
    var dragHint: dynamic = null
    on(vp, "dragover", { e ->
        e.preventDefault(); e.dataTransfer.dropEffect = "copy"
        val types = arr(js("Array.from(e.dataTransfer.types||[])")).map { str(it) }
        val isPalette = "text/x-lcnc-type" in types || "text/x-lcnc-program" in types
        if (dragHint == null) {
            dragHint = document.createElement("div").asDynamic()
            dragHint.style.cssText = "position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);background:var(--panel);border:1px dashed var(--graal);color:var(--graal);padding:10px 18px;border-radius:8px;pointer-events:none;z-index:50;font-size:12px"
            vp.appendChild(dragHint)
        }
        dragHint.textContent = if (isPalette) "⊕ drop to place (a ring's square places INSIDE it)" else "⊕ drop to mount project"
    })
    on(vp, "dragleave", { e -> if (!vp.contains(e.relatedTarget)) { dragHint?.remove(); dragHint = null } })
    on(vp, "drop", { e -> e.preventDefault(); dragHint?.remove(); dragHint = null; launchJs { p.onDrop(e) } })
    on(window, "pointerdown", { e -> if (!p.menu.contains(e.target)) p.menu.style.display = "none" })
    on(byId("addBtn"), "click", { p.showMenu(60.0, 60.0) })
    on(byId("fitBtn"), "click", { p.fit() })
    on(byId("shakeBtn"), "click", { e -> launchJs { p.shake.request(p.shakeOwner, objOf { it.optional = truthy(e.shiftKey) }) } })
    on(byId("fdBtn"), "click", { launchJs { p.layout.settle(p.layoutOwner) } })
    byId("runBtn").title = "Run this authored program once in the daemon and inspect its durable receipt"
    on(byId("runBtn"), "click", { launchJs { p.runProgram() } })
    val debug = document.createElement("button").asDynamic()
    debug.id = "debugBtn"; debug.textContent = "Debug nodes"
    debug.title = "Run once in the daemon; step nodes and rings, or enter in-process JS/Python/Ruby source. Pauses count toward timeouts."
    byId("runBtn").parentNode.insertBefore(debug, byId("stopBtn"))
    on(debug, "click", { launchJs { p.runProgram(debug = true) } })
    val live = document.createElement("button").asDynamic()
    live.id = "liveBtn"; live.textContent = "Live"; live.setAttribute("aria-label", "Run live panel")
    live.title = "Page-owned interactive sweep and sources; not a durable whole-program run"
    byId("runBtn").parentNode.insertBefore(live, byId("stopBtn"))
    on(live, "click", {
        if (!p.programRunning) { p.armSources(); launchJs { p.runAll(null) } }
    })
    on(byId("stopBtn"), "click", { p.stopSources() })
    on(p.mm, "click", { e ->
        if (truthy(p.mm._s)) {
            val r = p.mm.getBoundingClientRect()
            val wx = ((num(e.clientX) - num(r.left)) - num(p.mm._ox)) / num(p.mm._s); val wy = ((num(e.clientY) - num(r.top)) - num(p.mm._oy)) / num(p.mm._s)
            p.view.x = -(wx * num(p.view.z)) + num(vp.clientWidth) / 2; p.view.y = -(wy * num(p.view.z)) + num(vp.clientHeight) / 2
            p.applyView(); p.save(); p.drawMinimap()
        }
    })
    window.setInterval({ p.drawMinimap() }, 1500)
    on(window, "keydown", { e ->
        val t = e.target; val tag = str(t?.tagName ?: "").lowercase()
        if (tag == "input" || tag == "textarea" || tag == "select" || truthy(t?.isContentEditable)) return@on
        // Bare F frames the board — checked before the modifier gate
        if ((e.key == "f" || e.key == "F") && !truthy(e.metaKey) && !truthy(e.ctrlKey) && !truthy(e.altKey)) { e.preventDefault(); p.fit(); return@on }
        if (!(truthy(e.metaKey) || truthy(e.ctrlKey))) return@on
        if (e.key == "z" || e.key == "Z") { e.preventDefault(); if (truthy(e.shiftKey)) p.history.redo() else p.history.undo() }
        else if (e.key == "y" || e.key == "Y") { e.preventDefault(); p.history.redo() }
    })
    on(byId("storeSaveBtn"), "click", {
        launchJs {
            val name = str(p.panelName().value ?: "").trim()
            if (!Regex("^[a-z0-9][a-z0-9._-]*$").matches(name)) { p.status("name: lowercase kebab"); return@launchJs }
            val resp = fetchJs("/api/panels/$name", jsonInit("POST", JSON.stringify(p.serialize())))
            val r = awaitJs(resp.json())
            val refused = arr(r.violations)
            p.status(if (r.verdict == "ok") "saved panels/" + name + " @ " + str(r.cid).let { it.substring(minOf(7, it.length), minOf(19, it.length)) } +
                (if (refused.isNotEmpty()) " · ${refused.size} cable(s) refused by the daemon: " + str(refused[0].detail) else "")
            else "save failed: " + str(r.error ?: r.detail ?: resp.status))
            if (r.verdict == "ok") p.syncBoard(name)
        }
    })
    on(byId("storeLoadBtn"), "click", {
        launchJs {
            var name = str(p.panelName().value ?: "").trim()
            if (name.isEmpty()) {
                val r = fetchJson("/api/panels")
                val names = arr(r.panels).map { str(it.name) }
                if (names.isEmpty()) { p.status("store has no panels yet"); return@launchJs }
                name = window.prompt("load which panel?\n" + names.joinToString("\n"), names[0]) ?: ""
                if (name.isEmpty()) return@launchJs
            }
            val resp = fetchJs("/api/panels/$name")
            if (!truthy(resp.ok)) { p.status("load failed: " + str(resp.status)); return@launchJs }
            if (!p.load(awaitJs(resp.json()))) return@launchJs
            p.panelName().value = name
            p.armSources(automatic = true); launchJs { p.runAll(null, automatic = true) }
            p.status("loaded panels/$name")
            p.syncBoard(name)
        }
    })
    on(byId("exportBtn"), "click", {
        val doc = p.serialize()
        val parts = arrayOf(js("JSON.stringify(doc,null,1)")); val blobType = objOf { it.type = "application/json" }
        val blob = js("new Blob(parts, blobType)")
        val a = document.createElement("a").asDynamic(); a.href = js("URL").createObjectURL(blob); a.download = "panel-graph.json"; a.click()
    })
    on(byId("importBtn"), "click", { byId("fileIn").click() })
    on(byId("fileIn"), "change", { e ->
        val f = e.target.files[0]
        if (f != null) launchJs {
            try {
                if (p.load(JSON.parse(str(awaitJs(f.text()))))) {
                    p.armSources(automatic = true); p.runAll(null, automatic = true)
                }
            } catch (x: kotlinx.coroutines.CancellationException) { throw x }
            catch (x: dynamic) { p.refuseLoad("Import failed: " + failureText(x)) }
            finally { e.target.value = "" }
        }
    })
    on(byId("clearBtn"), "click", { if (window.confirm("clear the panel?")) p.load(objOf { it.nodes = jsArray(); it.wires = jsArray() }) })
    on(byId("paletteBtn"), "click", { byId("palette").classList.toggle("open") })
    on(byId("keysBtn"), "click", { launchJs { p.openKeyMuxDlg() } })
    on(byId("assetsBtn"), "click", { borg.trikeshed.web.AssetTreeDialog.toggle() })
    on(byId("presetsBtn"), "click", { launchJs { p.openGallery() } })
    on(byId("rdfBtn"), "click", { p.openRdf() })
    on(byId("undoBtn"), "click", { p.history.undo() })
    on(byId("redoBtn"), "click", { p.history.redo() })
}

/** the page's shared state and operations, as the markup, the blip and cross-surface scripts address them */
private fun exposeWindow(p: Panels) {
    val w = window.asDynamic()
    val gGet = obj(); gGet.get = { p.graph }; gGet.configurable = true
    js("Object.defineProperty")(w, "G", gGet)
    w.view = p.view
    w.serialize = { p.serialize() }
    w.redraw = { p.redraw() }
    w.save = { p.save() }
    w.connectPanelPorts = { from: dynamic, to: dynamic -> p.connectPanelPorts(from, to) }
    w.removeNode = { id: dynamic -> p.removeNode(id) }
    w.growRingWorldFor = { n: dynamic -> p.growRingWorldFor(n) }
    w.ringScaleOf = { n: dynamic -> p.ringScaleOf(n) }
    w.applyView = { p.applyView() }
    w.blipLeave = { p.blipLeave() }
    val cGet = obj(); cGet.get = { p.contracts }; cGet.configurable = true
    js("Object.defineProperty")(w, "CONTRACTS", cGet)
}
