package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import org.w3c.dom.HTMLElement

/** SpatialWorkspace.mjs: the LCNC document owns all effects; this workspace owns only presentation. */
class SpaceGraphWorkspace(private val p: Panels) : SpatialCallbacks {
    private var open = false
    var backend = "svg"; private set
    private var response: dynamic = null
    private var request: dynamic = null
    private var timer = 0
    private var serial = 0
    var selected: String? = null; private set
    private var pendingPort: dynamic = null
    private lateinit var workspace: HTMLElement
    private lateinit var surface: HTMLElement
    private lateinit var inspector: HTMLElement
    private lateinit var tree: HTMLElement
    private lateinit var badge: HTMLElement
    var renderer: SpatialRenderer? = null; private set
    private var provider: dynamic = null
    private var modeButton: dynamic = null
    private var editButton: dynamic = null
    private lateinit var tools: HTMLElement
    private var query: dynamic = null
    private var observer: dynamic = null
    private var resetCamera = true
    private var dirtyAlignment = true
    private var heldParams: Pair<dynamic, dynamic>? = null
    private var lastIdentity = ""
    private var paletteWasOpen = false
    private var fallback = ""
    private var treeKey = ""
    private var projectionKey = ""
    private lateinit var providerState: HTMLElement

    private fun el(tag: String, cls: String? = null, text: String? = null): HTMLElement {
        val n = document.createElement(tag) as HTMLElement; if (cls != null) n.className = cls; if (text != null) n.textContent = text; return n
    }
    private fun node(id: dynamic): dynamic = p.node(id)
    private fun icon(name: String): HTMLElement { val n = el("i"); n.asDynamic().dataset["lucide"] = name; return n }
    private fun command(name: String, glyph: String, label: Boolean = false, action: () -> Unit): HTMLElement {
        val b = el("button", "sg-command"); b.asDynamic().type = "button"; b.title = name; b.setAttribute("aria-label", name); b.append(icon(glyph))
        if (label) b.append(el("span", "", name)); b.onclick = { action() }; return b
    }
    private fun icons() { val f = window.asDynamic().MuxIcons; if (f != null) f() }

    fun measurements(): dynamic {
        val origin = byId("world").getBoundingClientRect(); val scale = num(p.view.z)
        return p.nodes().map { n ->
            val r = n.el.getBoundingClientRect(); var visible = truthy(r.width) && truthy(r.height)
            var q = n._parentScope
            while (truthy(q)) { if (truthy(q.el.classList.contains("collapsed"))) visible = false; q = q._parentScope }
            val m = obj()
            m.id = n.id; m.x = (num(r.left) - num(origin.left)) / scale; m.y = (num(r.top) - num(origin.top)) / scale
            m.width = num(r.width) / scale; m.height = num(r.height) / scale; m.visible = visible; m.scale = p.ringScaleOf(n)
            m.ports = arr(n.el.querySelectorAll(".port")).filter { pe -> pe.closest(".node") === n.el && num(pe.getClientRects().length) > 0 }.map { pe ->
                val pr = pe.getBoundingClientRect(); val o = obj()
                o.name = pe.dataset.port; o.input = pe.dataset.dir == "in"
                o.x = (num(pr.left) + num(pr.width) / 2 - num(origin.left)) / scale; o.y = (num(pr.top) + num(pr.height) / 2 - num(origin.top)) / scale
                o
            }.toTypedArray()
            m
        }.toTypedArray()
    }

    private fun releaseParams() {
        val h = heldParams ?: return
        val (params, placeholder) = h
        if (truthy(placeholder.isConnected)) placeholder.replaceWith(params) else params.remove()
        heldParams = null
    }

    fun activate(value: Boolean) {
        val palette = byId("palette")
        if (value != open) { if (value) { paletteWasOpen = truthy(palette.classList.contains("open")); palette.classList.remove("open") } else palette.classList.toggle("open", paletteWasOpen) }
        open = value; workspace.hidden = !open; tools.hidden = !open; document.body!!.classList.toggle("sg-spatial", open)
        modeButton.setAttribute("aria-pressed", open.toString()); editButton.setAttribute("aria-pressed", (!open).toString()); byId("viewport").inert = open
        for (n in arr(document.querySelectorAll("[data-spatial]"))) n.hidden = !open
        if (open) schedule()
        else { releaseParams(); request?.abort(); request = null; serial++; window.clearTimeout(timer); timer = 0; pendingPort = null }
        window.dispatchEvent(jsNew(js("Event"), "resize")); icons()
    }

