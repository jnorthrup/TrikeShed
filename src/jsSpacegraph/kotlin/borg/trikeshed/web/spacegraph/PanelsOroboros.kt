package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/** Oroboros observes the local execution owner; a concentric network remains its own domain. */
fun Panels.openOroboros() {
    if (page.closing) return
    byId("panelOroboros")?.querySelector("[data-oroboros-close]")?.click()
    PanelsOroboros(this).open()
}

class PanelsOroboros(val panels: Panels) {
    internal val page get() = panels.page
    val panel = document.createElement("section").asDynamic()
    val focus = document.activeElement.asDynamic()
    val bindings = mutableListOf<() -> Unit>()
    var release: () -> Unit = {}
    var closed = false
    var timer = 0
    var reading = false
    var sending = false
    var available = false
    var generation = 0
    var runId = ""
    var nodeId = ""
    var scope = emptyArray<String>()
    var detail: dynamic = null
    var initialized = ""
    var inventoryKey = ""
    var rendered = ""

    fun element(tag: String, text: String = "", parent: dynamic = panel): dynamic {
        val value = document.createElement(tag).asDynamic()
        value.textContent = text
        parent.appendChild(value)
        return value
    }

    fun slot(name: String): dynamic = panel.querySelector("[data-oroboros-$name]")
    fun mark(value: dynamic, name: String): dynamic { value.setAttribute("data-oroboros-$name", ""); return value }
    fun on(target: dynamic, event: String, action: (dynamic) -> Unit) {
        page.on(target, event, action)
        bindings.add { page.off(target, event, action) }
    }
    fun button(label: String, parent: dynamic, attribute: String, value: String = ""): dynamic {
        val button = element("button", label, parent)
        button.type = "button"; button.setAttribute(attribute, value)
        return button
    }
    fun field(label: String, name: String, parent: dynamic, multiline: Boolean = false): dynamic {
        val holder = element("label", label, parent)
        val field = mark(element(if (multiline) "textarea" else "input", parent = holder), name)
        field.setAttribute("aria-label", label)
        if (multiline) field.value = "{}"
        return field
    }
    fun json(value: dynamic): String {
        val text = if (value == null) "No captured value." else JSON.stringify(value, null, 2)
        return text.take(24_576) + if (text.length > 24_576) "\n… display truncated" else ""
    }

