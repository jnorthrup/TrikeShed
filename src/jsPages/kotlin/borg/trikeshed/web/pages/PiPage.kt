package borg.trikeshed.web.pages

import borg.trikeshed.web.PanelsPage
import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.media.toMap
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.terminal.XtermTerminal
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams

/** Pi is an independent host session; its token never enters storage or a Hermes route. */
object PiPage {
    private val page = PanelsPage(::pageFailure)
    private val terminal = XtermTerminal(100, 24)
    private val grid = TerminalGrid(hermes = true)
    private fun terminalWrite(text: String) {
        terminal.feed(text.replace("\u001b", "").replace("\r\n", "\n").replace("\n", "\r\n"))
        val screen = terminal.snapshot(0)
        grid.columns = screen.columns; grid.rows = screen.rows; grid.revision = screen.revision
        grid.cursor = JSON.parse<dynamic>(jsonOf(_m["row" j screen.cursor.row, "column" j screen.cursor.column, "visible" j screen.cursor.visible]))
        grid.lines = JSON.parse<dynamic>(jsonOf(screen.lines.view.map { row -> row.view.map { it.toMap() } }))
        grid.renderAll()
    }
    private fun pageFailure(failure: Throwable) { if (!page.closing) status(failure.message ?: "Pi request failed") }
    private var revision = 0
    private var busy = false
    private var verified = false
    private var sessionId: String? = null
    private var state = "closed"
    private var lastResult = ""
    private val project = URLSearchParams(window.location.search).get("project")?.take(256).orEmpty()
    private val storagePrefix = "trikeshed.pi." + project + "."
    private fun token() = byId("pi-token") as HTMLInputElement
    private fun model() = byId("pi-model") as HTMLSelectElement
    private fun breadcrumb() = byId("pi-breadcrumb") as HTMLInputElement
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
        breadcrumb().disabled = busy || sessionId != null
        button("pi-start").disabled = busy || !verified || sessionId != null || model().value.isBlank()
        button("pi-close").disabled = busy || !verified || sessionId == null || state == "closing"
        button("pi-send").disabled = busy || !verified || sessionId == null || state != "ready" || prompt().value.isBlank()
    }
    private suspend fun request(path: String, body: dynamic = null): dynamic {
        val headers = jsonHeaders(); headers.Authorization = "Bearer " + token().value
        val init = if (body == null) requestInit("GET", headers, cache = "no-store")
            else requestInit("POST", headers, JSON.stringify(body), cache = "no-store")
        val response = fetchResponse("/api/pi" + path, init)
        val value = response.jsonValue()
        if (!response.ok) error(strOrEmpty(value?.detail).ifBlank { strOrEmpty(value?.error).ifBlank { "Pi is unavailable" } })
        return value
    }
    private fun append(label: String, text: String, toolResult: String? = null) {
        terminalWrite("$label\r\n$text\r\n\r\n")
        val out = byId("pi-transcript")
        if (out.textContent == "No messages yet.") out.textContent = ""
        val item = window.document.createElement("section")
        if (toolResult != null) item.setAttribute("data-pi-tool-result", toolResult)
        val heading = window.document.createElement("strong"); heading.textContent = label
        val body = window.document.createElement("pre"); body.textContent = text
        body.asDynamic().style.cssText = "font:inherit;white-space:pre-wrap;overflow-wrap:anywhere"
        item.appendChild(heading); item.appendChild(body); out.appendChild(item)
    }
    private fun showResult(result: dynamic, turn: String) {
        if (result == null || turn.isBlank() || turn == lastResult || !(js("Array").isArray(result.messages) as Boolean)) return
        lastResult = turn
        byId("pi-transcript").textContent = ""
        terminal.feed("\u001b[2J\u001b[H")
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
            val label = if (role == "toolResult") "Tool · " + strOrEmpty(message?.toolName) + (if (message?.isError == true) " · refused" else " · result") else role
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
                if (role == "toolResult") text else null)
        }
    }
    private fun poll(ticket: Int, id: String) {
        page.submit {
            try {
                val value = request("/session/status?sessionId=" + js("encodeURIComponent")(id))
                if (page.closing || ticket != revision || sessionId != id) return@submit
                state = strOrEmpty(value.state).ifBlank { "unknown" }
                val context = strOrEmpty(value.contextId)
                val contextLink = byId("pi-context")
                contextLink.hidden = context.isBlank() || value.contextRetained != true
                if (context.isNotBlank()) contextLink.setAttribute("href", "/modelmux?context=" + js("encodeURIComponent")(context))
                if (jsTypeOf(value.model) == "string") model().value = str(value.model)
                if (js("Array").isArray(value.workspaceFiles) as Boolean && jsArray(value.workspaceFiles).isNotEmpty()) {
                    byId("pi-project").hidden = false
                    byId("pi-project").textContent = "Host source grant · " + jsArray(value.workspaceFiles).joinToString(", ") { str(it) }
                }
                showResult(value.result, strOrEmpty(value.turnId))
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
        breadcrumb().value = URLSearchParams(window.location.search).get("context")?.take(128).orEmpty()
        if (project.isNotEmpty()) {
            byId("pi-project").hidden = false
            byId("pi-project").textContent = "Project navigation context: $project. It is not automatically attached to this session."
        }
        prompt().value = recalled("draft").orEmpty()
        sessionId = recalled("session")
        page.mount {
            page.on(token(), "input", {
                revision++; busy = false; verified = false; state = "closed"
                status("Refresh models to authenticate this Pi token."); controls()
            })
            page.on(model(), "change", { controls() })
            page.on(prompt(), "input", { remember("draft", prompt().value); controls() })
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
                        verified = true
                        status(if (value.available == true && model().options.length > 1) "Select a model to start Pi." else "No Pi models are available from this host.")
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
