package borg.trikeshed.web.harness

import borg.trikeshed.landscape.Extent
import borg.trikeshed.landscape.LandscapeCamera
import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.landscape.Pt
import borg.trikeshed.web.PatchLayoutOwner
import borg.trikeshed.web.PatchNavigation
import borg.trikeshed.web.PatchShakeOwner
import borg.trikeshed.web.arr
import borg.trikeshed.web.awaitJs
import borg.trikeshed.web.num
import borg.trikeshed.web.on
import borg.trikeshed.web.str
import borg.trikeshed.web.patch.CenterBox
import borg.trikeshed.web.patch.PatchKinds
import borg.trikeshed.web.patch.PatchLimits
import borg.trikeshed.web.patch.PatchPlacement
import borg.trikeshed.web.patch.PatchRing
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/** A JS owner ({shaking,selected,parentRevision,parentTarget(),document(),message()}) as the shared shake owner. */
class JsShakeOwner(private val o: dynamic) : PatchShakeOwner {
    override var shaking: Boolean
        get() = o.shaking == true
        set(value) { o.shaking = value }
    override val selected: String? get() = if (o.selected == null) null else str(o.selected)
    override val parentRevision: Int get() = num(o.parentRevision).toInt()
    override fun parentTarget(): dynamic = o.parentTarget()
    override fun document(): dynamic = o.document()
    override fun message(text: String) { o.message(text) }
}

/** The blackboard's selected parent as the shared layout owner; hints are program-namespaced. */
object HarnessLayoutOwner : PatchLayoutOwner {
    override val selected: String? get() = Harness.selected
    override val parentRevision: Int get() = Harness.parentRevision
    override fun parentTarget(): dynamic = Harness.parentTarget()?.js()
    override fun document(): dynamic = Harness.document()
    override suspend fun layoutHints(document: dynamic, parentId: dynamic): Array<dynamic> = HarnessPatch.layout.hints(document, parentId)
    override fun layoutNodeId(id: dynamic): String = str(Harness.selected) + "::" + str(id)
    override fun resizeParent(parent: dynamic) = HarnessPatch.resizeParentFrames(parent)
    override val mounts: dynamic get() = Harness.mounts
    override fun bounds(name: String): dynamic = Harness.bounds(name)
}

/**
 * Publishes on `window` every name patch.js, patch-layout.js, patch-shake.js, patch-camera.js,
 * landscape-navigation.js and landscape.js declared at top level, and installs their top-level
 * listeners in the original script order.
 */
@OptIn(DelicateCoroutinesApi::class)
object HarnessPatchGlobals {
    private val w: dynamic get() = window.asDynamic()

    private fun value(name: String, get: () -> dynamic, set: ((dynamic) -> Unit)? = null) {
        val d: dynamic = jsObject(); d.configurable = true; d.enumerable = true; d.get = get
        if (set != null) d.set = set
        js("Object").defineProperty(window, name, d)
    }

    /** landscape-navigation.js: `window.LandscapeNavigation` over commonMain [LandscapeNavigation] */
    fun landscapeNavigation(): dynamic {
        val o: dynamic = jsObject()
        o.minZoom = LandscapeNavigation.minZoom; o.detailZoom = LandscapeNavigation.detailZoom; o.absoluteZoom = LandscapeNavigation.absoluteZoom
        o.maxZoom = { scale: dynamic -> LandscapeNavigation.maxZoom(if (scale == undefined) 1.0 else num(scale)) }
        o.wheelFactor = { delta: dynamic -> LandscapeNavigation.wheelFactor(num(delta)) }
        o.callout = { pointer: dynamic, size: dynamic, bounds: dynamic, subject: dynamic, preferred: dynamic ->
            PatchNavigation.callout(num(pointer.x), num(pointer.y), num(size.width), num(size.height), bounds, subject, preferred)
        }
        o.zoomAt = { camera: dynamic, requested: dynamic, anchor: dynamic, ceiling: dynamic ->
            val c = LandscapeNavigation.zoomAt(LandscapeCamera(num(camera.x), num(camera.y), num(camera.z)), num(requested), Pt(num(anchor.x), num(anchor.y)),
                if (ceiling == undefined) LandscapeNavigation.detailZoom else num(ceiling))
            obj { it.x = c.x; it.y = c.y; it.z = c.z }
        }
        o.projectWires = { svg: dynamic, viewport: dynamic, camera: dynamic -> PatchNavigation.projectWires(svg, viewport, camera) }
        o.wireCurve = { path: dynamic, a: dynamic, b: dynamic, viewport: dynamic, camera: dynamic -> PatchNavigation.wireCurve(path, a, b, viewport, camera) }
        o.projectCurve = { path: dynamic, viewport: dynamic, camera: dynamic -> PatchNavigation.projectCurve(path, num(viewport.clientWidth), num(viewport.clientHeight), camera) }
        o.encode = { camera: dynamic, focus: String? -> LandscapeNavigation.encode(LandscapeCamera(num(camera.x), num(camera.y), num(camera.z)), focus ?: "") }
        o.decode = { hash: String ->
            val d = LandscapeNavigation.decode(hash)
            if (d == null) null else obj { it.camera = obj { c -> c.x = d.camera.x; c.y = d.camera.y; c.z = d.camera.z }; it.focus = d.focus }
        }
        o.program = { name: String -> LandscapeNavigation.program(name) }
        o.node = { program: String, id: String -> LandscapeNavigation.node(program, id) }
        o.`object` = { id: String -> LandscapeNavigation.`object`(id) }
        return o
    }