    private fun editNode(id: String) {
        val n = node(id) ?: return; activate(false)
        val r = n.el.getBoundingClientRect(); val v = byId("viewport").getBoundingClientRect()
        p.view.x = num(p.view.x) + num(v.left) + num(v.width) / 2 - num(r.left) - num(r.width) / 2
        p.view.y = num(p.view.y) + num(v.top) + num(v.height) / 2 - num(r.top) - num(r.height) / 2
        p.applyView(); p.redraw()
        val frames = js("[{outline:'3px solid #cf5266'},{outline:'0 solid transparent'}]"); val opts = obj(); opts.duration = 900
        n.el.animate(frames, opts)
    }

    override fun select(id: String?, port: dynamic) {
        selected = id; renderer?.highlight(id); document.body!!.classList.toggle("sg-inspecting", id != null)
        if (port != null) {
            val pend = pendingPort
            if (pend != null && pend.input != port.input) {
                val from = if (truthy(port.input)) pend else port; val to = if (truthy(port.input)) port else pend
                badge.textContent = if (p.connectPanelPorts(arrayOf(from.nodeId, from.name), arrayOf(to.nodeId, to.name))) "Cable connected" else p.statusText(); pendingPort = null
            } else { pendingPort = port; badge.textContent = "${port.nodeId} / ${port.name}" }
        }
        paintInspector(); paintTree()
    }

    private fun paintTree() {
        val resp = response ?: return; val filter = str(query.value).lowercase()
        val sceneNodes = arr(resp.scene.nodes)
        val key = JSON.stringify(arrayOf(selected, filter, sceneNodes.map { n -> arrayOf(n.id, n.title, n.type, n.level, n.color, n.ports.length) }.toTypedArray()))
        if (key == treeKey) return; treeKey = key; tree.asDynamic().replaceChildren()
        for (n in sceneNodes) {
            if (filter.isNotEmpty() && !"${n.id} ${n.title} ${n.type}".lowercase().contains(filter)) continue
            val b = el("button", "sg-tree-node"); b.setAttribute("aria-pressed", (n.id == selected).toString()); b.title = str(n.id); b.asDynamic().dataset["nodeId"] = str(n.id)
            b.style.paddingLeft = "${12 + minOf(num(n.level).toInt(), 8) * 12}px"
            val mark = el("span", "sg-node-mark"); mark.style.background = str(n.color)
            b.append(mark, el("span", "sg-tree-title", str(n.title)), el("small", "", str(n.ports.length)))
            b.onclick = { select(str(n.id), null); renderer!!.focus(str(n.id)) }; tree.append(b)
        }
        if (tree.children.length == 0) tree.append(el("p", "sg-empty", "No matching nodes"))
    }

    private fun section(title: String): HTMLElement { val s = el("section", "sg-inspector-section"); s.append(el("h3", "", title)); inspector.append(s); return s }

