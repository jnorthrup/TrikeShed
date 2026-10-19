package borg.trikeshed.web.pages

import borg.trikeshed.web.PanelsPage
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.CompositionEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.js.Date
import kotlin.js.Promise

/** Hermes xterm-256color: the GraalPy sleeve terminal served at `/hermes`. */
object HermesTerminalPage {
    private val panelId: String = js("new URLSearchParams(window.location.search).get('panel')") as? String ?: "default"
    private fun endpoint(path: String = ""): String = "/api/hermes/terminal$path?panel=" + js("encodeURIComponent")(panelId)
    private val grid = TerminalGrid(hermes = true)
    private var state = "closed"
    private var boot: dynamic = null
    private var detail = ""
    private var runtimeName = "GraalPy sleeve"
    private var focused = false
    private val page = PanelsPage(::pageFailure)

    private fun pageFailure(failure: Throwable) {
        if (!page.closing) byId("clipboardStatus").textContent = failure.message ?: "Page action failed"
    }

    private fun setState(s: String) {
        if (page.closing) return
        state = s
        byId("state").textContent = s
        byId("state").className = "badge " + (if (s == "ready") "ok" else if (s == "failed" || s == "offline") "bad" else "")
        renderBoot()
    }

    private fun adoptState(x: dynamic) {
        if (jsTypeOf(x.boot) != "undefined") {
            val next: dynamic = x.boot
            val current: dynamic = boot
            if (next != null && current != null && isNumber(next.startedAtMs) && isNumber(current.startedAtMs)) {
                val nextStarted = next.startedAtMs as Double
                val currentStarted = current.startedAtMs as Double
                if (nextStarted < currentStarted) return
                if (nextStarted == currentStarted && isNumber(next.revision) && isNumber(current.revision) &&
                    (next.revision as Double) < (current.revision as Double)) return
            }
            if (next != null || x.state == "closed") boot = next
        }
        if (x.detail != null) detail = str(x.detail)
        if (x.runtime == "hermes-cli" || detail.contains("HermesCLI")) runtimeName = "HermesCLI"
        else if (detail.contains("Python diagnostic console")) runtimeName = "Python diagnostic console"
        setState(if (truthy(x.state)) str(x.state) else state)
    }

    private fun renderBoot() {
        val b: dynamic = boot
        val status = if (b != null && truthy(b.status)) str(b.status) else state
        val phase = if (b != null && truthy(b.phase)) str(b.phase) else "startup"
        val offline = state == "offline"
        val ready = state == "ready" && (b == null || b.steadyState == true)
        val failed = status == "failed" || state == "failed"
        val running = !offline && !ready && !failed && (status == "running" || state == "booting")
        byId("boot").className = if (ready) "ok" else if (failed || offline) "bad" else ""
        byId("bootState").textContent = when {
            offline -> "$runtimeName · offline"
            ready -> "$runtimeName ready"
            failed -> "$phase failed · $runtimeName unavailable"
            running -> "$phase · $runtimeName starting"
            else -> "$runtimeName · $state"
        }
        renderBootTime()
        val module = if (b != null) strOrEmpty(b.module) else ""
        val progress = if (b != null && truthy(b.detail)) str(b.detail) else detail
        byId("bootDetail").textContent = (if (module.isNotEmpty()) "$module · " else "") + progress
        val failure = if (b != null) strOrEmpty(b.failure) else ""
        byId("bootFailure").textContent = if (failure != progress) failure else ""
        val trace = if (b != null) strOrEmpty(b.traceback) else ""
        byId("bootTrace").hidden = trace.isEmpty()
        byId("bootTraceback").textContent = trace
    }

