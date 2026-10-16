package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
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

    private fun setState(s: String) {
        state = s
        byId("state").textContent = s
        byId("state").className = "badge " + (if (s == "ready") "ok" else if (s == "failed" || s == "offline") "bad" else "")
        byId("open").hidden = s == "ready" || s == "booting"
        byId("reset").asDynamic().disabled = s == "booting" || s == "offline"
        byId("newSession").asDynamic().disabled = s != "ready"
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
        val p: dynamic = if (truthy(x.panel)) x.panel else x
        adoptState(x)
        grid.adopt(p)
        grid.renderAll()
        grid.lastCursorRow = grid.cursor.row
        for (s in jsArray(x.signals)) grid.signal(s)
    }

    /* The server owns the line discipline. This page only ships keystrokes, and it
       ships them strictly in order: two POSTs racing would scramble the line. */
    private val outbox = ArrayDeque<dynamic>()
    private var pumping = false

    private fun post(body: dynamic) {
        outbox.addLast(body)
        if (!pumping) launchPage { pump() }
    }

    private suspend fun pump() {
        pumping = true
        while (outbox.isNotEmpty()) {
            val body = outbox.removeFirst()
            try {
                val response = fetchResponse(endpoint("/input"), requestInit("POST", jsonHeaders(), JSON.stringify(body)))
                if (!response.ok) {
                    byId("clipboardStatus").textContent = "Input rejected · use Reset to reconnect"
                }
            } catch (_: Throwable) {
                byId("clipboardStatus").textContent = "Input disconnected · reconnecting"
            }
        }
        pumping = false
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
        if (pending.length == text.length) js("queueMicrotask")({ flush() })
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
            snapshot(fetchResponse(endpoint()).jsonValue())
            val panels = fetchResponse("/api/hermes/terminal/panels").jsonValue()
            val tabs = byId("panels")
            tabs.textContent = ""
            var index = 0
            for (panel in jsArray(panels.panels)) {
                index++
                val link = document.createElement("a") as HTMLElement
                val id = str(panel.id)
                link.setAttribute("href", "/hermes?panel=" + js("encodeURIComponent")(id))
                link.textContent = (if (id == "default") "Hermes" else "Hermes $index") + " · " + str(panel.state)
                if (id == panelId) link.setAttribute("aria-current", "page")
                tabs.appendChild(link)
            }
        } catch (_: Throwable) {
            setState("offline")
        }
    }

    private var resizeTimer = 0

    private fun fitGeometry() {
        window.clearTimeout(resizeTimer)
        resizeTimer = setTimeout(250) {
            launchPage {
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
                    fetchResponse(endpoint("/resize"), requestInit("POST", jsonHeaders(), JSON.stringify(b)))
                }
            }
        }
    }

    private fun connect() {
        val es = EventSource(endpoint("/events"))
        es.onmessage = { e: MessageEvent ->
            val x: dynamic = JSON.parse<dynamic>(e.data as String)
            if (x.kind == "state") adoptState(x)
            else if (x.kind == "manual") grid.signal(x.signal)
            else if (x.kind == "causal") {
                grid.signal(x.signal)
                val t: dynamic = x.terminal
                if (truthy(t)) {
                    grid.adoptHeader(t)
                    if (truthy(t.title)) document.title = str(t.title)
                }
                grid.patches(x.patches)
            }
        }
        es.onerror = {
            es.close()
            launchPage { load() }
            setTimeout(1500) { connect() }
        }
    }

    fun mount() {
        val kbd = byId("kbd") as HTMLTextAreaElement
        val screen = byId("screen")
        val terminalPanel = byId("terminalPanel")
        val menu = byId("clipboardMenu")
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
            menu.hidden = true
            kbd.focus()
        }
        fun pasteText(text: String) {
            focusTerminal()
            if (text.isNotEmpty()) sendText(text, true)
        }
        fun copySelection() {
            val text = selectedText()
            menu.hidden = true
            if (text.isEmpty()) {
                byId("clipboardStatus").textContent = "Drag to select text first"
                return
            }
            launchPage {
                try {
                    window.navigator.asDynamic().clipboard.writeText(text).unsafeCast<Promise<dynamic>>().await()
                    byId("clipboardStatus").textContent = "Copied"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    byId("clipboardStatus").textContent = if (document.execCommand("copy")) "Copied" else "Use the browser's Copy command"
                }
            }
        }
        fun pasteClipboard() {
            menu.hidden = true
            launchPage {
                try {
                    val text = window.navigator.asDynamic().clipboard.readText().unsafeCast<Promise<String>>().await()
                    pasteText(text)
                    byId("clipboardStatus").textContent = if (text.isEmpty()) "Clipboard is empty" else "Pasted"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    byId("pasteBox").hidden = false
                    (byId("pasteText") as HTMLTextAreaElement).focus()
                }
            }
        }
        // Keep a selection when a toolbar or menu button takes focus.
        for (id in arrayOf("copy", "menuCopy")) byId(id).addEventListener("mousedown", { it.preventDefault() })
        byId("copy").onclick = { copySelection() }
        byId("menuCopy").onclick = { copySelection() }
        byId("paste").onclick = { pasteClipboard() }
        byId("menuPaste").onclick = { pasteClipboard() }
        byId("pasteInsert").onclick = {
            val input = byId("pasteText") as HTMLTextAreaElement
            pasteText(input.value)
            input.value = ""
            byId("pasteBox").hidden = true
        }
        byId("pasteCancel").onclick = {
            (byId("pasteText") as HTMLTextAreaElement).value = ""
            byId("pasteBox").hidden = true
            kbd.focus()
        }
        screen.addEventListener("contextmenu", { ev: Event ->
            ev.preventDefault()
            val e = ev as MouseEvent
            menu.hidden = false
            menu.style.left = "${minOf(e.clientX, window.innerWidth - menu.offsetWidth)}px"
            menu.style.top = "${minOf(e.clientY, window.innerHeight - menu.offsetHeight)}px"
        })
        document.addEventListener("mousedown", { ev: Event ->
            if (menu.asDynamic().contains(ev.target) != true) menu.hidden = true
        })
        document.addEventListener("selectionchange", { selectionChanged() })
        document.addEventListener("mouseup", { dragging = false; selectionChanged() })
        var composing = false
        document.addEventListener("keydown", { ev: Event ->
            val e = ev as KeyboardEvent
            if (e.key == "Escape" && !menu.hidden) {
                e.preventDefault(); focusTerminal(); return@addEventListener
            }
            val target = e.target as? HTMLElement
            if (target != null && target != kbd &&
                target.asDynamic().closest("input,textarea,select,button,a,[contenteditable]") != null) return@addEventListener
            if (target != kbd && target != document.body && target != document.documentElement &&
                terminalPanel.asDynamic().contains(target) != true) return@addEventListener
            if (e.metaKey) return@addEventListener // leave ⌘C/⌘V to the browser
            if (e.key == "Control" || e.key == "Shift" || e.key == "Alt" || e.key == "CapsLock") return@addEventListener
            if (e.ctrlKey && e.shiftKey && e.key.equals("c", true)) { e.preventDefault(); copySelection(); return@addEventListener }
            if (e.ctrlKey && e.shiftKey && e.key.equals("v", true)) { e.preventDefault(); pasteClipboard(); return@addEventListener }
            if (composing || e.asDynamic().isComposing == true) return@addEventListener
            val refocus = document.activeElement != kbd
            focusTerminal()
            val named = NAMED[e.key] ?: if (FKEY.matches(e.key)) e.key.uppercase() else null
            if (named != null) { e.preventDefault(); sendKey(named, e); return@addEventListener }
            if (e.ctrlKey && e.key.length == 1) { // Ctrl-letter folds to its C0 control
                val code = e.key.lowercase()[0].code
                if (code in 97..122) { e.preventDefault(); sendText((code - 96).toChar().toString()) }
                return@addEventListener
            }
            if (refocus && e.key.length == 1) { e.preventDefault(); sendText(e.key) }
            // Anything else is text: let it land in the textarea and leave through `input`.
        })
        kbd.addEventListener("compositionstart", { composing = true })
        kbd.addEventListener("compositionend", { ev: Event ->
            composing = false
            val data = (ev as CompositionEvent).data
            if (data.isNotEmpty()) sendText(data)
            kbd.value = ""
        })
        kbd.addEventListener("input", {
            if (!composing) {
                val text = kbd.value
                kbd.value = ""
                if (text.isNotEmpty()) sendText(text)
            }
        })
        document.addEventListener("paste", { ev: Event ->
            val target = ev.target as? HTMLElement
            if (target != null && target != kbd &&
                target.asDynamic().closest("input,textarea,select,button,a,[contenteditable]") != null) return@addEventListener
            if (target != kbd && target != document.body && target != document.documentElement &&
                terminalPanel.asDynamic().contains(target) != true) return@addEventListener
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
        kbd.addEventListener("focus", { syncFocus() })
        kbd.addEventListener("blur", { syncFocus() })
        window.addEventListener("focus", { syncFocus() })
        // A click anywhere on the screen means "type here" — but never steal a text selection.
        terminalPanel.addEventListener("mouseup", { ev: Event ->
            if ((ev as MouseEvent).button.toInt() == 0 && selectedText().isEmpty()) kbd.focus()
        })
        screen.addEventListener("mousedown", { ev: Event ->
            if ((ev as MouseEvent).button.toInt() == 0) { dragging = true; grid.selecting = true }
        })
        byId("open").onclick = {
            launchPage {
                boot = null
                detail = "Boot requested; waiting for sleeve progress"
                setState("booting")
                try {
                    val response = fetchResponse(endpoint("/open"), requestInit("POST"))
                    if (!response.ok) {
                        val failure = response.jsonValue()
                        detail = strOrEmpty(failure.error)
                        setState("failed")
                    } else load()
                } catch (t: Throwable) {
                    detail = t.message ?: "Boot request failed"
                    setState("failed")
                }
                kbd.focus()
            }
        }
        byId("newSession").onclick = {
            focusTerminal()
            flush()
            val command: dynamic = obj(); command.command = "/new"
            post(command)
        }
        byId("reset").onclick = {
            focusTerminal()
            launchPage {
                try {
                    val response = fetchResponse(endpoint("/reset"), requestInit("POST", jsonHeaders(), "{\"resume\":true}"))
                    if (!response.ok) error(strOrEmpty(response.jsonValue().error))
                    boot = null
                    detail = "Reset queued · restarting Hermes with this conversation"
                    setState("booting")
                } catch (failure: Throwable) {
                    detail = failure.message ?: "Reset failed"
                    setState("failed")
                }
            }
        }
        byId("newPanel").onclick = {
            launchPage {
                try {
                    val response = fetchResponse("/api/hermes/terminal/panels", requestInit("POST"))
                    val result = response.jsonValue()
                    if (!response.ok) error(strOrEmpty(result.error))
                    window.location.href = str(result.url)
                } catch (failure: Throwable) {
                    byId("clipboardStatus").textContent = failure.message ?: "New panel failed"
                }
            }
        }
        byId("diagnostics").onclick = {
            val visible = document.body!!.classList.toggle("diagnostics")
            byId("diagnostics").setAttribute("aria-pressed", visible.toString())
            fitGeometry()
            focusTerminal()
        }
        window.addEventListener("resize", { fitGeometry() })
        launchPage { load(); fitGeometry() }
        window.setInterval({ launchPage { load() } }, 10000)
        window.setInterval({ renderBootTime() }, 1000)
        connect()
        kbd.focus()
        syncFocus()
    }
}
