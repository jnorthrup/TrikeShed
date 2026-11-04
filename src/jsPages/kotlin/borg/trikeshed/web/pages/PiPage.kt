package borg.trikeshed.web.pages

import borg.trikeshed.web.PanelsPage
import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.terminal.XtermTerminal
import borg.trikeshed.terminal.VtCell
import borg.trikeshed.terminal.VtColor
import borg.trikeshed.terminal.VtStyle
import borg.trikeshed.terminal.VtSnapshot
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams

/** Pi is an independent host session; its token never enters storage or a Hermes route. */
object PiPage {
    private val page = PanelsPage(::pageFailure)
    private var terminal = XtermTerminal(100, 24)
    private val grid = TerminalGrid(hermes = true)
    private var terminalStream = ""
    private var paintedStream = ""
    private var paintedSession: String? = null
    private var paintedScreen: VtSnapshot? = null
    private var mainAnchor: PiScrollback.Anchor? = null
    private var viewportAnchor: PiScrollback.Anchor? = null
    private fun terminalWrite(text: String, paint: Boolean = true) {
        val input = text.replace("\r\n", "\n").replace("\n", "\r\n")
        terminalStream += input
        terminal.feed(input)
        if (paint) terminalPaint()
    }
    private fun terminalPaint() {
        val screen = terminal.snapshot(2_000)
        val old = paintedScreen
        val same = old != null && paintedSession == sessionId &&
            old.columns == screen.columns && old.rows == screen.rows && terminalStream.startsWith(paintedStream)
        val currentAnchor = if (same) PiScrollback.capture(old!!) else null
        val selected = PiScrollback.captureSelection(old, screen, same)
        if (!same) mainAnchor = null
        val anchor = when {
            !same -> null
            old!!.alternateScreen && !screen.alternateScreen -> mainAnchor.also { mainAnchor = null }
            !old.alternateScreen && screen.alternateScreen -> {
                mainAnchor = currentAnchor
                null
            }
            else -> currentAnchor
        }
        PiScrollback.update(if (screen.alternateScreen) emptySeriesOf() else screen.scrollback,
            if (screen.alternateScreen) emptySeriesOf() else screen.scrollbackIds, selected)
        grid.columns = screen.columns; grid.rows = screen.rows; grid.revision = screen.revision
        grid.cursor = JSON.parse<dynamic>(jsonOf(_m["row" j screen.cursor.row, "column" j screen.cursor.column, "visible" j screen.cursor.visible]))
        grid.lines = JSON.parse<dynamic>(jsonOf(screen.lines.view.map { row -> row.view.map { it.toMap() } }))
        grid.renderAll()
        val screenElement = byId("screen")
        for (r in 0 until screen.rows) screenElement.children.item(r)?.setAttribute("data-row-id", screen.rowIds[r].toString())
        PiScrollback.restore(screen, anchor)
        PiScrollback.restoreSelection(selected)
        paintedScreen = screen
        viewportAnchor = PiScrollback.capture(screen)
        paintedSession = sessionId
        paintedStream = terminalStream
    }
    private fun pageFailure(failure: Throwable) { if (!page.closing) status(failure.message ?: "Pi request failed") }
    private var revision = 0
    private var busy = false
    private var verified = false
    private var sessionId: String? = null
    private var state = "closed"
    private var lastResult = ""
    private var contextId = URLSearchParams(window.location.search).get("context")?.take(128).orEmpty()
    private var contextUnavailable = false
    private val project = URLSearchParams(window.location.search).get("project")?.take(256).orEmpty()
    private val storagePrefix = "trikeshed.pi." + project + "."
    private fun token() = byId("pi-token") as HTMLInputElement
    private fun model() = byId("pi-model") as HTMLSelectElement
    private fun breadcrumb() = byId("pi-breadcrumb") as HTMLSelectElement
    private fun prompt() = byId("pi-prompt") as HTMLTextAreaElement
    private fun button(id: String) = byId(id) as HTMLButtonElement
    private fun status(message: String) { byId("pi-status").textContent = message }
    private fun remember(key: String, value: String?) {
        try { if (value == null) sessionStorage.removeItem(storagePrefix + key) else sessionStorage.setItem(storagePrefix + key, value) } catch (_: Throwable) {}
    }
    private fun recalled(key: String): String? = try { sessionStorage.getItem(storagePrefix + key) } catch (_: Throwable) { null }
    private fun controls() {
        val hasToken = token().value.isNotBlank()
        token().disabled = busy
        button("pi-refresh").disabled = busy || !hasToken
        model().disabled = busy || !verified || sessionId != null
        breadcrumb().disabled = busy || !verified || sessionId != null
        button("pi-start").disabled = busy || !verified || sessionId != null || model().value.isBlank() || contextUnavailable
        button("pi-close").disabled = busy || !verified || sessionId == null || state == "closing"
        button("pi-send").disabled = busy || !verified || sessionId == null || state != "ready" || prompt().value.isBlank()
    }
    private fun contextLink(id: String) {
        val link = byId("pi-context")
        link.hidden = id.isBlank()
        if (id.isNotBlank()) link.setAttribute("href", "/modelmux?context=" + js("encodeURIComponent")(id))
    }
    private suspend fun request(path: String, body: dynamic = null): dynamic {
        val headers = jsonHeaders(); headers.Authorization = "Bearer " + token().value
        val init = if (body == null) requestInit("GET", headers, cache = "no-store")
            else requestInit("POST", headers, JSON.stringify(body), cache = "no-store")
        val response = fetchResponse("/api/pi" + path, init)
        val value = response.jsonValue()
        if (!response.ok) {
            val code = strOrEmpty(value?.error)
            error(when (code) {
                "pi_access_unconfigured" -> "Pi access is not configured. Set PI_ACCESS_TOKEN or PI_ACCESS_TOKEN_SHA256 on the host, then restart Oroboros."
                "authentication_required" -> "The Pi access token was not accepted. Enter the token configured on this host, then refresh models."
                else -> strOrEmpty(value?.detail).ifBlank { code.ifBlank { "Pi is unavailable" } }
            })
        }
        return value
    }
    private fun append(label: String, text: String, toolResult: String? = null, paint: Boolean = true) {
        terminalWrite("\u001b[0m$label\r\n$text\u001b[0m\r\n\r\n", paint)
        val out = byId("pi-transcript")
        if (out.textContent == "No messages yet.") out.textContent = ""
        val item = window.document.createElement("section")
        if (toolResult != null) item.setAttribute("data-pi-tool-result", toolResult)
        val heading = window.document.createElement("strong"); heading.textContent = label
        val body = window.document.createElement("pre"); body.textContent = text
        body.asDynamic().style.cssText = "font:inherit;white-space:pre-wrap;overflow-wrap:anywhere"
        item.appendChild(heading); item.appendChild(body); out.appendChild(item)
    }
    private fun showResult(result: dynamic, session: String, turn: String) {
        val resultId = "$session:$turn"
        if (result == null || turn.isBlank() || resultId == lastResult || !(js("Array").isArray(result.messages) as Boolean)) return
        lastResult = resultId
        byId("pi-transcript").textContent = ""
        terminal = XtermTerminal(100, 24)
        terminalStream = ""
        for (message in jsArray(result.messages)) {
            val role = strOrEmpty(message?.role).ifBlank { "Pi" }
            val content = message?.content
            val text = when {
                jsTypeOf(content) == "string" -> str(content)
                js("Array").isArray(content) as Boolean -> jsArray(content).joinToString("\n") { part ->
                    if (jsTypeOf(part?.text) == "string") str(part.text) else JSON.stringify(part)
                }
                content != null -> JSON.stringify(content)
                else -> ""
            }
            val error = strOrEmpty(message?.errorMessage)
            val label = if (role == "user") "You" else if (role == "toolResult") "Tool · " + strOrEmpty(message?.toolName) + (if (message?.isError == true) " · refused" else " · result") else role
            val tool = strOrEmpty(message?.toolName)
            val display = if (role == "toolResult" && message?.isError != true && tool in setOf("read", "edit")) {
                try {
                    val payload = JSON.parse<dynamic>(text)
                    val path = strOrEmpty(payload?.path)
                    val diff: dynamic = payload?.diff
                    when {
                        path.isBlank() -> text
                        tool == "read" && jsTypeOf(payload?.content) == "string" -> path + "\n\n" + str(payload.content)
                        tool == "edit" && payload?.changed == true && jsTypeOf(diff) == "string" -> str(diff)
                        else -> text
                    }
                } catch (_: Throwable) { text }
            } else text
            if (text.isNotBlank() || error.isNotBlank()) append(label,
                display + if (error.isBlank()) "" else "\n" + error,
                if (role == "toolResult") text else null, paint = false)
        }
        terminalPaint()
    }
    private fun poll(ticket: Int, id: String) {
        page.submit {
            try {
                val value = request("/session/status?sessionId=" + js("encodeURIComponent")(id))
                if (page.closing || ticket != revision || sessionId != id) return@submit
                state = strOrEmpty(value.state).ifBlank { "unknown" }
                val context = strOrEmpty(value.contextId)
                contextLink(if (value.contextRetained == true) context else "")
                if (jsTypeOf(value.model) == "string") model().value = str(value.model)
                if (js("Array").isArray(value.workspaceFiles) as Boolean && jsArray(value.workspaceFiles).isNotEmpty()) {
                    byId("pi-project").hidden = false
                    byId("pi-project").textContent = "Host source grant · " + jsArray(value.workspaceFiles).joinToString(", ") { str(it) }
                }
                showResult(value.result, id, strOrEmpty(value.turnId))
                val detail = strOrEmpty(value.error?.detail)
                status("Pi session · " + state + (if (detail.isBlank()) "" else " · " + detail))
                if (state == "closed") {
                    // Closing drains work first; a second close releases the retained terminal record.
                    val body = obj(); body.sessionId = id
                    request("/session/close", body)
                    if (page.closing || ticket != revision || sessionId != id) return@submit
                    sessionId = null; remember("session", null)
                }
                controls()
                if (state in setOf("starting", "running", "closing")) page.later(700) { poll(ticket, id) }
            } catch (e: Throwable) {
                if (!page.closing && ticket == revision) {
                    if (e.message == "unknown_session") {
                        sessionId = null; remember("session", null); state = "closed"
                        status("The previous Pi session is no longer available. Select a model to start another.")
                    } else { verified = false; status(e.message ?: "Could not read Pi session") }
                    controls()
                }
            }
        }
    }
    fun mount() {
        terminalWrite("Pi · xterm-256color\r\n")
        if (project.isNotEmpty()) {
            byId("pi-project").hidden = false
            byId("pi-project").textContent = "Project navigation context: $project. It is not automatically attached to this session."
        }
        prompt().value = recalled("draft").orEmpty()
        sessionId = recalled("session")
        page.mount {
            page.on(byId("pi-screen-wrap"), "scroll", {
                PiScrollback.render()
                paintedScreen?.let { viewportAnchor = PiScrollback.capture(it) }
            })
            page.on(byId("pi-screen-wrap"), "mousedown", { event: dynamic ->
                if (event.button == 0 && event.target?.closest(".termrow") != null) PiScrollback.beginSelection()
            })
            page.on(window, "mouseup", { PiScrollback.endSelection() })
            page.on(document, "selectionchange", { PiScrollback.selectionChanged() })
            page.on(window, "resize", {
                paintedScreen?.let { screen ->
                    val selection = PiScrollback.captureSelection(screen, screen, true)
                    PiScrollback.update(if (screen.alternateScreen) emptySeriesOf() else screen.scrollback,
                        if (screen.alternateScreen) emptySeriesOf() else screen.scrollbackIds, selection)
                    PiScrollback.restore(screen, viewportAnchor)
                    PiScrollback.restoreSelection(selection)
                }
            })
            page.on(token(), "input", {
                revision++; busy = false; verified = false; state = "closed"
                status("Refresh models to authenticate this Pi token."); controls()
            })
            page.on(model(), "change", { controls() })
            page.on(breadcrumb(), "change", {
                contextId = breadcrumb().value; contextUnavailable = false
                contextLink(contextId)
                status(if (contextId.isBlank()) "Pi will start with a new breadcrumb context." else "Pi will use the selected retained context.")
                controls()
            })
            page.on(prompt(), "input", { remember("draft", prompt().value); controls() })
            page.on(prompt(), "keydown", { event: dynamic ->
                if (event.key == "Enter" && event.shiftKey != true && event.isComposing != true) {
                    event.preventDefault()
                    if (!button("pi-send").disabled) byId("pi-form").asDynamic().requestSubmit()
                }
            })
            page.on(button("pi-refresh"), "click", {
                val ticket = ++revision; busy = true; verified = false; controls()
                page.submit {
                    try {
                        val value = request("/models")
                        if (page.closing || ticket != revision) return@submit
                        model().textContent = ""
                        val blank = window.document.createElement("option").asDynamic(); blank.value = ""; blank.textContent = "Choose a model"; model().appendChild(blank)
                        for (entry in jsArray(value.models)) if (jsTypeOf(entry?.id) == "string" && str(entry.id).isNotBlank()) {
                            val option = window.document.createElement("option").asDynamic(); option.value = str(entry.id); option.textContent = str(entry.id); model().appendChild(option)
                        }
                        breadcrumb().textContent = ""
                        val fresh = window.document.createElement("option").asDynamic(); fresh.value = ""; fresh.textContent = "New context"; breadcrumb().appendChild(fresh)
                        contextUnavailable = contextId.isNotBlank()
                        for (entry in jsArray(value.contexts)) if (jsTypeOf(entry?.context) == "string" && str(entry.context).isNotBlank()) {
                            val id = str(entry.context)
                            val option = window.document.createElement("option").asDynamic()
                            option.value = id
                            option.textContent = strOrEmpty(entry.model).ifBlank { "Retained context" } + " · " + strOrEmpty(entry.records) + " records · " + id
                            breadcrumb().appendChild(option)
                            if (id == contextId) contextUnavailable = false
                        }
                        if (contextUnavailable) {
                            val missing = window.document.createElement("option").asDynamic()
                            missing.value = contextId; missing.textContent = "Unavailable context · " + contextId; missing.disabled = true
                            breadcrumb().appendChild(missing)
                        }
                        breadcrumb().value = contextId
                        contextLink(if (contextUnavailable) "" else contextId)
                        verified = true
                        status(if (contextUnavailable) "The selected breadcrumb context is unavailable. Choose another context or New context."
                            else if (value.available == true && model().options.length > 1) "Select a model to start Pi." else "No Pi models are available from this host.")
                        sessionId?.let { poll(ticket, it) }
                    } catch (e: Throwable) { if (!page.closing && ticket == revision) status(e.message ?: "Pi is unavailable") }
                    finally { if (!page.closing && ticket == revision) { busy = false; controls() } }
                }
            })
            page.on(button("pi-start"), "click", {
                val ticket = ++revision; busy = true; controls()
                page.submit {
                    try {
                        val body = obj(); body.model = model().value
                        if (breadcrumb().value.isNotBlank()) body.contextId = breadcrumb().value.trim()
                        val value = request("/session/start", body)
                        if (page.closing || ticket != revision) return@submit
                        val id = strOrEmpty(value.sessionId); require(id.isNotBlank()) { "Pi did not return a session ID" }
                        sessionId = id; state = strOrEmpty(value.state); remember("session", id)
                        byId("pi-transcript").textContent = "No messages yet."
                        terminal = XtermTerminal(100, 24); terminalStream = ""; terminalWrite("Pi · xterm-256color\r\n")
                        status("Pi session · " + state); poll(ticket, id)
                    } catch (e: Throwable) { if (!page.closing && ticket == revision) status(e.message ?: "Could not start Pi") }
                    finally { if (!page.closing && ticket == revision) { busy = false; controls() } }
                }
            })
            page.on(byId("pi-form"), "submit", { event: dynamic ->
                event.preventDefault()
                if (button("pi-send").disabled) return@on
                val id = sessionId ?: return@on; val text = prompt().value; val ticket = ++revision
                busy = true; controls()
                page.submit {
                    try {
                        val body = obj(); body.sessionId = id; body.text = text
                        val value = request("/session/prompt", body)
                        if (page.closing || ticket != revision || sessionId != id) return@submit
                        state = strOrEmpty(value.state); prompt().value = ""; remember("draft", null)
                        append("You", text); status("Pi session · " + state); poll(ticket, id)
                    } catch (e: Throwable) { if (!page.closing && ticket == revision) status(e.message ?: "Could not send message") }
                    finally { if (!page.closing && ticket == revision) { busy = false; controls() } }
                }
            })
            page.on(button("pi-close"), "click", {
                val id = sessionId ?: return@on; val ticket = ++revision; busy = true; controls()
                page.submit {
                    try {
                        val body = obj(); body.sessionId = id
                        val value = request("/session/close", body)
                        if (page.closing || ticket != revision || sessionId != id) return@submit
                        state = strOrEmpty(value.state); status("Pi session · " + state)
                        if (state == "closed" || state == "failed") { sessionId = null; remember("session", null) }
                        else poll(ticket, id)
                    } catch (e: Throwable) { if (!page.closing && ticket == revision) status(e.message ?: "Could not close session") }
                    finally { if (!page.closing && ticket == revision) { busy = false; controls() } }
                }
            })
            controls()
        }
    }
    fun close() = page.close()
}

