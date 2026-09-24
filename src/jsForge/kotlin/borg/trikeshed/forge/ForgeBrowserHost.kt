@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/**
 * Host adapter — the jsForge DOM half of script.js "Render: host" (the `borg.trikeshed.vm`
 * sub-VM substrate): capability tiles, the VM sheet, the spawn/eval form, and the events stream.
 * Tile derivation is commonMain (`ForgeHost.kt`).
 */

fun ForgeBrowser.hostLogLine(text: String) {
    val hostLog = el("host-log") ?: return
    hostLog.textContent = (hostLog.textContent ?: "") + text + "\n"
    hostLog.scrollTop = hostLog.scrollHeight.toDouble()
}

fun ForgeBrowser.renderHostTiles(live: Boolean?) {
    val hostTilesEl = el("host-tiles") ?: return
    hostTilesEl.clearChildren()
    val hosts = forgeHostsOf(seed)
    val dashboardsNio = seed["dashboards"].asMap()["nio"]?.asMap()
    for (t in forgeHostTiles(hosts, dashboardsNio, live)) {
        val d = el("div", "host-tile" + (t.tone?.let { " $it" } ?: ""))
        d.append(el("div", "ht-k", t.key), el("div", "ht-v", t.value), el("div", "ht-sub", t.sub))
        if (t.deadList.isNotEmpty()) {
            d.appendChild(el("div", "host-dead-list", t.deadList.joinToString("\n")))
        }
        hostTilesEl.appendChild(d)
    }
}

fun ForgeBrowser.renderHostVms(sheet: Map<String, Any?>? = null) {
    val hostVmsEl = el("host-vms") ?: return
    val hostVmCountEl = el("host-vm-count")
    hostVmsEl.clearChildren()
    val sh = sheet ?: forgeHostsOf(seed)["vms"]?.asMap()
    val columns = sh?.get("columns")?.asList()
    if (sh != null && !columns.isNullOrEmpty()) {
        hostVmsEl.appendChild(buildSheetTable(borg.trikeshed.forge.sheet.SourceSheet.fromMap(sh), 0))
        hostVmCountEl?.textContent = sh["rows"].asList().size.toString() + " rows"
    } else {
        hostVmsEl.textContent = "no VM rows (host dead)"
        hostVmCountEl?.textContent = ""
    }
}

suspend fun ForgeBrowser.hostApi(path: String, body: Map<String, Any?>? = null): Any? {
    val response = window.fetch(path, if (body == null) RequestInit(method = "GET") else RequestInit(
        method = "POST",
        headers = Headers().also { it.append("Content-Type", "application/json") },
        body = JsonSupport.stringify(body),
    )).await()
    if (!response.ok) throw IllegalStateException(response.status.toString() + " " + response.text().await())
    return JsonSupport.parse(response.text().await())
}

fun ForgeBrowser.probeHostLive(onDone: (Boolean) -> Unit) {
    hostLiveProbe?.let { onDone(it); return }
    scope.launch {
        val live = try {
            val sheet = hostApi("./api/vm") as? Map<String, Any?>
            hostLiveProbe = true
            renderHostVms(sheet)
            true
        } catch (e: Throwable) {
            hostLiveProbe = false
            false
        }
        onDone(live)
    }
}

fun ForgeBrowser.renderHost() {
    val hostFacetSel = document.getElementById("host-vm-facet") as? HTMLSelectElement ?: return
    val hostForm = el("host-spawn") ?: return
    val hostLiveNote = el("host-live-note")
    hostFacetSel.clearChildren()
    for (l in forgeHostFacets(forgeHostsOf(seed))) {
        val o = document.createElement("option") as HTMLElement
        o.setAttribute("value", l)
        o.textContent = l
        hostFacetSel.appendChild(o)
    }
    renderHostTiles(hostLiveProbe)
    renderHostVms()
    probeHostLive { live ->
        renderHostTiles(live)
        hostForm.setAttribute("aria-disabled", if (live) "false" else "true")
        hostLiveNote?.textContent = if (live) "served live — spawn/eval round-trip to /api/vm"
        else "static Pages build — no live host; spawn/eval need the JVM server (runKanbanHttpServerJvm)"
        if (live && !hostEventsStarted) {
            hostEventsStarted = true
            startHostEvents()
        }
    }
}

/** `new EventSource('./api/vm/events')` → host log lines. */
fun ForgeBrowser.startHostEvents() {
    js(
        """
        if (typeof EventSource !== 'undefined') {
            var events = new EventSource('./api/vm/events');
            events.onmessage = function(e) { hostLogSink(e.data); };
            events.onerror = function() { hostLogSink('[events] stream closed'); };
        }
        """
    )
}

/** The EventSource callback lane (js interop needs a top-level reference). */
val hostLogSink: (String) -> Unit = { ForgeBrowser.hostLogLine(it) }

fun ForgeBrowser.wireHostForm() {
    val hostForm = el("host-spawn") ?: return
    hostForm.addEventListener("submit", { e ->
        e.preventDefault()
        val id = (document.getElementById("host-vm-id") as? HTMLInputElement)?.value?.trim()
            ?.takeIf { it.isNotEmpty() } ?: ("vm-" + forgeUid().take(6))
        val facet = (document.getElementById("host-vm-facet") as? HTMLSelectElement)?.value ?: "js"
        val trust = (document.getElementById("host-vm-trust") as? HTMLSelectElement)?.value ?: "OWN"
        val source = (document.getElementById("host-vm-src") as? HTMLTextAreaElement)?.value ?: ""
        hostLogLine("> spawn $id ($facet, $trust)")
        scope.launch {
            try {
                hostApi("./api/vm/spawn", mapOf("id" to id, "facet" to facet, "trust" to trust))
                val result: Any? = if (source.trim().isNotEmpty())
                    hostApi("./api/vm/" + urlEncode(id) + "/eval", mapOf("source" to source))
                else mapOf("value" to null)
                hostLogLine("< " + JsonSupport.stringify(result))
                val sheet = hostApi("./api/vm") as? Map<String, Any?>
                renderHostVms(sheet)
            } catch (err: Throwable) {
                hostLogLine("! " + (err.message ?: err.toString()))
            }
        }
    })
}