    /** patch-layout.js: `window.PatchLayout` = {layout, placement, limits, hints, settle, ringView, ringLayout} */
    fun patchLayout(): dynamic {
        val p = HarnessPatch
        val o: dynamic = jsObject()
        o.limits = obj {
            it.nodes = PatchLimits.nodes; it.edges = PatchLimits.edges; it.steps = PatchLimits.steps; it.milliseconds = PatchLimits.milliseconds
            it.ticks = PatchLimits.ticks; it.gap = PatchLimits.gap; it.cable = PatchLimits.cable
        }
        js("Object").freeze(o.limits)
        o.layout = { boxes: dynamic, patches: dynamic, options: dynamic ->
            val valid = options?.valid
            GlobalScope.promise { p.layout.layout(arr(boxes).toList(), arr(patches).toList(), if (valid == null) null else ({ valid() == true })) }
        }
        o.placement = { box: dynamic, obstacles: dynamic ->
            val r = PatchPlacement.placement(num(box.x), num(box.y), num(box.w), num(box.h), arr(obstacles).map { CenterBox(num(it.x), num(it.y), num(it.w), num(it.h)) })
            obj { it.x = r.first; it.y = r.second }
        }
        o.hints = { document: dynamic, parentId: dynamic -> GlobalScope.promise { p.layout.hints(document, parentId) } }
        o.settle = { owner: dynamic -> GlobalScope.promise { p.layout.settle(if (owner == null) null else HarnessLayoutOwner) } }
        o.ringView = { width: Double, height: Double -> val v = PatchRing.view(width, height); obj { it.x = v.x; it.y = v.y; it.z = v.z } }
        o.ringLayout = { n: dynamic, options: dynamic ->
            val preserve = options?.preserve == true
            val resize: dynamic = options?.resize; val frame: dynamic = options?.frame
            p.layout.ringLayout(n, preserve, if (resize == null) null else ({ x, a -> resize(x, a) }), if (frame == null) null else ({ x -> frame(x) }), { p.applyRingView(it) })
        }
        return o
    }