/** Retained terminal rows, rendered as a bounded viewport of positioned cells. */
internal object PiScrollback {
    private var rows: Series<Series<VtCell>> = emptySeriesOf()
    private var ids: Series<Long> = emptySeriesOf()
    private var firstPainted = -1
    private var lastPainted = -1
    private var heightPainted = -1.0
    private var dragging = false

    fun beginSelection() { dragging = true }
    fun endSelection() { dragging = false; render() }
    fun selectionChanged() { if (!dragging && !hasSelection()) render() }
    private fun hasSelection(): Boolean {
        val selection: dynamic = window.asDynamic().getSelection()
        val viewport = byId("pi-screen-wrap").asDynamic()
        return selection != null && selection.isCollapsed != true && selection.anchorNode != null &&
            viewport.contains(selection.anchorNode) && viewport.contains(selection.focusNode)
    }

    private fun color(value: VtColor, fallback: String): String =
        TerminalPalette.color(TerminalPalette.HERMES_ANSI16, value.index, value.rgb?.toLong(), fallback)

    private fun span(style: VtStyle, columns: Int, text: String): HTMLElement {
        val element = document.createElement("span") as HTMLElement
        element.className = "pi-history-run" + if (style.blink) " blink" else ""
        var fg = color(style.foreground, "#d8dce6")
        var bg = color(style.background, "#000")
        if (style.inverse) { val prior = fg; fg = bg; bg = prior }
        element.style.color = if (style.concealed) bg else fg
        element.style.backgroundColor = bg
        element.style.fontWeight = if (style.bold) "700" else "400"
        element.style.opacity = if (style.faint) ".65" else "1"
        element.style.fontStyle = if (style.italic) "italic" else "normal"
        element.style.textDecoration = _l[if (style.underline) "underline" else "", if (style.crossedOut) "line-through" else ""]
            .filter { it.isNotEmpty() }.joinToString(" ")
        element.style.width = "${columns * .61}em"
        element.textContent = text
        return element
    }