    fun open() {
        panel.id = "panelOroboros"
        panel.setAttribute("role", "dialog"); panel.setAttribute("aria-label", "Oroboros execution viewer")
        panel.style.cssText = "position:fixed;inset:54px 14px 18px;z-index:70;overflow:auto;background:var(--panel,#11151e);color:var(--ink,#d8dce6);border:1px solid var(--wire,#526077);border-radius:10px;padding:16px;box-shadow:0 12px 40px #0009;font:12px/1.5 var(--sans,sans-serif)"
        element("style", """
            #panelOroboros *{box-sizing:border-box}
            #panelOroboros h2,#panelOroboros h3{margin:0 0 8px}
            #panelOroboros button{font:inherit;margin:3px;padding:5px 8px;cursor:pointer}
            #panelOroboros button:disabled{opacity:.45;cursor:default}
            #panelOroboros label{display:block;margin:8px 0}
            #panelOroboros input,#panelOroboros select,#panelOroboros textarea{display:block;width:100%;font:inherit;margin:3px 0}
            #panelOroboros textarea{min-height:70px}
            #panelOroboros pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:250px;overflow:auto;background:#0003;padding:8px}
            #panelOroboros [data-oroboros-layout]{display:grid;grid-template-columns:210px minmax(220px,1fr) minmax(270px,.8fr);gap:14px}
            #panelOroboros [data-oroboros-runs] button{display:block;width:100%;text-align:left;overflow-wrap:anywhere}
            #panelOroboros [aria-current=true]{outline:2px solid var(--cyan,#57d3ff)}
            #panelOroboros [data-oroboros-card]{border:1px solid var(--wire,#526077);border-radius:8px;margin:8px 0;padding:9px;overflow-wrap:anywhere}
            #panelOroboros [data-executing=true]{border:2px solid var(--warn,#ffb02e);background:#ffb02e12}
            #panelOroboros [data-oroboros-children]{margin-left:12px;border-left:2px solid var(--wire,#526077);padding-left:8px}
            #panelOroboros [data-oroboros-layout]>section{min-width:0}
            @media(max-width:900px){#panelOroboros [data-oroboros-layout]{grid-template-columns:1fr}#panelOroboros [data-oroboros-runs]{max-height:180px;overflow:auto}}
        """.trimIndent())
        val header = element("div")
        element("h2", "Oroboros · execution viewer", header)
        button("Close viewer", header, "data-oroboros-close")
        button("Refresh", header, "data-oroboros-refresh")
        button("Inspect current work", header, "data-oroboros-current")
        element("p", "Live executions owned by this host. Select a run, scope, and work pointcut. Closing this viewer leaves execution and its monitor running.")
        mark(element("p", "Reading executions…"), "status").setAttribute("role", "status")
        val layout = mark(element("div"), "layout")
        val network = element("section", parent = layout)
        element("h3", "Concentric network work", network)
        mark(element("p", parent = network), "network")
        mark(element("div", parent = network), "runs")
        val work = element("section", parent = layout)
        element("h3", "LCNC execution", work)
        mark(element("div", parent = work), "crumb")
        mark(element("p", parent = work), "execution")
        mark(element("div", parent = work), "work")
        val inspector = element("section", parent = layout)
        element("h3", "Pointcut & state", inspector)
        mark(element("p", "Select an execution.", inspector), "selection")
        mark(element("pre", parent = inspector), "state")
        mark(element("p", parent = inspector), "boundary")
        val controlBar = mark(element("div", parent = inspector), "controls")
        for ((action, label) in arrayOf("pause" to "Pause at next pointcut", "step" to "Step work",
            "continue" to "Resume", "cancel" to "Cancel execution", "source-into" to "Into source",
            "source-over" to "Step over source", "source-out" to "Step out of source"))
            button(label, controlBar, "data-oroboros-action", action)
        val source = mark(element("details", parent = inspector), "source")
        element("summary", "Source pointcut", source)
        mark(element("pre", parent = source), "code")
        val coordinates = mark(element("select", parent = source), "coordinates")
        coordinates.setAttribute("aria-label", "Source coordinates")
        element("option", "Generated source", coordinates).value = "generated"
        element("option", "Authored source", coordinates).value = "authored"
        field("Source line", "line", source).type = "number"
        button("Run to line", source, "data-oroboros-action", "source-line")
        val pointcuts = element("details", parent = inspector)
        pointcuts.open = true; element("summary", "Pointcut selection", pointcuts)
        element("p", "Use the selected work and scope as a breakpoint filter, or stop automatically after failures or skipped work.", pointcuts)
        val automatic = mark(element("select", parent = pointcuts), "automatic")
        automatic.setAttribute("aria-label", "Automatic pointcut")
        for ((value, label) in arrayOf("none" to "Manual pointcuts", "failure" to "On failure", "skip" to "On skipped work"))
            element("option", label, automatic).value = value
        button("Use selected pointcut", pointcuts, "data-oroboros-action", "select")
        button("Clear pointcuts", pointcuts, "data-oroboros-clear")
        mark(element("pre", parent = pointcuts), "pointcuts")
        val intervention = element("details", parent = inspector)
        element("summary", "Inputs & lexical scope", intervention)
        element("p", "At a paused before-node pointcut, patch its inputs and current scope bindings. Changes apply on resume; network ownership is unchanged.", intervention)
        field("Input patch (JSON object)", "inputs", intervention, true)
        field("Scope bindings patch (JSON object)", "scope", intervention, true)
        button("Apply at this pointcut", intervention, "data-oroboros-action", "intervene")
        val monitoring = element("details", parent = inspector)
        monitoring.open = true; element("summary", "Mutation monitor", monitoring)
        element("p", "Logging retains bounded observations. Overload switches to spiking bins. Explicit spiking retains the first event per bin; remaining events are counted. Attachment starts a new interval. This is not a complete mutation log.", monitoring)
        val mode = mark(element("select", parent = monitoring), "mode")
        mode.setAttribute("aria-label", "Monitor mode")
        element("option", "Logging with overload spiking", mode).value = "logging"
        element("option", "Spiking", mode).value = "spiking"
        button("Attach to selection", monitoring, "data-oroboros-action", "monitor-attach")
        button("Detach monitor", monitoring, "data-oroboros-action", "monitor-detach")
        mark(element("p", parent = monitoring), "monitor")
        mark(element("pre", parent = monitoring), "mutations")
        val history = element("details", parent = inspector)
        element("summary", "Intervention outcomes & receipt", history)
        mark(element("pre", parent = history), "outcomes")
        mark(element("p", parent = inspector), "response").setAttribute("role", "status")
        on(panel, "click") { event ->
            val target = event.target?.closest("button")
            if (target != null && truthy(panel.contains(target)) && !truthy(target.disabled)) click(target)
        }
        on(coordinates, "change") { sourceView(resetLine = true) }
        on(panel, "keydown") { event -> if (event.key == "Escape") close(); event.stopPropagation() }
        on(panel, "pointerdown") { it.stopPropagation() }
        on(panel, "wheel") { it.stopPropagation() }
        on(window, "keydown") { if (it.key == "Escape") close() }
        release = page.own { close() }
        document.body!!.appendChild(panel)
        slot("close").focus()
        controls()
        read()
    }

