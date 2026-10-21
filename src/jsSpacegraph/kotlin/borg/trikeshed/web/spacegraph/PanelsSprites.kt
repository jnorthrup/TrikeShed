package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchKinds
import kotlinx.browser.document
import kotlinx.browser.window

/** Node-local presentation of the common contracts and mating algebra; never an executor. */
class PanelsSprites(val panels: Panels) {
    var dismiss: (() -> Unit)? = null

    fun close() { dismiss?.invoke() }

    fun attach(node: dynamic, header: dynamic) {
        val button = document.createElement("button").asDynamic()
        button.type = "button"; button.className = "node-sprite"; button.dataset.sprite = node.id
        button.setAttribute("aria-label", "Inspect ${node.id} connections and content")
        button.setAttribute("aria-haspopup", "dialog"); button.setAttribute("aria-expanded", "false")
        button.title = "Connections, content and nearby mates"
        button.innerHTML = "<svg viewBox='0 0 32 28' aria-hidden='true'><path d='M16 6V2m-3 0h6M5 14H1m30 0h-4M9 23v3m14-3v3'/><rect x='5' y='6' width='22' height='17' rx='6'/><circle cx='12' cy='13' r='1.5'/><circle cx='20' cy='13' r='1.5'/><path d='M12 18h8'/></svg>"
        header.insertBefore(button, header.querySelector("s"))
        panels.page.on(button, "pointerdown", { it.stopPropagation() })
        panels.page.on(button, "keydown", { if (it.key == "Escape") close(); it.stopPropagation() })
        panels.page.on(button, "click", { event -> event.stopPropagation(); open(node, button) })
    }

    fun program(): LcncProgram = LcncProgramConfix.fromJson(
        str(panels.panelName().value).ifBlank { "canvas" }, JSON.stringify(panels.toConfix()))