    fun install() {
        val p = HarnessPatch
        val L = HarnessLandscape
        // ── landscape-navigation.js / patch-layout.js ──
        w.LandscapeNavigation = landscapeNavigation()
        w.PatchLayout = patchLayout()
        // ── patch.js: graph state and surface ──
        w.RUNNERS = obj { o -> for ((type, r) in p.runners) o[type] = obj { it.run = { n: dynamic, i: dynamic -> GlobalScope.promise { r.run(n, i) } }; if (r.render != null) it.render = r.render } }
        w.serverRun = { type: String -> serverRun(type) }
        value("G", { p.graph }, { p.graph = it })
        value("view", { p.view }, { p.view = it })
        w["$"] = { s: String -> document.querySelector(s) }
        w.world = p.world; w.wiresSvg = p.wiresSvg; w.viewport = p.viewport
        w.enc = { s: String -> encodeURIComponent(s) }
        w.p = { n: dynamic, k: String -> p.p(n, k) }
        w.blipEl = { document.getElementById("blip") }
        w.blipMove = { e: dynamic -> HarnessBlip.move(e) }
        w.blipLeave = { e: dynamic -> HarnessBlip.leave(e ?: null) }
        w.blipEnter = { n: dynamic, e: dynamic -> HarnessBlip.enter(n, e) }
        w.api = { method: String, path: String, body: dynamic -> GlobalScope.promise { borg.trikeshed.web.api(method, path, body) } }
        w.renderTree = { v: dynamic, key: dynamic, depth: dynamic -> renderTree(v, if (key == null || key == undefined) "" else str(key), if (depth == undefined) 0 else num(depth).toInt()) }
        w.setResult = { n: dynamic, v: dynamic -> p.setResult(n, v) }
        w.renderGroupedBoard = { n: dynamic -> p.renderGroupedBoard(n) }
        w.renderConcentric = { n: dynamic -> p.renderConcentric(n) }
        w.renderHeap = { n: dynamic -> p.renderHeap(n) }
        w.renderStandings = { n: dynamic -> p.renderStandings(n) }
        w.renderPanelsList = { n: dynamic, panels: dynamic -> p.renderPanelsList(n, panels) }
        w.renderProgramRef = { n: dynamic -> p.renderProgramRef(n) }
        w.renderButtonNode = { n: dynamic -> p.renderButtonNode(n) }
        w.renderSliderNode = { n: dynamic -> p.renderSliderNode(n) }
        w.ensureMedia = { n: dynamic, url: dynamic -> p.ensureMedia(n, if (url == null) "" else str(url)) }
        w.copyText = { s: dynamic -> copyText(str(s)) }
        w.fallbackCopy = { s: dynamic -> fallbackCopy(str(s)) }
        value("diveStack", { p.diveStack }, { p.diveStack = it })
        w.crumbSync = { p.crumbSync() }
        w.diveInto = { name: String -> GlobalScope.promise { p.diveInto(name) } }
        w.popTo = { depth: Int -> p.popTo(depth) }
        value("LANES", { p.lanes }, { p.lanes = it })
        w.syncLanes = { GlobalScope.promise { p.syncLanes() } }
        w.laneOf = { n: dynamic -> p.laneOf(n) }
        w.ringDepth = { n: dynamic -> p.ringDepth(n) }
        w.RING_EDGE = HarnessPatch.RING_EDGE; w.RING_GAP = HarnessPatch.RING_GAP
        w.NODE_FRAMES = p.nodeFrames
        w.applyNodeFrame = { n: dynamic -> p.applyNodeFrame(n) }
        w.resizeNode = { e: dynamic, n: dynamic -> p.resizeNode(e, n) }
        w.toggleNodeCollapsed = { n: dynamic, button: dynamic -> p.toggleNodeCollapsed(n, button) }
        w.layoutRing = { n: dynamic, preserve: dynamic -> p.layoutRing(n, preserve == true) }
        w.ringScaleOf = { n: dynamic -> p.ringScaleOf(n) }
        w.resizeParentFrames = { parent: dynamic, ancestors: dynamic -> p.resizeParentFrames(parent, ancestors != false) }
        w.applyRingView = { n: dynamic -> p.applyRingView(n) }
        w.growRingWorldFor = { n: dynamic -> p.growRingWorldFor(n) }
        w.syncRingFrame = { n: dynamic -> p.syncRingFrame(n) }
        w.refreshRingChrome = { n: dynamic -> p.refreshRingChrome(n) }
        w.mountChildren = { p.mountChildren() }
        w.resolveTopLevelOverlaps = { p.resolveTopLevelOverlaps() }
        w.drawSpine = { p.drawSpine() }
        w.redrawConcentric = { p.redrawConcentric() }
        w.mintId = { p.mintId() }
        w.addNode = { type: String, x: Double, y: Double, params: dynamic -> p.addNode(type, x, y, params ?: null) }
        w.topRingOf = { s: dynamic -> p.topRingOf(s) }
        w.addChildNode = { scope: dynamic, type: String -> p.addChildNode(scope, type) }
        w.kindOf = { type: dynamic, dir: String, port: String -> p.kindOf(type, dir, port) }
        w.KIND_CLASS = obj { o -> for ((k, v) in PatchKinds.kindClass) o[k] = v }
        w.portClass = { type: dynamic, dir: String, port: String -> p.portClass(type, dir, port) }
        w.KINDCOLOR = obj { o -> for ((k, v) in PatchKinds.kindColor) o[k] = v }
        w.kindsCompatible = { a: String?, b: String? -> p.kindsCompatible(a, b) }
        w.refinedLiteralKind = { nd: dynamic, port: String, declared: String -> p.refinedLiteralKind(nd, port, declared) }
        w.nodeKindOf = { nd: dynamic, dir: String, port: dynamic -> p.nodeKindOf(nd, dir, str(port)) }
        w.wireKindOk = { wire: dynamic -> p.wireKindOk(wire) }
        w.nodeParams = { type: dynamic, authored: dynamic -> p.nodeParams(type, authored ?: null) }
        w.nodePorts = { n: dynamic, dir: String -> p.nodePorts(n, dir).toTypedArray() }
        w.scopeBindingLinks = { nodes: dynamic, wires: dynamic, limit: dynamic ->
            p.scopeBindingLinks(arr(nodes).toList(), arr(wires), if (limit == undefined) 512 else num(limit).toInt()).map { l ->
                obj { it.scope = l.scope; it.child = l.child; it.from = l.from; it.to = l.to; it.kind = l.kind; it.label = l.label }
            }.toTypedArray()
        }
        w.scopePortLabel = { n: dynamic, dir: String, name: String -> p.scopePortLabel(n, dir, name) }
        w.refreshBindingCaption = { n: dynamic -> p.refreshBindingCaption(n) }
        w.renderNodePorts = { n: dynamic -> p.renderNodePorts(n) }
        w.buildNode = { n: dynamic -> p.buildNode(n) }
        w.removeNode = { id: dynamic -> p.removeNode(id) }
        w.portCenter = { nodeId: dynamic, dir: String, name: dynamic -> p.portCenter(str(nodeId), dir, str(name)) }
        w.WIRE_PAD = HarnessPatch.WIRE_PAD
        w.growWireBox = { pts: dynamic -> p.growWireBox(arr(pts).toList()) }
        w.applyWireBox = { p.applyWireBox() }
        w.bez = { a: dynamic, b: dynamic -> p.bez(a, b) }
        value("BOARD", { p.board }, { p.board = it })
        w.cableKey = { a: dynamic, b: dynamic, c: dynamic, d: dynamic -> p.cableKey(a, b, c, d) }
        w.currentProgramName = { p.currentProgramName() }
        w.applyBoardEntry = { name: dynamic, e: dynamic, opts: dynamic -> p.applyBoardEntry(if (name == null) null else str(name), e, opts?.loadDocument == true) }
        w.applyProgramDelta = { key: String, v: dynamic -> p.applyProgramDelta(key, v) }
        w.loadBoardProgram = { name: String -> GlobalScope.promise { p.loadBoardProgram(name) } }
        w.syncBoard = { name: dynamic -> GlobalScope.promise { p.syncBoard(if (name == null) null else str(name)) } }
        w.redraw = { p.redraw() }
        value("dragWire", { p.dragWire }, { p.dragWire = it })
        w.fdLayout = { GlobalScope.promise { p.layout.settle(HarnessLayoutOwner) } }
        w.livePicklist = { spec: dynamic -> livePicklist(str(spec)).then { it.toTypedArray() } }
        w.treeshake = { opts: dynamic -> w.Harness.shake(opts) }
        w.wireEdgeVelocity = { point: dynamic, rect: dynamic -> wireEdgeVelocity(point, rect) }
        w.startWireDrag = { n: dynamic, dir: String, name: String, e: dynamic -> p.startWireDrag(n, dir, name, e) }
        w.showMateMenu = { cx: Double, cy: Double, node: dynamic, port: dynamic, wx: Double, wy: Double, scope: dynamic, dir: dynamic ->
            GlobalScope.promise { p.showMateMenu(cx, cy, node, str(port), wx, wy, scope ?: null, if (dir == undefined) "out" else str(dir)) }
        }
        w.fitToContent = { p.fitView() }
        w.menu = p.menu
        w.positionCreationMenu = { cx: Double, cy: Double -> p.positionCreationMenu(cx, cy) }
        w.showMenu = { cx: Double, cy: Double, scope: dynamic -> p.showMenu(cx, cy, scope ?: null) }
        w.scopeAtTarget = { t: dynamic -> p.scopeAtTarget(t) }
        w.menuTargetScope = { e: dynamic -> p.menuTargetScope(e) }
        w.panelsWalkEntries = { entry: dynamic, prefix: String ->
            GlobalScope.promise { panelsWalkEntries(entry, prefix).map { (rel, file) -> obj { it.rel = rel; it.file = file } }.toTypedArray() }
        }
        w.inputsOf = { id: dynamic -> p.inputsOf(id).toTypedArray() }
        w.topo = { p.topo().toTypedArray() }
        w.runNode = { n: dynamic, cache: dynamic -> GlobalScope.promise { p.runNode(n, cache) } }
        w.runAll = { fromId: dynamic -> GlobalScope.promise { p.runAll(if (fromId == null || fromId == undefined) null else str(fromId)) } }
        w.armSources = { p.armSources() }
        w.mm = p.mm; w.mmx = p.mmx
        w.drawMinimap = { p.drawMinimap() }
        w.nodeDoc = { n: dynamic -> nodeDoc(n) }
        w.serialize = { p.serialize() }
        value("AUTOSAVE", { p.autosave }, { p.autosave = it == true })
        w.save = { p.save() }
        w.UNDO = p.undoStack; w.REDO = p.redoStack; w.UNDO_MAX = HarnessPatch.UNDO_MAX
        value("restoring", { p.restoring }, { p.restoring = it == true })
        value("lastDoc", { p.lastDoc }, { p.lastDoc = it })
        value("lastPushMs", { p.lastPushMs }, { p.lastPushMs = num(it) })
        w.shapeKey = { o: dynamic -> shapeKey(o) }
        w.recordHistory = { cur: String -> p.recordHistory(cur) }
        w.histButtons = { p.histButtons() }
        w.applyDoc = { json: String -> p.applyDoc(json) }
        w.undo = { p.undo() }
        w.redo = { p.redo() }
        w.load = { data: dynamic -> p.load(data) }
        w.buildPalette = { GlobalScope.promise { p.buildPalette() } }
        w.openKeyMuxDlg = { GlobalScope.promise { p.openKeyMuxDlg() } }
        w.openCardModal = { n: dynamic, colId: dynamic, item: dynamic -> p.openCardModal(n, str(colId), item ?: null) }
        w.fromConfix = { doc: dynamic -> fromConfix(doc) }
        w.openGallery = { GlobalScope.promise { p.openGallery() } }
        w.toConfix = { p.toConfix() }
        w.refreshRdf = { p.refreshRdf() }
        w.rdfTab = { tab: String -> GlobalScope.promise { p.rdfTab(tab) } }
        w.openRdf = { p.openRdf() }
        value("CONTRACTS", { p.contracts }, { p.contracts = it })
        value("KIND_ACCEPTANCE", { p.kindAcceptance })
        value("KIND_REFINEMENTS", { p.kindRefinements })
        value("KIND_HIERARCHY", { p.kindHierarchy })
        w.boardVocabulary = { GlobalScope.promise { boardVocabulary() } }
        w.syncContracts = { payload: dynamic -> GlobalScope.promise { p.syncContracts(payload ?: null) } }
        // ── patch-shake.js ──
        w.requestTreeShake = { owner: dynamic, options: dynamic, namespace: dynamic ->
            GlobalScope.promise { p.shake.request(JsShakeOwner(owner), options, if (namespace == null || namespace == undefined) null else str(namespace)) }
        }
        value("STARVED", { js("new Set")(p.shake.starved.toTypedArray()) })
        w.clearVerdicts = { p.shake.clearVerdicts() }
        w.projectVerdicts = { p.shake.projectVerdicts() }
        w.positionVerdicts = { p.shake.positionVerdicts() }
        w.applyServerTreeShake = { res: dynamic, optional: dynamic, programName: dynamic ->
            p.shake.apply(res, optional == true, if (programName == null || programName == undefined) null else str(programName))
        }
        // ── patch-camera.js ──
        w.scopeZoomCeiling = { px: Double, py: Double, camera: dynamic -> if (camera == undefined) p.camera.scopeZoomCeiling(px, py) else p.camera.scopeZoomCeiling(px, py, camera) }
        w.restoreCameraView = { camera: dynamic -> p.camera.restoreCameraView(camera) }
        w.restoreCameraDetail = { p.camera.restoreCameraDetail() }
        w.projectCameraDetail = { p.camera.projectCameraDetail() }
        w.DETAIL_LIFT_ZOOM = borg.trikeshed.web.PatchCamera.DETAIL_LIFT_ZOOM
        w.applyView = { p.camera.applyView() }
        w.reducedMotion = p.camera.reducedMotion
        w.killMomentum = { p.camera.killMomentum() }
        w.saveCameraSoon = { p.camera.saveCameraSoon() }
        // ── landscape.js ──
        w.LandscapeActivity = L.activityFacade()
        w.visibleClosure = { n: dynamic -> L.visibleClosure(n) }
        w.Landscape = L.facade()
    }