    data class Point(val row: String, val column: Int, val text: Boolean, val offset: Int)
    data class Selection(val anchor: Point, val focus: Point, val content: String)
    private var selectionToRestore: Selection? = null

    private fun point(node: dynamic, offset: Int): Point? {
        if (node == null) return null
        val viewport = byId("pi-screen-wrap").asDynamic()
        if (viewport.contains(node) != true) return null
        val element: dynamic = if (node.nodeType == 3) node.parentElement else node
        val row: dynamic = element?.closest(".termrow")
        if (row != null) {
            val id = row.getAttribute("data-row-id") as? String ?: return null
            if (node == row) return Point(id, -1, false, offset)
            var child: dynamic = element
            while (child != null && child.parentElement != row) child = child.parentElement
            if (child == null) return null
            var index = 0
            val count = (row.childNodes.length as Number).toInt()
            while (index < count && row.childNodes.item(index) != child) index++
            if (index == count) return null
            return Point(id, index, node.nodeType == 3, offset)
        }
        // A browser may place a range endpoint at a container boundary rather than in a cell.
        // Canonicalize it to the adjacent materialized row boundary before rebuilding the DOM.
        if (node.nodeType != 1) return null
        fun edge(candidate: dynamic, first: Boolean): dynamic {
            if (candidate == null || candidate.nodeType != 1) return null
            if (candidate.classList.contains("termrow") == true) return candidate
            val matches: dynamic = candidate.querySelectorAll(".termrow")
            val count = (matches.length as Number).toInt()
            return if (count == 0) null else matches.item(if (first) 0 else count - 1)
        }
        val children: dynamic = node.childNodes
        val count = (children.length as Number).toInt()
        for (i in offset.coerceIn(0, count) until count) {
            val found: dynamic = edge(children.item(i), true)
            if (found != null) return Point(found.getAttribute("data-row-id") as String, -1, false, 0)
        }
        for (i in (offset.coerceIn(0, count) - 1) downTo 0) {
            val found: dynamic = edge(children.item(i), false)
            if (found != null) return Point(found.getAttribute("data-row-id") as String, -1, false,
                (found.childNodes.length as Number).toInt())
        }
        return null
    }