    fun open(node: dynamic, trigger: dynamic) {
        close()
        if (panels.page.closing || panels.node(node.id) !== node || !truthy(node.el?.isConnected)) return
        panels.blip.leave(null)
        val graph = panels.graph
        val id = str(node.id)
        val owner = node.el
        val panel = document.createElement("section").asDynamic()
        panel.className = "sprite-panel"; panel.dataset.spritePanel = id
        panel.setAttribute("role", "dialog"); panel.setAttribute("aria-label", "LCNC node guide")
        trigger.setAttribute("aria-expanded", "true")
        val releases = mutableListOf<() -> Unit>()
        val highlights = mutableListOf<dynamic>()
        var arrow: dynamic = null
        var closed = false
        var forget: () -> Unit = {}
        fun current(): Boolean = !closed && !panels.page.closing && panels.graph === graph &&
            panels.node(id) === node && node.el === owner && truthy(owner.isConnected)
        fun on(target: dynamic, type: String, handler: (dynamic) -> Unit) {
            panels.page.on(target, type, handler)
            releases.add { panels.page.off(target, type, handler) }
        }
        fun clearHighlight() {
            for (element in highlights) element.classList.remove("sprite-mate", "sprite-port")
            highlights.clear(); arrow?.remove(); arrow = null
        }
        fun closePanel() {
            if (closed) return
            closed = true; dismiss = null; forget(); clearHighlight()
            for (release in releases) release()
            releases.clear(); panel.remove(); owner.classList.remove("sprite-selected")
            trigger.setAttribute("aria-expanded", "false")
            if (!panels.page.closing && truthy(trigger.isConnected)) trigger.focus()
        }
        dismiss = ::closePanel
        forget = panels.page.own(::closePanel)
        fun text(parent: dynamic, tag: String, value: String, cls: String = ""): dynamic {
            val item = document.createElement(tag).asDynamic()
            item.textContent = value; item.className = cls; parent.appendChild(item); return item
        }
        fun section(name: String, attribute: String): dynamic {
            val section = document.createElement("section").asDynamic()
            section.setAttribute(attribute, ""); panel.appendChild(section); text(section, "h3", name)
            return section
        }
        fun button(parent: dynamic, label: String, action: () -> Unit): dynamic {
            val button = text(parent, "button", label)
            button.type = "button"; on(button, "click") { action() }; return button
        }
        val header = document.createElement("header").asDynamic(); panel.appendChild(header)
        text(header, "strong", str(panels.contract(node.type)?.title ?: node.type))
        val closeButton = button(header, "×", ::closePanel)
        closeButton.setAttribute("aria-label", "Close node guide")
        text(panel, "p", "$id · ${node.type}", "sprite-identity")
        val status = text(panel, "p", if (truthy(graph.controls?.inspectionOnly))
            "Wiring specimen · editing allowed; execution disabled. Choose a connection to add."
            else "Inspect connections and content, then choose a connection to add.", "sprite-status")
        status.setAttribute("data-sprite-status", ""); status.setAttribute("role", "status")
        owner.classList.add("sprite-selected")
        on(panel, "pointerdown") { it.stopPropagation() }
        on(panel, "wheel") { it.stopPropagation() }
        on(panel, "keydown") {
            it.stopPropagation()
            if (it.key == "Escape") { it.preventDefault(); closePanel() }
        }
        on(window, "keydown") { if (it.key == "Escape") closePanel() }
        on(window, "resize") { closePanel() }
        on(panels.viewport, "pointerdown") { clearHighlight() }
        on(panels.viewport, "wheel") { clearHighlight() }
        on(window, "lcnc:shadow-update") {
            clearHighlight()
            if (!current()) closePanel()
            else status.textContent = "Canvas changed. Refresh content; connections are checked again when selected."
        }
        val controls = text(panel, "div", "", "sprite-actions")
        button(controls, "Refresh") { if (current()) open(node, trigger) else closePanel() }
        button(controls, "Recorded evidence") { if (current()) { closePanel(); panels.facets.inspectNode(id) } }
        if (node.type == "mux.chat" || panels.contract(node.type)?.params?.model != null ||
            str(node.type).startsWith("council.") || str(node.type).startsWith("tribunal.") || str(node.type).startsWith("brain."))
            button(controls, "Choose provider & model") { if (current()) { closePanel(); panels.openModelSelection(id) } }

        try {
            val frozen = program()
            val contracts = panels.portContracts
            val resolve = panels.portKindResolver(frozen)
            val contract = contracts[str(node.type)]
            val ports = section("Mating surfaces", "data-sprite-ports")
            if (contract?.isEffect == true || truthy(panels.contract(node.type)?.effect))
                text(ports, "p", "This node can have effects when run. Choosing a connection only edits this construction.")
            for ((direction, label) in listOf("in" to "→ Inputs", "out" to "Outputs →")) {
                val portElements = arr(owner.querySelectorAll(":scope > .body .port")).filter { it.dataset.dir == direction }
                text(ports, "h4", label)
                if (portElements.isEmpty()) text(ports, "p", "None", "sprite-muted")
                for (port in portElements) {
                    val name = str(port.dataset.port)
                    val kind = resolve(node, direction, name) ?: "unresolved"
                    val cardinality = contract?.cardinality?.get(PatchKinds.bare(name))
                    text(ports, "p", name + " · " + kind +
                        (if (name.endsWith("?")) " · optional" else "") +
                        (if (cardinality == LcncCardinality.MANY) " · many" else ""), "sprite-port-label")
                }
            }
            val content = section("Content insight", "data-sprite-content")
            when (str(node.type)) {
                "text.value", "json.value" -> {
                    val raw = str(node.params?.value ?: "")
                    text(content, "p", "Authored literal · snapshot when opened", "sprite-muted")
                    if (node.type == "json.value") {
                        if (raw.length > 32_768) text(content, "p", "JSON text · ${raw.length} characters · preview limit 32768")
                        else try {
                            val value = JSON.parse<dynamic>(raw)
                            text(content, "p", shape(value)); text(content, "pre", preview(value))
                        } catch (_: dynamic) { text(content, "p", "Invalid JSON · ${raw.length} characters. Edit the literal before running.") }
                    } else { text(content, "p", "Text · ${raw.length} characters"); text(content, "pre", raw.take(160) + if (raw.length > 160) "…" else "") }
                }
                else -> {
                    if (node._lastOut === undefined || node._lastOut == null) text(content, "p", "No observed output. Inspect a recorded run, or run explicitly when ready.")
                    else if (str(node.type).startsWith("credential.")) text(content, "p", "Credential output is not previewed.")
                    else {
                        text(content, "p", "Last observed output · may belong to an earlier definition · display only", "sprite-muted")
                        text(content, "p", shape(node._lastOut)); text(content, "pre", preview(node._lastOut))
                    }
                }
            }
            val diagnostics = section("Connection diagnostics", "data-sprite-diagnostics")
            val violations = LcncTypeCheck.check(frozen, contracts, strict = false)
                .filter { it.fromNode == id || it.toNode == id }
            if (violations.isEmpty()) text(diagnostics, "p", "No local connection violations. This does not execute or certify the node.", "sprite-muted")
            for (violation in violations.take(16)) text(diagnostics, "p", violation.render(), "sprite-violation")
            if (violations.size > 16) text(diagnostics, "p", "${violations.size - 16} more local violations; inspect recorded evidence for run diagnostics.")

            val mates = section("Nearby connections", "data-sprite-mates")
            fun portElement(n: dynamic, direction: String, name: String): dynamic =
                arr(n.el?.querySelectorAll(":scope > .body .port")).firstOrNull {
                    it.dataset.dir == direction && PatchKinds.bare(str(it.dataset.port)) == PatchKinds.bare(name)
                }
            fun highlight(from: dynamic, fromPort: String, to: dynamic, toPort: String) {
                clearHighlight()
                if (!current() || panels.node(from.id) !== from || panels.node(to.id) !== to) return
                for (n in arrayOf(from, to)) { n.el.classList.add("sprite-mate"); highlights.add(n.el) }
                val source = portElement(from, "out", fromPort); val target = portElement(to, "in", toPort)
                if (source == null || target == null) return
                for (port in arrayOf(source, target)) { port.classList.add("sprite-port"); highlights.add(port) }
                val a = source.getBoundingClientRect(); val b = target.getBoundingClientRect()
                val x1 = num(a.left) + num(a.width) / 2; val y1 = num(a.top) + num(a.height) / 2
                val x2 = num(b.left) + num(b.width) / 2; val y2 = num(b.top) + num(b.height) / 2
                val svg = document.createElementNS("http://www.w3.org/2000/svg", "svg").asDynamic()
                svg.setAttribute("class", "sprite-arrow"); svg.setAttribute("aria-hidden", "true")
                val path = document.createElementNS("http://www.w3.org/2000/svg", "path")
                val bend = maxOf(40.0, kotlin.math.abs(x2 - x1) / 2)
                path.setAttribute("d", "M$x1 $y1 C${x1 + bend} $y1 ${x2 - bend} $y2 $x2 $y2 M${x2 - 8} ${y2 - 5} L$x2 $y2 L${x2 - 8} ${y2 + 5}")
                svg.appendChild(path); document.body!!.appendChild(svg); arrow = svg
            }
            var count = 0
            run {
                val bounds = owner.getBoundingClientRect()
                fun distance(n: dynamic): Double {
                    val b = n.el.getBoundingClientRect()
                    val dx = num(b.left) + num(b.width) / 2 - num(bounds.left) - num(bounds.width) / 2
                    val dy = num(b.top) + num(b.height) / 2 - num(bounds.top) - num(bounds.height) / 2
                    return dx * dx + dy * dy
                }
                val nearby = panels.nodes().filter { it !== node && truthy(it.el?.isConnected) }
                    .sortedBy { distance(it) }.take(8)
                text(mates, "p", "Up to eight nearest nodes · every compatible choice is explicit · hover or focus to trace ports", "sprite-muted")
                for (other in nearby) for (outgoing in arrayOf(true, false)) {
                    val from = if (outgoing) node else other; val to = if (outgoing) other else node
                    val fromId = str(from.id); val toId = str(to.id)
                    val candidates = LcncMating.autoWire(frozen, fromId, toId).candidates
                    if (candidates.isEmpty()) continue
                    val effectLabel = if (arrayOf(from, to).any {
                            contracts[str(it.type)]?.isEffect == true || truthy(panels.contract(it.type)?.effect)
                        }) " · effects when run" else ""
                    if (candidates.size > 1) text(mates, "p", "$fromId → $toId · ${candidates.size} alternatives; choose the intended ports", "sprite-muted")
                    for (candidate in candidates) {
                        count++
                        val choice = button(mates, "Connect $fromId.${candidate.fromPort} → $toId.${candidate.toPort} · ${candidate.kind}$effectLabel") {
                            clearHighlight()
                            if (!current() || panels.node(fromId) !== from || panels.node(toId) !== to) {
                                status.textContent = "Connection not added: the node owner changed. Reopen its guide."
                            } else try {
                                val legal = LcncMating.autoWire(program(), fromId, toId).candidates.any {
                                    it.fromPort == candidate.fromPort && it.toPort == candidate.toPort
                                }
                                if (!legal) status.textContent = "Connection not added: ports or their occupancy changed. Refresh the guide."
                                else {
                                    panels.history.boundary()
                                    if (panels.connectPanelPorts(arrayOf(fromId, candidate.fromPort), arrayOf(toId, candidate.toPort))) {
                                        panels.history.boundary()
                                        open(node, trigger)
                                        panels.status("Connected $fromId.${candidate.fromPort} → $toId.${candidate.toPort}. No execution; undo with ⌘Z.")
                                    } else status.textContent = panels.statusText()
                                }
                            } catch (failure: dynamic) { status.textContent = "Connection not added: " + failureText(failure) }
                        }
                        choice.className = "sprite-choice"
                        choice.dataset.spriteFrom = fromId; choice.dataset.spriteFromPort = candidate.fromPort
                        choice.dataset.spriteTo = toId; choice.dataset.spriteToPort = candidate.toPort
                        on(choice, "pointerenter") { highlight(from, candidate.fromPort, to, candidate.toPort) }
                        on(choice, "focus") { highlight(from, candidate.fromPort, to, candidate.toPort) }
                        on(choice, "pointerleave") { clearHighlight() }
                        on(choice, "blur") { clearHighlight() }
                    }
                }
            }
            if (count == 0) text(mates, "p", "No open legal mates nearby. Add or move a compatible node, or free an occupied port.", "sprite-muted")
        } catch (failure: dynamic) { status.textContent = "Guide unavailable for this construction: " + failureText(failure) }
        document.body!!.appendChild(panel)
        val anchor = trigger.getBoundingClientRect()
        val width = num(panel.offsetWidth)
        val left = if (num(anchor.right) + width + 12 <= window.innerWidth) num(anchor.right) + 8 else num(anchor.left) - width - 8
        panel.style.left = "${maxOf(8.0, minOf(left, window.innerWidth - width - 8))}px"
        panel.style.top = "${maxOf(8.0, minOf(num(anchor.top), window.innerHeight - num(panel.offsetHeight) - 64))}px"
        closeButton.focus()
    }