    /** patch.js's top-level listeners (blip, canvas menus, drop, bar buttons, keys, minimap). */
    fun listen() {
        val p = HarnessPatch
        val vp = p.viewport
        HarnessBlip.install()
        on(vp, "dblclick", { e ->
            val sc = p.menuTargetScope(e)
            if (sc != null) p.showMenu(num(e.clientX), num(e.clientY), sc)
            else if (e.target === vp || e.target.id == "world" || e.target.id == "wires") p.showMenu(num(e.clientX), num(e.clientY))
        })
        on(vp, "contextmenu", { e -> e.preventDefault(); p.showMenu(num(e.clientX), num(e.clientY), p.menuTargetScope(e)) })
        on(document.getElementById("qsBtn"), "click", { p.openQuickstart() })
        on(document.getElementById("fbBtn"), "click", { p.openFeedback() })
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
        on(q("#addBtn"), "click", { p.showMenu(60.0, 60.0) })
        on(q("#fitBtn"), "click", { p.fitView() })
        on(q("#shakeBtn"), "click", { e -> w.Harness.shake(obj { it.optional = e.shiftKey }) })
        on(q("#fdBtn"), "click", { launchJs { p.layout.settle(HarnessLayoutOwner) } })
        on(q("#runBtn"), "click", { w.Harness.run() })
        on(q("#stopBtn"), "click", { p.stopSources() })
        on(p.mm, "click", { e -> minimapJump(e) })
        window.setInterval({ p.drawMinimap() }, 1500)
        on(window, "keydown", { e ->
            val t = e.target; val tag = str(t?.tagName ?: "").lowercase()
            if (tag == "input" || tag == "textarea" || tag == "select" || truthy(t?.isContentEditable)) return@on
            // Bare F frames the board — checked before the modifier gate
            if ((e.key == "f" || e.key == "F") && !truthy(e.metaKey) && !truthy(e.ctrlKey) && !truthy(e.altKey)) { e.preventDefault(); p.fitView(); return@on }
            if (!(truthy(e.metaKey) || truthy(e.ctrlKey))) return@on
            if (e.key == "z" || e.key == "Z") { e.preventDefault(); if (truthy(e.shiftKey)) p.redo() else p.undo() }
            else if (e.key == "y" || e.key == "Y") { e.preventDefault(); p.redo() }
        })
        on(q("#storeSaveBtn"), "click", { w.Harness.publish() })
        on(q("#storeLoadBtn"), "click", {
            launchJs {
                var name = p.currentProgramName()
                if (name.isEmpty()) {
                    val r = borg.trikeshed.web.fetchJson("/api/panels")
                    val names = arr(r.panels).map { str(it.name) }
                    if (names.isEmpty()) { p.status("store has no panels yet"); return@launchJs }
                    name = window.prompt("load which panel?\n" + names.joinToString("\n"), names[0]) ?: ""
                    if (name.isEmpty()) return@launchJs
                    p.panelName().value = name
                }
                if (p.loadBoardProgram(name)) { p.status("loaded lcnc/program/$name from the board"); return@launchJs }
                val resp = borg.trikeshed.web.fetchJs("/api/panels/$name")
                if (!truthy(resp.ok)) { p.status("load failed: " + str(resp.status)); return@launchJs }
                p.load(awaitJs(resp.json()))
                p.armSources(); launchJs { p.runAll(null) }
                p.status("loaded panels/$name")
                p.syncBoard(name)
            }
        })
        on(q("#exportBtn"), "click", {
            val doc = p.serialize()
            val parts = arrayOf(js("JSON.stringify(doc,null,1)")); val blobType = obj { it.type = "application/json" }
            val blob = js("new Blob(parts, blobType)")
            val a = document.createElement("a").asDynamic(); a.href = js("URL").createObjectURL(blob); a.download = "panel-graph.json"; a.click()
        })
        on(q("#importBtn"), "click", { q("#fileIn").click() })
        on(q("#fileIn"), "change", { e ->
            val f = e.target.files[0]
            if (f != null) launchJs { p.load(JSON.parse(str(awaitJs(f.text())))); p.armSources(); p.runAll(null) }
        })
        on(q("#clearBtn"), "click", { if (window.confirm("clear the panel?")) p.load(js("({nodes:[],wires:[]})")) })
        on(q("#paletteBtn"), "click", { q("#palette").classList.toggle("open") })
        on(q("#keysBtn"), "click", { launchJs { p.openKeyMuxDlg() } })
        on(q("#presetsBtn"), "click", { launchJs { p.openGallery() } })
        on(q("#rdfBtn"), "click", { p.openRdf() })
        on(q("#undoBtn"), "click", { p.undo() })
        on(q("#redoBtn"), "click", { p.redo() })
    }
}