    fun close() {
        if (closed) return
        closed = true; generation++
        page.cancelLater(timer)
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
        panel.remove(); release()
        if (!page.closing && truthy(focus?.isConnected)) focus.focus()
    }

    fun controls() {
        val actions = arr(detail?.debug?.actions)
        val site = detail?.debug?.site
        val names = scopePath()
        val path = arr(site?.path)
        val target = if (nodeId.isEmpty()) path.size >= names.size && names.indices.all { str(path[it]) == names[it] }
            else str(site?.nodeId) == nodeId && atPath(site?.path, names)
        for (button in arr(panel.querySelectorAll("[data-oroboros-action]"))) {
            val action = str(button.getAttribute("data-oroboros-action"))
            button.disabled = closed || !available || sending || !actions.any { str(it) == action } ||
                (action == "intervene" && !target)
            button.hidden = action.startsWith("source-") && !actions.any { str(it) == action }
        }
        slot("clear").disabled = !available || sending || !actions.any { str(it) == "select" }
        slot("current").disabled = currentPath() == null
        for (name in arrayOf("inputs", "scope"))
            slot(name).disabled = !available || sending || !target || !actions.any { str(it) == "intervene" }
        slot("coordinates").querySelector("[value=authored]").disabled = detail?.debug?.site?.authored == null
    }

    fun click(target: dynamic) {
        when {
            target.hasAttribute("data-oroboros-close") == true -> close()
            target.hasAttribute("data-oroboros-refresh") == true -> { page.cancelLater(timer); read() }
            target.hasAttribute("data-oroboros-current") == true -> {
                currentPath()?.let { path ->
                    scope = path; nodeId = str(detail.debug.site.nodeId); render()
                }
            }
            target.hasAttribute("data-oroboros-run") == true -> {
                runId = str(target.getAttribute("data-oroboros-run")); generation++
                nodeId = ""; scope = emptyArray(); detail = null; available = false
                initialized = ""; rendered = ""; slot("response").textContent = ""
                for (name in arrayOf("work", "crumb", "state", "pointcuts", "mutations", "outcomes", "monitor", "boundary"))
                    slot(name).textContent = ""
                slot("selection").textContent = "Reading execution " + runId
                slot("execution").textContent = "Reading selected execution…"; slot("source").hidden = true
                controls(); page.cancelLater(timer); read()
            }
            target.hasAttribute("data-oroboros-node") == true -> {
                nodeId = str(target.getAttribute("data-oroboros-node"))
                scope = arr(JSON.parse<dynamic>(str(target.getAttribute("data-oroboros-node-scope")))).map { str(it) }.toTypedArray()
                render()
            }
            target.hasAttribute("data-oroboros-path") == true -> {
                scope = arr(JSON.parse<dynamic>(str(target.getAttribute("data-oroboros-path")))).map { str(it) }.toTypedArray()
                nodeId = ""; render()
            }
            target.hasAttribute("data-oroboros-clear") == true -> command("select", objOf {
                it.nodeIds = jsArray(); it.scope = null; it.automatic = "none"
            })
            target.hasAttribute("data-oroboros-action") == true -> {
                val action = str(target.getAttribute("data-oroboros-action"))
                try {
                    val payload = obj()
                    when (action) {
                        "select" -> {
                            payload.nodeIds = if (nodeId.isEmpty()) emptyArray<String>() else arrayOf(nodeId)
                            payload.scope = scopePath(); payload.automatic = str(slot("automatic").value)
                        }
                        "intervene" -> {
                            for ((field, name) in arrayOf("inputs" to "inputs", "scope" to "bindings")) {
                                val parsed = JSON.parse<dynamic>(str(slot(field).value))
                                require(parsed != null && typeOf(parsed) == "object" && !isArray(parsed)) { "$name must be a JSON object" }
                                payload[name] = parsed
                            }
                            require(keysOf(payload.inputs).isNotEmpty() || keysOf(payload.bindings).isNotEmpty()) { "Enter an input or scope binding patch" }
                        }
                        "source-line" -> {
                            val line = str(slot("line").value).toIntOrNull()
                            require(line != null && line > 0) { "Choose a positive source line" }
                            payload.line = line; payload.coordinates = str(slot("coordinates").value)
                        }
                        "monitor-attach" -> {
                            payload.scope = scopePath().takeIf { it.isNotEmpty() }?.joinToString("/")
                            payload.pointcut = nodeId.ifEmpty { null }
                            payload.spiking = slot("mode").value == "spiking"
                        }
                    }
                    command(action, payload)
                } catch (failure: dynamic) { slot("response").textContent = "Not submitted: " + failureText(failure) }
            }
        }
    }