    private fun paintInspector() {
        releaseParams(); inspector.asDynamic().replaceChildren()
        val n = node(selected); val geometry = arr(response?.scene?.nodes).firstOrNull { it.id == selected }
        val top = el("header", "sg-inspector-title")
        top.append(el("strong", "", if (n != null) str(p.contract(n.type)?.title ?: n.type) else "Scene"))
        if (n != null) top.append(command("Close inspector", "x") { select(null, null) })
        inspector.append(top)
        if (n == null) {
            val s = section("Document"); s.append(el("p", "sg-document-name", str(p.panelName().value).ifEmpty { "Untitled" }))
            val sceneNodes = arr(response?.scene?.nodes)
            for ((label, value) in listOf("Nodes" to sceneNodes.size, "Cables" to arr(response?.scene?.cables).size, "Measured surfaces" to sceneNodes.count { truthy(it.measured) })) {
                val r = el("div", "sg-stat"); r.append(el("span", "", label), el("b", "", value.toString())); s.append(r)
            }
            section("Evidence").append(el("p", "sg-muted", "No exact node bindings")); return
        }
        val meta = section("Identity"); meta.append(el("code", "sg-id", str(n.id)), el("p", "sg-muted", str(n.type)))
        if (geometry != null) { val r = el("div", "sg-stat"); r.append(el("span", "", "Containment depth"), el("b", "", str(geometry.level))); meta.append(r) }
        val actions = el("div", "sg-actions")
        actions.append(command("Open editor", "pencil", true) { editNode(str(n.id)) }, command("Frame node", "search") { renderer!!.focus(str(n.id)) },
            command("Delete node", "trash-2") { releaseParams(); p.removeNode(n.id); select(null, null) })
        meta.append(actions)
        val params = n.el.querySelector(":scope > .params")
        if (params != null && num(params.children.length) > 0) {
            val s = section("Parameters"); val r = params.getBoundingClientRect(); val scale = num(n.el.getBoundingClientRect().width) / num(n.el.offsetWidth)
            val placeholder = el("div", "sg-params-placeholder"); placeholder.style.height = "${num(r.height) / maxOf(scale, .00001)}px"
            params.replaceWith(placeholder); s.append(params); heldParams = params to placeholder.asDynamic()
        }
        val ports = section("Ports")
        val gp = arr(geometry?.ports)
        for (pt in gp) {
            val r = el("button", "sg-port")
            r.setAttribute("aria-pressed", (pendingPort?.nodeId == n.id && pendingPort?.name == pt.name && pendingPort?.input == pt.input).toString())
            r.title = (if (truthy(pt.input)) "Input" else "Output") + " " + str(pt.name)
            r.append(el("span", if (truthy(pt.input)) "sg-port-dot input" else "sg-port-dot"), el("span", "", str(pt.name)), el("code", "", str(if (truthy(pt.kind)) pt.kind else "unresolved")))
            r.onclick = { select(str(n.id), pt) }; ports.append(r)
        }
        if (gp.isEmpty()) ports.append(el("p", "sg-muted", "No ports"))
        val cables = arr(p.graph.wires).filter { it.from[0] == n.id || it.to[0] == n.id }
        if (cables.isNotEmpty()) {
            val s = section("Connections")
            for (w in cables) {
                val r = el("div", "sg-connection")
                r.append(el("code", "", "${w.from[0]}:${w.from[1]} → ${w.to[0]}:${w.to[1]}"),
                    command("Disconnect cable", "x") { p.graph.wires = arr(p.graph.wires).filter { it !== w }.toTypedArray(); p.redraw(); p.save() })
                s.append(r)
            }
        }
        val alignment = arr(response?.alignment?.nodes).firstOrNull { it.id == n.id }; val s = section("Alignment candidates")
        for ((label, values) in listOf("Rete" to alignment?.watchedBy, "Causal rules" to alignment?.causalRules, "KIF" to alignment?.facts))
            s.append(el("h4", "", label), el("pre", "", arr(values).joinToString("\n") { str(it) }.ifEmpty { "None" }))
        section("Epistemic bindings").append(el("p", "sg-muted", "Not supplied")); icons()
    }

    override fun moveNode(id: String, dx: Double, dy: Double, commit: Boolean) {
        val n = node(id) ?: return
        val scale = num(n.el.getBoundingClientRect().width) / num(p.view.z) / num(n.el.offsetWidth)
        n.x = num(n.x) + dx / scale; n.y = num(n.y) + dy / scale; n.el.style.left = "${n.x}px"; n.el.style.top = "${n.y}px"
        if (truthy(n._parentScope)) p.growRingWorldFor(n)
        p.redraw(); if (commit) p.save() else schedule()
    }

    override fun viewChanged() {}

    override fun unavailable(message: String) { useProvider("canvas", false); fallback = message; providerStatus() }

    private fun report(error: dynamic) { badge.textContent = if (truthy(error?.message)) str(error.message) else str(error); badge.asDynamic().dataset["error"] = "true" }