    private fun renderBootTime() {
        if (page.closing) return
        val b: dynamic = boot
        val running = state == "booting" || (b != null && b.status == "running" && state != "failed" && state != "offline")
        val started = if (b != null && isNumber(b.startedAtMs)) b.startedAtMs as Double else 0.0
        val updated = if (b != null && isNumber(b.updatedAtMs)) b.updatedAtMs as Double else started
        val wallSeconds = if (b != null && isNumber(b.wallMillis)) ((b.wallMillis as Double) / 1000).toInt() else 0
        val now = Date.now()
        byId("bootElapsed").textContent = if (started > 0.0) {
            val elapsed = (maxOf(0.0, (if (running) now else updated) - started) / 1000).toInt()
            " · ${elapsed}s elapsed" + if (running && updated > 0.0) {
                " · ${(maxOf(0.0, now - updated) / 1000).toInt()}s since progress" +
                    if (wallSeconds > 0) " · ${wallSeconds}s guest execution limit" else ""
            } else ""
        } else ""
    }

    private fun snapshot(x: dynamic) {
        if (page.closing) return
        val p: dynamic = if (truthy(x.panel)) x.panel else x
        adoptState(x)
        if (grid.adopt(p, x.sequence)) {
            grid.renderAll()
            grid.lastCursorRow = grid.cursor.row
        }
        for (s in jsArray(x.signals)) grid.signal(s)
    }

    /* Line edits, Enter and Reset share one finite commonMain lane. A rejected or
       ambiguous edit poisons subsequent input: never execute a truncated command. */
    private val input = page.lane(workers = 1, maxActions = 64)
    private var inputFailure: Throwable? = null

    private fun post(body: dynamic, path: String = "/input", accepted: suspend () -> Unit = {}) {
        if (page.closing) return
        if (path != "/reset" && inputFailure != null) {
            byId("clipboardStatus").textContent = STOPPED
            return
        }
        val serialized = JSON.stringify(body)
        val completion = input.submit {
            try {
                if (path != "/reset") inputFailure?.let {
                    throw IllegalStateException("Input requires Reset after an unacknowledged edit", it)
                }
                val response = fetchResponse(endpoint(path), requestInit("POST", jsonHeaders(), serialized))
                val result = response.jsonValue()
                if (!response.ok) error(strOrEmpty(result.error).ifEmpty { "Input rejected" })
                if (path == "/reset") inputFailure = null
                if (!page.closing) accepted()
            } catch (failure: Throwable) {
                inputFailure = failure
                if (!page.closing) byId("clipboardStatus").textContent = STOPPED
                throw failure
            }
        }
        if (completion == null) {
            inputFailure = IllegalStateException("Terminal input capacity is full or closed")
            byId("clipboardStatus").textContent = STOPPED
        }
    }

    private const val STOPPED = "Input stopped · Enter restarts Hermes with this conversation"

    /** Stops browser admission and joins input plus background effects before replacement. */
    fun close(): Promise<Unit> {
        if (page.closing) return page.close()
        flush()
        return page.close()
    }

    /* Printables coalesce inside one frame so fast typing is a few POSTs, not one per key. */
    private var pending = ""

    private fun sendText(text: String, paste: Boolean = false) {
        if (paste) {
            flush()
            val b: dynamic = obj(); b.text = text; b.paste = true
            post(b)
            return
        }
        pending += text
        if (pending.length == text.length) js("queueMicrotask")({ if (!page.closing) flush() })
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val text = pending
        pending = ""
        val b: dynamic = obj(); b.text = text
        post(b)
    }

    private fun sendKey(key: String, e: KeyboardEvent) {
        flush()
        val b: dynamic = obj()
        b.key = key; b.ctrl = e.ctrlKey; b.alt = e.altKey; b.shift = e.shiftKey
        post(b)
    }

    private val NAMED = mapOf(
        "Enter" to "ENTER", "Backspace" to "BACKSPACE", "Tab" to "TAB", "Escape" to "ESCAPE", "ArrowUp" to "UP", "ArrowDown" to "DOWN",
        "ArrowLeft" to "LEFT", "ArrowRight" to "RIGHT", "Home" to "HOME", "End" to "END", "Insert" to "INSERT", "Delete" to "DELETE",
        "PageUp" to "PAGE_UP", "PageDown" to "PAGE_DOWN",
    )
    private val FKEY = Regex("^F([1-9]|1[0-2])$")