    fun shape(value: dynamic): String = when {
        value == null -> "null"
        isArray(value) -> "Array · ${value.length} items"
        typeOf(value) == "object" -> "Object · ${keysOf(value).size} fields"
        typeOf(value) == "string" -> "Text · ${str(value).length} characters"
        else -> typeOf(value)
    }

    /** Bounded structural preview; no node parameter map or secret-named field is copied. */
    fun preview(value: dynamic): String {
        val sensitive = Regex("password|secret|token|api.?key|authorization|credential|cookie", RegexOption.IGNORE_CASE)
        fun bounded(v: dynamic, depth: Int, key: String): dynamic {
            if (sensitive.containsMatchIn(key)) return "[redacted]"
            if (v == null) return null
            if (typeOf(v) == "string") return str(v).take(160) + if (str(v).length > 160) "…" else ""
            if (depth >= 2) return shape(v)
            if (isArray(v)) return arr(v).take(3).map { bounded(it, depth + 1, "") }.toTypedArray()
            if (typeOf(v) == "object") {
                val result = obj()
                for (name in keysOf(v).take(6)) result[name] = bounded(v[name], depth + 1, name)
                return result
            }
            return if (typeOf(v) == "number" || typeOf(v) == "boolean") v else typeOf(v)
        }
        val result = str(JSON.stringify(bounded(value, 0, ""), null, 2))
        return result.take(1200) + if (result.length > 1200) "…" else ""
    }
}