    private fun snapshotRows(snapshot: VtSnapshot): List<Pair<Long, Series<VtCell>>> = buildList {
        if (!snapshot.alternateScreen) for (r in 0 until snapshot.scrollback.size) add(snapshot.scrollbackIds[r] to snapshot.scrollback[r])
        for (r in 0 until snapshot.rows) add(snapshot.rowIds[r] to snapshot.lines[r])
    }

    fun captureSelection(old: VtSnapshot?, next: VtSnapshot, same: Boolean): Selection? {
        val value: dynamic = window.asDynamic().getSelection()
        if (value == null || value.rangeCount == 0 || value.anchorNode == null || value.focusNode == null) return null
        val a = point(value.anchorNode, (value.anchorOffset as Number).toInt()) ?: return null
        val b = point(value.focusNode, (value.focusOffset as Number).toInt()) ?: return null
        if (!same || old == null) { value.removeAllRanges(); return null }
        val prior = snapshotRows(old)
        val current = snapshotRows(next)
        val start = prior.indexOfFirst { it.first.toString() == a.row }
        val end = prior.indexOfFirst { it.first.toString() == b.row }
        val nextStart = current.indexOfFirst { it.first.toString() == a.row }
        val nextEnd = current.indexOfFirst { it.first.toString() == b.row }
        val retained = if (start < 0 || end < 0 || nextStart < 0 || nextEnd < 0) false else {
            val before = prior.subList(minOf(start, end), maxOf(start, end) + 1)
            val after = current.subList(minOf(nextStart, nextEnd), maxOf(nextStart, nextEnd) + 1)
            before.map { it.first } == after.map { it.first }
        }
        if (!retained) { value.removeAllRanges(); return null }
        return Selection(a, b, value.toString() as String)
    }