    private suspend fun load() {
        try {
            val current = fetchResponse(endpoint()).jsonValue()
            if (page.closing) return
            snapshot(current)
            val panels = fetchResponse("/api/hermes/terminal/panels").jsonValue()
            if (page.closing) return
            val tabs = byId("panels")
            addPanelLink?.let { page.off(it, "click", addPanel) }
            tabs.textContent = ""
            var index = 0
            for (panel in jsArray(panels.panels)) {
                index++
                val link = document.createElement("a") as HTMLElement
                val id = str(panel.id)
                link.setAttribute("href", "/hermes?panel=" + js("encodeURIComponent")(id) + if (diagnosticsShown) "&diagnostics" else "")
                link.textContent = (if (id == "default") "Hermes" else "Hermes $index") + " · " + str(panel.state)
                if (id == panelId) link.setAttribute("aria-current", "page")
                tabs.appendChild(link)
            }
            val add = document.createElement("a") as HTMLElement
            add.setAttribute("href", "#")
            add.setAttribute("title", "Open an independent Hermes worker")
            add.textContent = "+ panel"
            addPanelLink = add
            page.on(add, "click", addPanel)
            tabs.appendChild(add)
            val diagnostics = document.createElement("a") as HTMLElement
            diagnostics.setAttribute("href", "/hermes?panel=" + js("encodeURIComponent")(panelId) + if (diagnosticsShown) "" else "&diagnostics")
            diagnostics.setAttribute("aria-pressed", diagnosticsShown.toString())
            diagnostics.textContent = "diagnostics"
            tabs.appendChild(diagnostics)
            if (state == "closed" && !started) { started = true; start() }
        } catch (_: Throwable) {
            setState("offline")
        }
    }

    private var addPanelLink: HTMLElement? = null
    private val addPanel: (dynamic) -> Unit = { event ->
        event.preventDefault()
        page.submit { newPanel() }
    }

    private val diagnosticsShown: Boolean = js("new URLSearchParams(window.location.search).has('diagnostics')") as Boolean
    /** A closed panel boots once on arrival; afterwards Enter restarts it. */
    private var started = false

    private suspend fun start() {
        if (page.closing) return
        boot = null
        detail = "Boot requested; waiting for sleeve progress"
        setState("booting")
        try {
            val response = fetchResponse(endpoint("/open"), requestInit("POST"))
            val result = response.jsonValue()
            if (page.closing) return
            if (!response.ok) {
                detail = strOrEmpty(result.error)
                setState("failed")
            } else load()
        } catch (t: Throwable) {
            detail = t.message ?: "Boot request failed"
            setState("failed")
        }
    }

    private fun restart() {
        flush()
        val request: dynamic = obj(); request.resume = true
        post(request, "/reset") {
            boot = null
            detail = "Reset queued · restarting Hermes with this conversation"
            byId("clipboardStatus").textContent = ""
            setState("booting")
        }
    }

    private suspend fun newPanel() {
        try {
            val response = fetchResponse("/api/hermes/terminal/panels", requestInit("POST"))
            val result = response.jsonValue()
            if (page.closing) return
            if (!response.ok) error(strOrEmpty(result.error))
            window.location.href = str(result.url) + if (diagnosticsShown) "&diagnostics" else ""
        } catch (failure: Throwable) {
            if (!page.closing) byId("clipboardStatus").textContent = failure.message ?: "New panel failed"
        }
    }

    private var resizeTimer = 0

