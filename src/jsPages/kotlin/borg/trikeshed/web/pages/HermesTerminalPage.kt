package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.CompositionEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/** Hermes xterm-256color: the GraalPy sleeve terminal served at `/hermes`. */
object HermesTerminalPage {
    private val grid = TerminalGrid(hermes = true)
    private var state = "closed"
    private var focused = false

    private fun setState(s: String) {
        byId("state").textContent = s
        byId("state").className = "badge " + (if (s == "ready") "ok" else if (s == "failed") "bad" else "")
    }

    private fun snapshot(x: dynamic) {
        val p: dynamic = if (truthy(x.panel)) x.panel else x
        if (truthy(x.state)) state = str(x.state)
        grid.adopt(p)
        setState(state)
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
                fetchResponse("/api/hermes/terminal/input", requestInit("POST", jsonHeaders(), JSON.stringify(body)))
            } catch (_: Throwable) {
                /* dropped keystroke; the next snapshot reconciles the screen */
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
            snapshot(fetchResponse("/api/hermes/terminal").jsonValue())
        } catch (_: Throwable) {
            byId("state").textContent = "offline"
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
                    fetchResponse("/api/hermes/terminal/resize", requestInit("POST", jsonHeaders(), JSON.stringify(b)))
                }
            }
        }
    }

    private fun connect() {
        val es = EventSource("/api/hermes/terminal/events")
        es.onmessage = { e: MessageEvent ->
            val x: dynamic = JSON.parse<dynamic>(e.data as String)
            if (x.kind == "state") setState(str(x.state))
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
        var composing = false
        kbd.addEventListener("keydown", { ev: Event ->
            val e = ev as KeyboardEvent
            if (e.metaKey) return@addEventListener // leave ⌘C/⌘V to the browser
            if (composing) return@addEventListener // mid-IME: let the composition finish
            val named = NAMED[e.key] ?: if (FKEY.matches(e.key)) e.key.uppercase() else null
            if (named != null) { e.preventDefault(); sendKey(named, e); return@addEventListener }
            if (e.ctrlKey && e.key.length == 1) { // Ctrl-letter folds to its C0 control
                val code = e.key.lowercase()[0].code
                if (code in 97..122) { e.preventDefault(); sendText((code - 96).toChar().toString()) }
                return@addEventListener
            }
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
        kbd.addEventListener("paste", { ev: Event ->
            ev.preventDefault()
            val cd: dynamic = (ev as ClipboardEvent).clipboardData ?: window.asDynamic().clipboardData
            val text = cd.getData("text") as String
            if (text.isNotEmpty()) sendText(text, true)
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
        byId("screen").addEventListener("mouseup", {
            val sel: dynamic = window.asDynamic().getSelection()
            if ((if (sel == null) "" else str(sel.toString())) == "") kbd.focus()
        })
        byId("screen").addEventListener("mousedown", { e: Event -> e.preventDefault() })
        byId("open").onclick = {
            launchPage {
                fetchResponse("/api/hermes/terminal/open", requestInit("POST"))
                setState("booting")
                kbd.focus()
            }
        }
        window.addEventListener("resize", { fitGeometry() })
        launchPage { load(); fitGeometry() }
        window.setInterval({ launchPage { load() } }, 10000)
        connect()
        kbd.focus()
        syncFocus()
    }
}