    /** One finite page-lane read at a time; close stops admission and admitted reads drain. */
    fun read() {
        if (closed || page.closing || reading || sending) return
        reading = true
        val revision = generation
        val selected = runId
        val completion = page.submit {
            try {
                val response = fetchJs("/api/lcnc/oroboros")
                val index = JSON.parse<dynamic>(PatchRead.text(response))
                require(truthy(response.ok) && index.error == null) { str(index.detail ?: index.error ?: "HTTP ${response.status}") }
                if (closed || page.closing || generation != revision) return@submit
                inventory(index)
                if (selected.isNotEmpty()) {
                    val state = fetchJs("/api/lcnc/oroboros?runId=" + encodeURIComponent(selected))
                    val snapshot = JSON.parse<dynamic>(PatchRead.text(state, 8_388_608))
                    if (closed || page.closing || generation != revision) return@submit
                    require(truthy(state.ok) && snapshot.error == null) { str(snapshot.detail ?: snapshot.error ?: "HTTP ${state.status}") }
                    require(snapshot.runId == selected) { "Execution identity changed" }
                    detail = snapshot; available = true; render()
                }
                slot("status").textContent = "Observing local host · " + arr(index.executions).size +
                    " executions shown · recent limit " + str(index.recentLimit ?: "—") +
                    if (truthy(index.truncated)) " · inventory truncated" else ""
            } catch (failure: CancellationException) { throw failure }
            catch (failure: dynamic) {
                if (!closed && generation == revision) {
                    available = false
                    slot("status").textContent = "Observation unavailable: " + failureText(failure) + ". Last state may be stale."
                }
            } finally {
                reading = false
                if (!closed && !page.closing) {
                    controls()
                    timer = page.later(if (generation == revision) 750 else 0) { read() }
                }
            }
        }
        if (completion == null) {
            reading = false; available = false; controls()
            slot("status").textContent = "Page queue full or closing. Refresh to retry observation."
        }
    }

    fun inventory(value: dynamic) {
        val key = runId + JSON.stringify(value)
        if (inventoryKey == key) return
        inventoryKey = key
        val runs = slot("runs"); runs.textContent = ""
        slot("network").textContent = str(value.coverage ?: "Local execution owner")
        for (run in arr(value.executions)) {
            val entry = button(str(run.program ?: run.runId) + "\n" + str(run.state ?: run.status) +
                "\n" + str(run.subnet ?: "local") + "\n" + str(run.runId), runs, "data-oroboros-run", str(run.runId))
            entry.setAttribute("aria-current", str(run.runId == runId))
        }
        if (arr(value.executions).isEmpty()) element("p", "No retained executions. Run a construction from the existing work network or Panels controls.", runs)
    }