    private fun fitGeometry() {
        page.cancelLater(resizeTimer)
        resizeTimer = page.later(250) {
            page.submit {
                if (page.closing) return@submit
                val wrap = byId("screenWrap").getBoundingClientRect()
                val probe = document.createElement("span") as HTMLElement
                probe.className = "cell"
                probe.textContent = "M"
                byId("screen").appendChild(probe)
                val box = probe.getBoundingClientRect()
                probe.remove()
                val bw = if (box.width != 0.0) box.width else 8.5
                val bh = if (box.height != 0.0) box.height else 16.0
                val columns = maxOf(20, minOf(220, kotlin.math.floor((wrap.width - 32) / bw).toInt()))
                val rows = maxOf(6, minOf(80, kotlin.math.floor((wrap.height - 30) / bh).toInt()))
                if (columns != grid.columns || rows != grid.rows) {
                    val b: dynamic = obj(); b.columns = columns; b.rows = rows
                    fetchResponse(endpoint("/resize"), requestInit("POST", jsonHeaders(), JSON.stringify(b))).jsonValue()
                }
            }
        }
    }

    private fun connect() {
        if (page.closing) return
        val es = EventSource(endpoint("/events"))
        val release = { es.onmessage = null; es.onerror = null; es.close() }
        val forget = page.own(release)
        es.onmessage = { e: MessageEvent ->
            if (!page.closing) {
                val x: dynamic = JSON.parse<dynamic>(e.data as String)
                if (x.kind == "state") adoptState(x)
                else if (x.kind == "manual") grid.signal(x.signal)
                else if (x.kind == "causal") {
                    grid.signal(x.signal)
                    if (grid.causal(x) && truthy(x.terminal.title)) document.title = str(x.terminal.title)
                }
            }
        }
        es.onerror = {
            release()
            forget()
            reconnect()
        }
    }

    fun mount() {
        // Capture precedes the owner's pagehide listener, so pending text joins the input lane.
        page.on(window, "pagehide", { close().ignore() }, true)
        page.mount { install() }
    }

    private fun reconnect() {
        page.later(1500) {
            if (page.submit { load(); connect() } == null && !page.closing) reconnect()
        }
    }

    private fun poll() {
        page.later(10000) {
            if (page.submit { try { load() } finally { poll() } } == null && !page.closing) poll()
        }
    }

    private fun bootClock() {
        page.later(1000) { renderBootTime(); bootClock() }
    }

