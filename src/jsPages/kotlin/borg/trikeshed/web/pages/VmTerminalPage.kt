package borg.trikeshed.web.pages

import borg.trikeshed.web.PanelsPage
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URLSearchParams
import kotlin.js.Promise

/** Process terminals: one tab per VM, served at `/vm-terminal`. */
object VmTerminalPage {
    private val grid = TerminalGrid(hermes = false)
    private var active = ""
    private var events: EventSource? = null
    private var releaseEvents: (() -> Unit)? = null
    private var reconnectTimer = 0
    private val page = PanelsPage(::failure)
    private val input = page.lane(workers = 1)

    private fun failure(error: Throwable) {
        if (!page.closing) {
            byId("state").textContent = error.message ?: "Terminal request failed"
            byId("state").className = "badge bad"
        }
    }

    private fun encodeUri(s: String): String = js("encodeURIComponent")(s) as String

    private fun snap(x: dynamic) {
        if (page.closing) return
        val p: dynamic = if (truthy(x.panel)) x.panel else x
        if (!grid.adopt(p, x.sequence)) return
        if (x.tier == "capsule") (byId("mode") as HTMLSelectElement).value = "stdin"
        byId("state").textContent = (if (truthy(x.vmId)) str(x.vmId) else active) + " · " + (if (truthy(x.facet)) str(x.facet) else "") + " · " + (if (truthy(x.phase)) str(x.phase) else "")
        byId("state").className = "badge " + (if (x.phase == "live") "ok" else if (x.phase == "closed") "bad" else "")
        grid.renderAll()
        grid.lastCursorRow = grid.cursor.row
        byId("manualRail").asDynamic().replaceChildren()
        byId("causalRail").asDynamic().replaceChildren()
        for (s in jsArray(x.signals)) grid.signal(s)
    }

    private suspend fun terminals() {
        if (page.closing) return
        val selected = active
        val x: dynamic = fetchResponse("/api/vm/terminals").jsonValue()
        if (page.closing || selected != active) return
        val list = jsArray(x.terminals)
        if (active.isEmpty() && list.isNotEmpty()) active = str(list[0].vmId)
        val esc = TerminalPalette::escapeVm
        byId("tabs").innerHTML = list.joinToString("") { t ->
            val id = strOrEmpty(t.vmId)
            val on = t.vmId == active
            "<button class=\"tab " + (if (on) "active" else "") + "\" data-id=\"" + esc(id) + "\"" + (if (on) " aria-current=\"page\"" else "") + ">" +
                esc(id) + " · " + esc(strOrEmpty(t.tier)) + "</button>"
        }
        if (active.isNotEmpty()) load()
    }

    private suspend fun select(id: String) {
        if (page.closing) return
        releaseEvents?.invoke()
        page.cancelLater(reconnectTimer)
        active = id
        window.history.replaceState(null, "", "/vm-terminal?id=" + encodeUri(id))
        terminals()
        connect()
    }

    private suspend fun load() {
        if (page.closing || active.isEmpty()) return
        val id = active
        val r = fetchResponse("/api/vm/" + encodeUri(id) + "/terminal")
        val snapshot = r.jsonValue()
        if (page.closing || id != active) return
        if (!r.ok) error(strOrEmpty(snapshot.error).ifEmpty { "Terminal snapshot failed" })
        snap(snapshot)
    }

    private fun send() {
        if (page.closing || active.isEmpty()) return
        val command = byId("command") as HTMLInputElement
        val text = command.value
        if (text.isEmpty()) return
        // Pin the complete command and destination at admission, not when a queued action starts.
        val id = active
        val b: dynamic = obj(); b.text = text; b.mode = (byId("mode") as HTMLSelectElement).value
        val body = JSON.stringify(b)
        val completion = input.submit {
            val response = fetchResponse("/api/vm/" + encodeUri(id) + "/terminal/input", requestInit("POST", jsonHeaders(), body))
            val result = response.jsonValue()
            if (!response.ok) error(strOrEmpty(result.error).ifEmpty { "Terminal input rejected" })
        }
        if (completion == null) failure(IllegalStateException("Terminal input capacity is full or closed"))
        else {
            command.value = ""
            completion.invokeOnCompletion { error -> if (error != null) failure(error) }
        }
    }

    private var rt = 0

    private fun fit() {
        page.cancelLater(rt)
        rt = page.later(250) {
            page.submit {
                if (page.closing || active.isEmpty()) return@submit
                val w = byId("screenWrap").getBoundingClientRect()
                val probe = document.createElement("span") as HTMLElement
                probe.className = "cell"
                probe.textContent = "M"
                byId("screen").appendChild(probe)
                val b = probe.getBoundingClientRect()
                probe.remove()
                val bw = if (b.width != 0.0) b.width else 8.0
                val bh = if (b.height != 0.0) b.height else 16.0
                val columns = maxOf(20, minOf(220, kotlin.math.floor((w.width - 32) / bw).toInt()))
                val rows = maxOf(6, minOf(80, kotlin.math.floor((w.height - 28) / bh).toInt()))
                if (columns != grid.columns || rows != grid.rows) {
                    val body: dynamic = obj(); body.columns = columns; body.rows = rows
                    fetchResponse("/api/vm/" + encodeUri(active) + "/terminal/resize", requestInit("POST", jsonHeaders(), JSON.stringify(body))).jsonValue()
                }
            }
        }
    }

    private fun connect() {
        if (page.closing) return
        page.cancelLater(reconnectTimer)
        releaseEvents?.invoke()
        val e = EventSource("/api/vm/terminal/events")
        events = e
        val release = { e.onmessage = null; e.onerror = null; e.close() }
        val forget = page.own(release)
        releaseEvents = { release(); forget(); if (events === e) events = null }
        e.onmessage = { m: MessageEvent ->
            if (!page.closing && events === e) {
                val x: dynamic = JSON.parse<dynamic>(m.data as String)
                if (x.vmId == active) {
                    if (x.kind == "manual") grid.signal(x.signal)
                    else if (x.kind == "causal") {
                        grid.signal(x.signal)
                        grid.causal(x)
                    } else if (x.kind == "phase") byId("state").textContent = active + " · " + str(x.phase)
                }
            }
        }
        e.onerror = {
            release(); forget()
            if (events === e) reconnect(e)
        }
    }

    private fun reconnect(previous: EventSource) {
        reconnectTimer = page.later(1500) {
            if (events === previous && page.submit { load(); if (events === previous) connect() } == null && !page.closing)
                reconnect(previous)
        }
    }

    private fun poll() {
        page.later(10000) {
            if (page.submit { try { terminals() } finally { poll() } } == null && !page.closing) poll()
        }
    }

    /** Joins admitted HTTP work and releases browser handles; it does not stop the selected VM. */
    fun close(): Promise<Unit> = page.close()

    fun mount() {
        page.mount { install() }
    }

    private fun install() {
        active = URLSearchParams(window.location.search).get("id") ?: ""
        page.on(byId("send"), "click", { send() })
        page.on(byId("command"), "keydown", { ev ->
            if ((ev as KeyboardEvent).key == "Enter") { ev.preventDefault(); send() }
        })
        page.on(byId("tabs"), "click", { ev ->
            val tab = ev.target?.closest("button[data-id]")
            if (tab != null) { val id = str(tab.getAttribute("data-id")); page.submit { select(id) } }
        })
        page.on(byId("screen"), "click", { byId("command").focus() })
        page.on(window, "resize", { fit() })
        page.submit { try { terminals() } finally { fit(); connect(); poll() } }
        byId("command").focus()
    }
}