    fun restoreSelection(selection: Selection?) {
        selection ?: return
        fun node(point: Point): dynamic {
            val row: dynamic = byId("pi-screen-wrap").asDynamic().querySelector("[data-row-id='" + point.row + "']") ?: return null
            if (point.column < 0) return row
            val cell: dynamic = row.childNodes.item(point.column) ?: return null
            return if (point.text) cell.firstChild else cell
        }
        selectionToRestore = null
        val value: dynamic = window.asDynamic().getSelection()
        val first = node(selection.anchor)
        val last = node(selection.focus)
        fun fits(node: dynamic, offset: Int): Boolean {
            if (node == null || offset < 0) return false
            val length = if (node.nodeType == 3) (node.length as Number).toInt() else (node.childNodes.length as Number).toInt()
            return offset <= length
        }
        if (!fits(first, selection.anchor.offset) || !fits(last, selection.focus.offset)) {
            value.removeAllRanges()
            return
        }
        value.setBaseAndExtent(first, selection.anchor.offset, last, selection.focus.offset)
        if (value.toString() != selection.content) value.removeAllRanges()
    }

    fun update(value: Series<Series<VtCell>>, identities: Series<Long>, selection: Selection?) {
        selectionToRestore = selection
        rows = value
        ids = identities
        firstPainted = -1
        lastPainted = -1
        render()
    }