    private fun install() {
        val kbd = byId("kbd") as HTMLTextAreaElement
        val screen = byId("screen")
        val terminalPanel = byId("terminalPanel")
        var dragging = false
        fun selectedText(): String {
            val selection: dynamic = window.asDynamic().getSelection()
            return if (selection != null &&
                (screen.asDynamic().contains(selection.anchorNode) == true || screen.asDynamic().contains(selection.focusNode) == true))
                str(selection.toString()) else ""
        }
        fun selectionChanged() {
            val selecting = dragging || selectedText().isNotEmpty()
            if (grid.selecting && !selecting) {
                grid.selecting = false
                grid.renderAll()
            } else grid.selecting = selecting
        }
        fun focusTerminal() {
            dragging = false
            // Clearing the textarea's native selection during keydown suppresses text input in Chrome.
            if (selectedText().isNotEmpty()) window.asDynamic().getSelection()?.removeAllRanges()
            selectionChanged()
            kbd.focus()
        }
        fun pasteText(text: String) {
            focusTerminal()
            if (text.isNotEmpty()) sendText(text, true)
        }
        fun copySelection() {
            val text = selectedText()
            if (text.isEmpty()) {
                byId("clipboardStatus").textContent = "Drag to select text first"
                return
            }
            page.submit {
                try {
                    window.navigator.asDynamic().clipboard.writeText(text).unsafeCast<Promise<dynamic>>().await()
                    if (page.closing) return@submit
                    byId("clipboardStatus").textContent = "Copied"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (!page.closing) byId("clipboardStatus").textContent = if (document.execCommand("copy")) "Copied" else "Use the browser's Copy command"
                }
            }
        }
        fun pasteClipboard() {
            page.submit {
                try {
                    val text = window.navigator.asDynamic().clipboard.readText().unsafeCast<Promise<String>>().await()
                    if (page.closing) return@submit
                    pasteText(text)
                    byId("clipboardStatus").textContent = if (text.isEmpty()) "Clipboard is empty" else "Pasted"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (!page.closing) byId("clipboardStatus").textContent = "Clipboard read blocked · use ⌘V"
                }
            }
        }
        page.on(document, "selectionchange", { selectionChanged() })
        page.on(document, "mouseup", { dragging = false; selectionChanged() })
        var composing = false
        page.on(document, "keydown", { ev ->
            val e = ev as KeyboardEvent
            val target = e.target as? HTMLElement
            if (target != null && target != kbd &&
                target.asDynamic().closest("input,textarea,select,button,a,[contenteditable]") != null) return@on
            if (target != kbd && target != document.body && target != document.documentElement &&
                terminalPanel.asDynamic().contains(target) != true) return@on
            if (e.metaKey) return@on // leave ⌘C/⌘V to the browser
            if (e.key == "Control" || e.key == "Shift" || e.key == "Alt" || e.key == "CapsLock") return@on
            if (e.ctrlKey && e.shiftKey && e.key.equals("c", true)) { e.preventDefault(); copySelection(); return@on }
            if (e.ctrlKey && e.shiftKey && e.key.equals("v", true)) { e.preventDefault(); pasteClipboard(); return@on }
            if (composing || e.asDynamic().isComposing == true) return@on
            val refocus = document.activeElement != kbd
            focusTerminal()
            val named = NAMED[e.key] ?: if (FKEY.matches(e.key)) e.key.uppercase() else null
            if (named == "ENTER" && (inputFailure != null || state == "failed" || state == "closed")) {
                e.preventDefault()
                if (state == "closed") page.submit { start() } else restart()
                return@on
            }
            if (named != null) { e.preventDefault(); sendKey(named, e); return@on }
            if (e.ctrlKey && e.key.length == 1) { // Ctrl-letter folds to its C0 control
                val code = e.key.lowercase()[0].code
                if (code in 97..122) { e.preventDefault(); sendText((code - 96).toChar().toString()) }
                return@on
            }
            if (refocus && e.key.length == 1) { e.preventDefault(); sendText(e.key) }
            // Anything else is text: let it land in the textarea and leave through `input`.
        })
        page.on(kbd, "compositionstart", { composing = true })
        page.on(kbd, "compositionend", { ev ->
            composing = false
            val data = (ev as CompositionEvent).data
            if (data.isNotEmpty()) sendText(data)
            kbd.value = ""
        })
        page.on(kbd, "input", {
            if (!composing) {
                val text = kbd.value
                kbd.value = ""
                if (text.isNotEmpty()) sendText(text)
            }
        })
        page.on(document, "paste", { ev ->
            val target = ev.target as? HTMLElement
            if (target != null && target != kbd &&
                target.asDynamic().closest("input,textarea,select,button,a,[contenteditable]") != null) return@on
            if (target != kbd && target != document.body && target != document.documentElement &&
                terminalPanel.asDynamic().contains(target) != true) return@on
            ev.preventDefault()
            val cd: dynamic = (ev as ClipboardEvent).clipboardData ?: window.asDynamic().clipboardData
            val text = cd.getData("text") as String
            pasteText(text)
        })
        // Derive the caret's look from what actually holds focus.
        fun syncFocus() {
            focused = document.activeElement == kbd
            byId("screen").classList.toggle("focused", focused)
        }
        page.on(kbd, "focus", { syncFocus() })
        page.on(kbd, "blur", { syncFocus() })
        page.on(window, "focus", { syncFocus() })
        // A click anywhere on the screen means "type here" — but never steal a text selection.
        page.on(terminalPanel, "mouseup", { ev ->
            if ((ev as MouseEvent).button.toInt() == 0 && selectedText().isEmpty()) kbd.focus()
        })
        page.on(screen, "mousedown", { ev ->
            if ((ev as MouseEvent).button.toInt() == 0) { dragging = true; grid.selecting = true }
        })
        if (diagnosticsShown) document.body!!.classList.add("diagnostics")
        page.on(window, "resize", { fitGeometry() })
        page.submit { load(); if (!page.closing) { fitGeometry(); connect() } }
        poll()
        bootClock()
        kbd.focus()
        syncFocus()
    }
}