    fun command(action: String, payload: dynamic) {
        if (closed || sending || !available || !arr(detail?.debug?.actions).any { str(it) == action }) return
        val selected = runId
        val revision = ++generation
        payload.runId = selected; payload.ordinal = detail.debug.ordinal; payload.action = action
        val body = JSON.stringify(payload)
        sending = true; available = false; controls()
        slot("response").textContent = "Submitting " + action + " at ordinal " + str(payload.ordinal) + "…"
        val completion = page.submit {
            try {
                val response = fetchJs("/api/lcnc/oroboros", jsonInit("POST", body))
                val result = JSON.parse<dynamic>(PatchRead.text(response, 8_388_608))
                if (closed || page.closing || generation != revision) return@submit
                slot("response").textContent = "HTTP " + str(response.status) + " · " + action +
                    " · " + (if (result.ok == true) "accepted" else str(result.error ?: "response received")) +
                    (if (result.detail == null) "" else "\n" + str(result.detail)) +
                    (if (result.debug?.state == null) "" else "\nObserved state: " + str(result.debug.state))
                if (!truthy(response.ok) || result.error != null) available = false
            } catch (failure: CancellationException) { throw failure }
            catch (failure: dynamic) {
                if (!closed && generation == revision) {
                    available = false
                    slot("response").textContent = "Command outcome unknown: " + failureText(failure) + ". Inspect recorded interventions before retrying."
                }
            } finally {
                sending = false
                if (!closed && !page.closing) { controls(); page.cancelLater(timer); read() }
            }
        }
        if (completion == null) {
            sending = false; controls()
            slot("response").textContent = "Page queue full or closing; command not submitted."
        }
    }

    fun reference(node: dynamic): String? {
        val explicit = node.subprogram ?: if (node.type == "scope") node.params?.program else null
        if (explicit != null && str(explicit).isNotBlank()) return str(explicit)
        val type = str(node.type)
        val programs = detail?.programs ?: return null
        return type.takeIf { programs[type] != null }
    }

    /** Bodies come only from this execution's captured pins, never the latest stored document. */
    fun body(node: dynamic, document: dynamic): dynamic {
        if (arr(node.children).isNotEmpty()) return objOf { it.nodes = node.children; it.wires = document.wires }
        val name = reference(node) ?: return null
        val programs = detail?.programs ?: return null
        return programs[name]
    }

    fun scopePath(path: Array<String> = scope): Array<String> {
        var document = detail?.program
        val names = mutableListOf<String>()
        for (id in path) {
            val node = arr(document?.nodes).firstOrNull { str(it.id) == id } ?: break
            val nested = body(node, document) ?: break
            names.add(reference(node) ?: id)
            document = nested
        }
        return names.toTypedArray()
    }

    fun currentPath(): Array<String>? {
        val site = detail?.debug?.site ?: return null
        if (!isArray(site.path) || site.nodeId == null) return null
        var document = detail.program
        val path = mutableListOf<String>()
        for (name in arr(site.path)) {
            val candidates = arr(document?.nodes).filter { (reference(it) ?: str(it.id)) == str(name) && body(it, document) != null }
            if (candidates.size != 1) return null
            val node = candidates.single()
            path.add(str(node.id)); document = body(node, document)
        }
        if (!arr(document?.nodes).any { it.id == site.nodeId }) return null
        return path.toTypedArray()
    }

    fun atPath(path: dynamic, names: Array<String>): Boolean {
        val segments = arr(path)
        return segments.size == names.size && names.indices.all { str(segments[it]) == names[it] }
    }

    fun sourceView(resetLine: Boolean) {
        val site = detail?.debug?.site
        slot("source").hidden = site?.kind != "source"
        if (site?.kind != "source") { slot("code").textContent = ""; return }
        if (site.authored == null) slot("coordinates").value = "generated"
        val authored = slot("coordinates").value == "authored"
        val source = if (authored) site.authored else site
        val lines = arr(source.sourceLines)
        val start = num(source.lineStart ?: 1).toInt()
        slot("code").textContent = (if (authored) "Authored source" else "Generated source") +
            "\n" + str(source.sourceName ?: site.sourceName ?: "") + "\n" +
            lines.mapIndexed { index, line ->
                (if (start + index == num(source.line).toInt()) "> " else "  ") + (start + index) + "  " + str(line)
            }.joinToString("\n").ifEmpty { str(source.code ?: "Source text unavailable at this pointcut.") }
        slot("line").min = "1"; slot("line").max = str(source.lineCount ?: 1)
        if (resetLine) slot("line").value = str(source.line ?: 1)
    }