    data class Anchor(val id: Long?, val fraction: Double, val follow: Boolean)

    private fun height(): Double {
        val row = byId("pi-scrollback").querySelector(".termrow") ?: byId("screen").querySelector(".termrow")
        val measured = row?.let { window.getComputedStyle(it).height.removeSuffix("px").toDoubleOrNull() }
        val font = window.getComputedStyle(byId("pi-scrollback")).fontSize.removeSuffix("px").toDoubleOrNull() ?: 14.0
        return (measured ?: font * 1.15).coerceAtLeast(1.0)
    }

    private fun position(element: HTMLElement): Double {
        val viewport = byId("pi-screen-wrap").asDynamic()
        val outer = viewport.getBoundingClientRect()
        val inner = element.asDynamic().getBoundingClientRect()
        val cell = byId("screen").querySelector(".cell") ?: byId("pi-scrollback").querySelector(".pi-history-run")
        val cssWidth = cell?.let { window.getComputedStyle(it).width.removeSuffix("px").toDoubleOrNull() }
        val scaledWidth = cell?.let { (it.asDynamic().getBoundingClientRect().width as Number).toDouble() }
        val scale = if (cssWidth != null && cssWidth > 0 && scaledWidth != null && scaledWidth > 0) scaledWidth / cssWidth else 1.0
        return (viewport.scrollTop as Number).toDouble() + ((inner.top as Number).toDouble() - (outer.top as Number).toDouble()) / scale + 5.0
    }