    private fun providerStatus() {
        providerState.textContent = backend.uppercase(); providerState.asDynamic().dataset["fallback"] = fallback.isNotEmpty().toString()
        providerState.title = fallback.ifEmpty { "Active renderer: ${backend.uppercase()}" }
        provider.value = backend
        val resp = response
        if (resp != null) {
            val scene = resp.scene; val issues = arr(scene.issues).size
            badge.textContent = "${backend.uppercase()} · ${arr(scene.nodes).size} nodes · ${arr(scene.cables).size} cables" +
                (if (issues > 0) " · $issues unprojected" else "") + (if (fallback.isNotEmpty()) " · $fallback" else "")
            badge.asDynamic().dataset["error"] = fallback.isNotEmpty().toString()
        }
    }

    private fun useProvider(value: String, persist: Boolean = true) {
        if (persist) try { localStorage.setItem("spacegraph.renderer", value) } catch (e: dynamic) {}
        try { renderer!!.setBackend(value); backend = value; fallback = "" }
        catch (error: Throwable) { backend = "canvas"; renderer!!.setBackend("canvas"); fallback = "${value.uppercase()} unavailable: ${error.message}" }
        providerStatus()
    }

    private fun refresh() {
        val r = renderer
        if (!open || r == null) return
        try {
            val identity = p.nodes().joinToString("|") { str(it.id) }; val doc = p.serialize(); val name = str(p.panelName().value).ifEmpty { "canvas" }
            val geometry = measurements(); val key = JSON.stringify(arrayOf(name, doc, geometry))
            if (key == projectionKey && !resetCamera && !dirtyAlignment) { providerStatus(); return }
            val scene = if (key == projectionKey && !resetCamera) response.scene else r.project(name, doc, geometry, 0.0, resetCamera || lastIdentity != identity)
            projectionKey = key
            val next = obj(); next.scene = scene; next.alignment = if (lastIdentity == identity) response?.alignment else null
            response = next; lastIdentity = identity; resetCamera = false
            providerStatus()
            if (!truthy(inspector.contains(document.activeElement))) paintInspector(); paintTree()
            if (dirtyAlignment) {
                dirtyAlignment = false; request?.abort(); val active = jsNew(js("AbortController")); val current = ++serial; request = active
                val body = spread(doc); body.name = name
                val init = jsonInit("POST", JSON.stringify(body)); init.signal = active.signal
                launchJs {
                    try {
                        val reply = fetchJs("/api/lcnc/rdf/align", init); val b = awaitJs(reply.json())
                        if (!truthy(reply.ok)) throw Error(str(b.error ?: "Alignment failed (${reply.status})"))
                        if (current == serial && open) { response.alignment = b; if (!truthy(inspector.contains(document.activeElement))) paintInspector() }
                    } catch (e: CancellationException) { throw e } catch (error: dynamic) {
                        if (current == serial && error.name != "AbortError") { dirtyAlignment = true; report(error) }
                    } finally { if (request === active) request = null }
                }
            }
        } catch (e: CancellationException) { throw e } catch (error: dynamic) { report(error) }
    }

    fun schedule(alignment: Boolean = false) {
        dirtyAlignment = dirtyAlignment || alignment
        if (!open || timer != 0) return
        timer = window.setTimeout({ timer = 0; refresh() }, 20)
    }