    fun render() {
        val program = detail?.program ?: return
        val key = JSON.stringify(detail) + JSON.stringify(scope) + nodeId
        if (rendered == key) { controls(); return }
        rendered = key
        val debug = detail.debug
        val site = debug?.site
        val active = debug?.activeSite ?: site
        val activeNode = str(active?.nodeId ?: "")
        val activePath: dynamic = active?.path
        val activePhase = str(active?.phase ?: active?.kind ?: "pointcut")
        val crumb = slot("crumb"); crumb.textContent = ""
        button("Execution root", crumb, "data-oroboros-path", "[]")
        var document = program
        var nodes = arr(program.nodes)
        var path = emptyArray<String>()
        for (id in scope) {
            val parent = nodes.firstOrNull { str(it.id) == id } ?: break
            val nested = body(parent, document) ?: break
            path += id
            button(reference(parent)?.let { "$id · $it" } ?: id, crumb, "data-oroboros-path", JSON.stringify(path))
            document = nested; nodes = arr(document.nodes)
        }
        val wires = arr(document.wires)
        scope = path
        slot("execution").textContent = str(program.name ?: detail.receipt?.program ?: runId) + " · " + str(debug?.state ?: detail.receipt?.status ?: "unavailable") +
            " · ordinal " + str(debug?.ordinal ?: "—") +
            if (activeNode.isEmpty()) "" else "\nObserved work: " + activeNode + " · " + activePhase
        val work = slot("work"); work.textContent = ""
        fun node(value: dynamic, host: dynamic, parents: Array<String>, document: dynamic) {
            val id = str(value.id)
            val card = mark(element("article", parent = host), "card")
            val names = scopePath(parents)
            val observation = arr(debug?.observations).lastOrNull { str(it.nodeId) == id && atPath(it.path, names) }
            val executing = activeNode == id && atPath(activePath, names) &&
                str(debug?.state) in arrayOf("running", "paused", "pause-requested", "cancel-requested")
            card.setAttribute("data-executing", str(executing))
            card.setAttribute("aria-current", str(nodeId == id && parents.contentEquals(scope)))
            button(id + " · " + str(value.type), card, "data-oroboros-node", id)
                .setAttribute("data-oroboros-node-scope", JSON.stringify(parents))
            if (executing) element("strong", " ● " + str(debug?.state) + " · " + activePhase, card)
            else element("span", " · " + str(observation?.status ?: "not observed") +
                if (observation?.phase == null) "" else " · " + str(observation.phase), card)
            val incoming = arr(document.wires).filter { str(it.to[0]) == id }
            val outgoing = arr(document.wires).filter { str(it.from[0]) == id }
            if (incoming.isNotEmpty()) element("div", "in  " + incoming.joinToString(" · ") { str(it.from[0]) + "." + str(it.from[1]) + " → " + str(it.to[1]) }, card)
            if (outgoing.isNotEmpty()) element("div", "out  " + outgoing.joinToString(" · ") { str(it.from[1]) + " → " + str(it.to[0]) + "." + str(it.to[1]) }, card)
            val children = arr(value.children)
            val nestedDocument = body(value, document)
            if (children.isNotEmpty()) {
                button("Enter scope · " + children.size + " work nodes", card, "data-oroboros-path", JSON.stringify(parents + id))
                val nested = mark(element("div", parent = card), "children")
                for (child in children) node(child, nested, parents + id, nestedDocument)
            } else if (nestedDocument != null)
                button("Enter pinned scope · " + reference(value), card, "data-oroboros-path", JSON.stringify(parents + id))
            else if (reference(value) != null)
                element("p", "Referenced scope has not been captured for this execution.", card)
        }
        for (value in nodes) node(value, work, path, document)
        if (nodes.isEmpty()) element("p", "No authored work in this scope.", work)
        if (num(debug?.observationsDropped ?: 0) > 0)
            element("p", str(debug.observationsDropped) + " older execution observations omitted; unobserved work may have run.", work)
        if (num(debug?.observationsOmitted ?: 0) > 0)
            element("p", str(debug.observationsOmitted) + " execution observations omitted from retained evidence.", work)
        if (num(detail.programsTruncated ?: 0) > 0)
            element("p", str(detail.programsTruncated) + " pinned reference bodies omitted by the snapshot limit.", work)
        if (wires.isNotEmpty()) {
            val connections = element("details", parent = work)
            element("summary", "Scope wires · " + wires.size, connections)
            element("pre", json(wires), connections)
        }
        val selected = nodes.firstOrNull { str(it.id) == nodeId }
        slot("selection").textContent = "Scope: " + scopePath().joinToString(" / ").ifEmpty { "root" } +
            "\nWork: " + nodeId.ifEmpty { "all work" } + "\nRun: " + runId
        val names = scopePath()
        fun inScope(path: dynamic): Boolean {
            val segments = arr(path)
            return segments.size >= names.size && names.indices.all { str(segments[it]) == names[it] }
        }
        val observed = arr(debug?.observations).lastOrNull { str(it.nodeId) == nodeId && atPath(it.path, names) }
        slot("state").textContent = if ((nodeId.isEmpty() && inScope(site?.path)) ||
            (site?.nodeId == nodeId && atPath(site?.path, names))) json(site ?: active)
            else if (nodeId.isEmpty()) "Retained observations in this scope:\n" +
                json(arr(debug?.observations).filter { inScope(it.path) }.toTypedArray())
            else "Authored work:\n" + json(selected) + "\n\nLast retained observation:\n" + json(observed) +
                "\n\nCurrent pointcut: " + str(site?.nodeId ?: "none")
        if (debug?.stateOmitted != null) slot("state").textContent =
            "Retained state omitted: " + str(debug.stateOmitted) + "\n" + str(slot("state").textContent)
        slot("pointcuts").textContent = json(debug?.pointcuts)
        slot("boundary").textContent = if (debug == null) "No debugger snapshot retained for this execution." else
            str(debug.boundary) + ". Pause and cancel take effect at cooperative boundaries; admitted work settles first." +
                if (truthy(detail.timeoutIncludesPauses)) " Execution deadlines continue while paused." else ""
        val identity = runId + ":" + str(debug?.ordinal)
        val changed = initialized != identity
        if (changed) {
            initialized = identity
            slot("inputs").value = "{}"; slot("scope").value = "{}"
        }
        sourceView(resetLine = changed)
        val monitor = debug?.monitor
        slot("monitor").textContent = if (monitor == null) "Monitor unavailable." else
            (if (truthy(monitor.attached)) "Attached" else "Detached") + " · " + str(monitor.mode) +
                "\nObserved " + str(monitor.observed) + " · persistent " + str(monitor.persistent) +
                " · aggregated " + str(monitor.aggregated) + " · overwritten " + str(monitor.overwritten) +
                " · truncated values " + str(monitor.valueTruncations) +
                "\nInterval " + str(monitor.epoch) + " · bin " + str(monitor.binMillis) + " ms · event capacity " + str(monitor.eventCapacity) +
                "\nRetained evidence omissions: " + str(monitor.eventsOmitted ?: 0) + " events · " + str(monitor.binsOmitted ?: 0) + " bins"
        slot("mutations").textContent = if (monitor == null) "" else json(objOf {
            it.scope = monitor.scope; it.pointcut = monitor.pointcut
            val prefix = names.joinToString("/")
            it.events = arr(monitor.events).filter { event ->
                (nodeId.isEmpty() || str(event.pointcut) == nodeId) &&
                    (prefix.isEmpty() || str(event.scope) == prefix || str(event.scope).startsWith("$prefix/"))
            }.toTypedArray()
            it.coverage = "Events filtered to selected work/scope; bins count the entire monitor attachment."
            it.bins = monitor.bins
        })
        slot("outcomes").textContent = json(objOf {
            it.controls = debug?.controls; it.controlsDropped = debug?.controlsDropped
            it.controlsOmitted = debug?.controlsOmitted
            it.interventions = debug?.interventions; it.interventionsDropped = debug?.interventionsDropped
            it.interventionsOmitted = debug?.interventionsOmitted
            it.receipt = detail.receipt; it.coverage = detail.coverage; it.observation = detail.observation
        })
        controls()
    }
}
