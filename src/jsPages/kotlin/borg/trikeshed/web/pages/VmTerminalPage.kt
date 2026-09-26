package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.EventSource
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URLSearchParams

/** Process terminals: one tab per VM, served at `/vm-terminal`. */
object VmTerminalPage {
    private val grid = TerminalGrid(hermes = false)
    private var active = ""

    private fun encodeUri(s: String): String = js("encodeURIComponent")(s) as String

    private fun snap(x: dynamic) {
        val p: dynamic = if (truthy(x.panel)) x.panel else x
        grid.adopt(p)
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
        val x: dynamic = fetchResponse("/api/vm/terminals").jsonValue()
        val list = jsArray(x.terminals)
        if (active.isEmpty() && list.isNotEmpty()) active = str(list[0].vmId)
        val esc = TerminalPalette::escapeVm
        byId("tabs").innerHTML = list.joinToString("") { t ->
            val id = strOrEmpty(t.vmId)
            val on = t.vmId == active
            "<button class=\"tab " + (if (on) "active" else "") + "\" data-id=\"" + esc(id) + "\"" + (if (on) " aria-current=\"page\"" else "") + ">" +
                esc(id) + " · " + esc(strOrEmpty(t.tier)) + "</button>"
        }
        val tabs = byId("tabs").children
        for (i in 0 until tabs.length) {
            val b = tabs.item(i) as HTMLElement
            b.onclick = { launchPage { select(b.getAttribute("data-id") ?: "") } }
        }
        if (active.isNotEmpty()) load()
    }

    private suspend fun select(id: String) {
        active = id
        window.history.replaceState(null, "", "/vm-terminal?id=" + encodeUri(id))
        terminals()
    }

    private suspend fun load() {
        if (active.isEmpty()) return
        val r = fetchResponse("/api/vm/" + encodeUri(active) + "/terminal")
        if (r.ok) snap(r.jsonValue())
    }

    private suspend fun send() {
        if (active.isEmpty()) return
        val command = byId("command") as HTMLInputElement
        val text = command.value
        if (text.isEmpty()) return
        command.value = ""
        val b: dynamic = obj(); b.text = text; b.mode = (byId("mode") as HTMLSelectElement).value
        fetchResponse("/api/vm/" + encodeUri(active) + "/terminal/input", requestInit("POST", jsonHeaders(), JSON.stringify(b)))
    }

    private var rt = 0

    private fun fit() {
        window.clearTimeout(rt)
        rt = setTimeout(250) {
            launchPage {
                if (active.isEmpty()) return@launchPage
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
                    fetchResponse("/api/vm/" + encodeUri(active) + "/terminal/resize", requestInit("POST", jsonHeaders(), JSON.stringify(body)))
                }
            }
        }
    }

    private fun connect() {
        val e = EventSource("/api/vm/terminal/events")
        e.onmessage = { m: MessageEvent ->
            val x: dynamic = JSON.parse<dynamic>(m.data as String)
            if (x.vmId == active) {
                if (x.kind == "manual") grid.signal(x.signal)
                else if (x.kind == "causal") {
                    grid.signal(x.signal)
                    if (truthy(x.terminal)) grid.adoptHeader(x.terminal)
                    grid.patches(x.patches)
                } else if (x.kind == "phase") byId("state").textContent = active + " · " + str(x.phase)
            }
        }
        e.onerror = {
            e.close()
            launchPage { load() }
            setTimeout(1500) { connect() }
        }
    }

    fun mount() {
        active = URLSearchParams(window.location.search).get("id") ?: ""
        byId("send").onclick = { launchPage { send() } }
        byId("command").onkeydown = { ev: Event ->
            if ((ev as KeyboardEvent).key == "Enter") { ev.preventDefault(); launchPage { send() } }
        }
        byId("screen").onclick = { byId("command").focus() }
        window.addEventListener("resize", { fit() })
        launchPage { terminals(); fit() }
        window.setInterval({ launchPage { terminals() } }, 10000)
        connect()
        byId("command").focus()
    }
}