    fun capture(snapshot: VtSnapshot): Anchor {
        val viewport = byId("pi-screen-wrap").asDynamic()
        val top = (viewport.scrollTop as Number).toDouble()
        val follow = (viewport.scrollHeight as Number).toDouble() - (viewport.clientHeight as Number).toDouble() - top <= 4.0
        val history = if (snapshot.alternateScreen) emptySeriesOf() else snapshot.scrollbackIds
        val h = height()
        val screenTop = position(byId("screen"))
        val historyTop = position(byId("pi-scrollback"))
        val inHistory = history.size > 0 && top < screenTop
        val source = if (inHistory) history else snapshot.rowIds
        val origin = if (inHistory) historyTop else screenTop
        val index = kotlin.math.floor((top - origin) / h).toInt().coerceIn(0, source.size - 1)
        return Anchor(source[index], (top - (origin + index * h)) / h, follow)
    }

    fun restore(snapshot: VtSnapshot, anchor: Anchor?) {
        val viewport = byId("pi-screen-wrap").asDynamic()
        if (anchor == null || anchor.follow) {
            viewport.scrollTop = viewport.scrollHeight
            render()
            return
        }
        val history = if (snapshot.alternateScreen) emptySeriesOf() else snapshot.scrollbackIds
        val historyIndex = (0 until history.size).firstOrNull { history[it] == anchor.id }
        val liveIndex = (0 until snapshot.rowIds.size).firstOrNull { snapshot.rowIds[it] == anchor.id }
        val origin = if (historyIndex != null || liveIndex == null && history.size > 0) position(byId("pi-scrollback")) else position(byId("screen"))
        val index = historyIndex ?: liveIndex ?: 0
        viewport.scrollTop = origin + (index + anchor.fraction) * height()
        render()
    }

    private fun historyRow(r: Int): HTMLElement {
        val line = rows[r]
        val row = document.createElement("div") as HTMLElement
        row.className = "termrow"
        row.setAttribute("data-row-id", ids[r].toString())
        for (c in 0 until line.size) {
            val cell = line[c]
            val element = span(cell.style, 1, if (cell.continuation) "" else cell.text.ifEmpty { " " })
            if (c + 1 < line.size && line[c + 1].continuation) {
                element.style.position = "relative"; element.style.zIndex = "1"
            }
            row.appendChild(element)
        }
        return row
    }

    fun render() {
        val target = byId("pi-scrollback")
        val viewport = byId("pi-screen-wrap").asDynamic()
        val height = height()
        val top = (viewport.scrollTop as Number).toDouble()
        val viewportHeight = (viewport.clientHeight as Number).toDouble()
        var first = ((top / height).toInt() - 8).coerceIn(0, rows.size)
        var last = (first + kotlin.math.ceil(viewportHeight / height).toInt() + 16).coerceAtMost(rows.size)
        selectionToRestore?.let { selected ->
            for (id in listOf(selected.anchor.row, selected.focus.row)) {
                val index = (0 until ids.size).firstOrNull { ids[it].toString() == id }
                if (index != null) { first = minOf(first, index); last = maxOf(last, index + 1) }
            }
        }
        if ((dragging || hasSelection()) && firstPainted >= 0 && height == heightPainted) {
            first = minOf(first, firstPainted)
            last = maxOf(last, lastPainted)
            val before = target.firstChild as HTMLElement
            val after = target.lastChild as HTMLElement
            for (r in firstPainted - 1 downTo first) target.insertBefore(historyRow(r), before.nextSibling)
            for (r in lastPainted until last) target.insertBefore(historyRow(r), after)
            before.style.height = "${first * height}px"
            after.style.height = "${(rows.size - last) * height}px"
            firstPainted = first; lastPainted = last
            return
        }
        if (first == firstPainted && last == lastPainted && height == heightPainted) return
        firstPainted = first
        lastPainted = last
        heightPainted = height
        val fragment = document.createDocumentFragment()
        val before = document.createElement("div") as HTMLElement
        before.style.height = "${first * height}px"
        fragment.appendChild(before)
        for (r in first until last) fragment.appendChild(historyRow(r))
        val after = document.createElement("div") as HTMLElement
        after.style.height = "${(rows.size - last) * height}px"
        fragment.appendChild(after)
        target.setAttribute("data-rows", rows.size.toString())
        target.asDynamic().replaceChildren(fragment)
    }
}