    fun install() {
        window.addEventListener("lcnc:shadow-update", { schedule(true) })
        window.addEventListener("pagehide", { releaseParams(); request?.abort(); window.clearTimeout(timer); observer?.disconnect(); renderer?.destroy() })
        if (byId("spacegraphBtn") == null) return
        tools = el("div", "sg-tools"); tools.id = "sg-tools"; tools.hidden = true; document.body!!.append(tools)
        val modes = el("div", "sg-modes"); modes.setAttribute("role", "group"); modes.setAttribute("aria-label", "Workspace view")
        editButton = command("Editor", "panels-top-left", true) { activate(false) }; editButton.id = "sg-editor"
        modeButton = byId("spacegraphBtn"); modeButton.title = "Flat SpaceGraph projection preview"; modeButton.setAttribute("aria-label", "SpaceGraph projection")
        modeButton.onclick = { activate(!open) }; modes.append(editButton); tools.append(modes)
        val spatial = el("div", "sg-spatial-tools"); spatial.asDynamic().dataset["spatial"] = ""
        provider = el("select"); provider.setAttribute("aria-label", "Rendering provider")
        for (value in listOf("gl", "canvas", "svg")) { val o = el("option", "", value.uppercase()).asDynamic(); o.value = value; provider.append(o) }
        provider.onchange = { useProvider(str(provider.value)) }
        val fit = command("Frame scene", "search") { resetCamera = true; schedule() }; fit.id = "sg-fit"
        lateinit var move: HTMLElement
        move = command("Move nodes", "pencil") { val on = renderer!!.mode != "move"; renderer!!.mode = if (on) "move" else "pan"; move.setAttribute("aria-pressed", on.toString()) }
        move.id = "sg-move"; move.setAttribute("aria-pressed", "false")
        providerState = el("span", "sg-provider", "SVG"); providerState.setAttribute("role", "status"); providerState.setAttribute("aria-label", "Active renderer")
        spatial.append(providerState, provider, fit, move, command("Zoom in", "plus") { renderer!!.zoom(1.3) }, command("Zoom out", "minus") { renderer!!.zoom(1 / 1.3) }); tools.append(spatial)
        workspace = el("main", "sg-workspace"); workspace.id = "spacegraph-shadow"; workspace.hidden = true
        val navigator = el("nav", "sg-navigator"); navigator.setAttribute("aria-label", "Scene nodes"); val nh = el("header"); nh.append(el("strong", "", "Nodes")); navigator.append(nh)
        query = el("input"); query.type = "search"; query.placeholder = "Find a node"; query.setAttribute("aria-label", "Find a node"); query.oninput = { paintTree() }
        navigator.append(query); tree = el("div", "sg-tree"); navigator.append(tree)
        surface = el("div", "sg-surface"); surface.id = "sg-surface"; surface.setAttribute("aria-label", "Spatial canvas"); surface.tabIndex = 0
        inspector = el("aside", "sg-inspector"); inspector.setAttribute("aria-label", "Node inspector"); workspace.append(navigator, surface, inspector); document.body!!.append(workspace)
        badge = el("div", "sg-badge", "Preparing scene"); badge.setAttribute("role", "status"); surface.append(badge)
        spatial.append(command("Node inspector", "panels-top-left") {
            if (window.matchMedia("(max-width:650px)").matches) document.body!!.classList.toggle("sg-inspecting") else document.body!!.classList.toggle("sg-inspector-hidden")
        })
        try {
            renderer = SpatialRenderer(surface, this)
            var preferred = "svg"
            try { val value = localStorage.getItem("spacegraph.renderer"); if (value in listOf("gl", "canvas", "svg")) preferred = value!! } catch (e: dynamic) {}
            useProvider(preferred, false)
        } catch (error: Throwable) { report(error); activate(false); return }
        jsNew(js("ResizeObserver"), { _: dynamic -> schedule() }).observe(surface)
        val world = byId("world")
        observer = jsNew(js("MutationObserver"), { records: dynamic ->
            if (arr(records).any { r ->
                    val target = if (r.target.nodeType == 1) r.target else r.target.parentElement
                    if (target?.closest("#wires") != null || (r.type == "attributes" && target === world)) false
                    else r.type != "childList" || !(arr(js("Array.from(r.addedNodes)")) + arr(js("Array.from(r.removedNodes)"))).all { n -> n.nodeType == 1 && truthy(n.matches(".params,.sg-params-placeholder")) }
                }) schedule()
        })
        val oo = obj(); oo.attributes = true; oo.attributeFilter = arrayOf("class", "style"); oo.childList = true; oo.subtree = true; oo.characterData = true
        observer.observe(world, oo)
        on(byId("fitBtn"), "click", { if (open) { resetCamera = true; schedule() } })
        val api = obj()
        api.select = { id: String?, port: dynamic -> select(id, port) }
        api.activate = { v: Boolean -> activate(v) }
        api.refresh = { schedule(true) }
        api.measurements = { measurements() }
        val self = this
        js("Object.defineProperties")(api, objOf { d ->
            d.selected = objOf { it.get = { self.selected } }
            d.scene = objOf { it.get = { self.response?.scene } }
            d.renderer = objOf { it.get = { self.renderer } }
            d.backend = objOf { it.get = { self.backend } }
        })
        window.asDynamic().SpaceGraphWorkspace = api
        icons(); activate(org.w3c.dom.url.URLSearchParams(window.location.search).get("spacegraph") == "1")
    }
}
