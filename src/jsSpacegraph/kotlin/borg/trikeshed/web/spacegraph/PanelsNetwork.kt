package borg.trikeshed.web.spacegraph

import borg.trikeshed.job.ContentId
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/** Explicit signed host discovery and pinned execution through the daemon's DHT owner. */
fun Panels.openWorkNetwork() {
    if (page.closing) return
    byId("panelWorkNetwork")?.querySelector("[data-network-close]")?.click()
    val focus = document.activeElement.asDynamic()
    val dialog = document.createElement("section").asDynamic()
    dialog.id = "panelWorkNetwork"; dialog.setAttribute("role", "dialog")
    dialog.setAttribute("aria-label", "DHT work network")
    dialog.style.cssText = "position:fixed;inset:58px 16px auto auto;width:min(680px,calc(100vw - 32px));max-height:84vh;overflow:auto;z-index:64;background:var(--panel);color:var(--ink);border:1px solid var(--wire);border-radius:10px;padding:18px;box-shadow:0 12px 40px #0009;font:12px/1.5 var(--sans)"
    val bindings = mutableListOf<() -> Unit>()
    val controls = mutableListOf<dynamic>()
    var forget: () -> Unit = {}
    var closed = false
    var busy = false
    var host: dynamic = null
    fun close() {
        if (closed) return
        closed = true
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
        dialog.remove(); forget()
        if (!page.closing && truthy(focus?.isConnected)) focus.focus()
    }
    fun on(target: dynamic, event: String, action: (dynamic) -> Unit) {
        page.on(target, event, action); bindings.add { page.off(target, event, action) }
    }
    fun text(tag: String, value: String, parent: dynamic = dialog): dynamic {
        val element = document.createElement(tag).asDynamic(); element.textContent = value; parent.appendChild(element); return element
    }
    fun button(label: String, action: () -> Unit): dynamic {
        val element = text("button", label); element.type = "button"; on(element, "click") { action() }; return element
    }
    fun field(label: String, initial: String = "", multiline: Boolean = false): dynamic {
        val holder = text("label", label); holder.style.display = "block"
        val input = document.createElement(if (multiline) "textarea" else "input").asDynamic()
        input.value = initial; input.setAttribute("aria-label", label)
        input.style.cssText = "display:block;width:100%;box-sizing:border-box;margin:4px 0 10px;font:inherit" + if (multiline) ";min-height:80px" else ""
        holder.appendChild(input); controls.add(input); return input
    }
    text("h3", "Concentric work network · Kademlia DHT")
    val closeButton = button("Close", ::close)
    closeButton.setAttribute("data-network-close", "")
    button("Oroboros · view local executions") { close(); openOroboros() }
    text("p", "Store a construction with its subnet, publish this host, then discover a signed host name. Select a specific program version before requesting work.")
    val status = text("p", "Reading local network readiness…")
    status.setAttribute("role", "status"); status.setAttribute("data-network-status", "")
    val local = text("pre", ""); local.style.whiteSpace = "pre-wrap"
    val origin = field("Published HTTP origin", str(window.location.origin))
    val name = field("Signed host name")
    val discovery = text("pre", "No remote host selected."); discovery.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
    val choicesLabel = text("label", "Registered construction")
    val choices = document.createElement("select").asDynamic(); choices.setAttribute("aria-label", "Network construction")
    choices.style.width = "100%"; choicesLabel.appendChild(choices); controls.add(choices)
    val subnet = field("Network requested subnet")
    val inputs = field("Network construction inputs", "{}", true)
    val result = text("pre", ""); result.setAttribute("data-network-result", "")
    result.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:240px;overflow:auto"
    fun selected(): dynamic = arr(host?.programs).firstOrNull { str(it.program) + "@" + str(it.programCid) == str(choices.value) }
    fun clearDiscovery(message: String) {
        host = null; choices.textContent = ""; subnet.value = ""; result.textContent = ""
        discovery.textContent = message
    }
    fun showHost(value: dynamic) {
        require(typeOf(value.name) == "string" && typeOf(value.origin) == "string" && isArray(value.programs)) { "Invalid discovered host" }
        ContentId(str(value.hostCid))
        for (row in arr(value.programs)) {
            require(typeOf(row.program) == "string" && str(row.program).isNotBlank() &&
                typeOf(row.scope) == "string" && str(row.scope).isNotBlank()) { "Invalid discovered construction" }
            ContentId(str(row.programCid))
        }
        host = value; choices.textContent = ""
        val blank = text("option", "Choose a program version explicitly", choices); blank.value = ""
        for (row in arr(value.programs)) {
            val option = text("option", str(row.program) + " · " + str(row.scope) + " · " + str(row.programCid), choices)
            option.value = str(row.program) + "@" + str(row.programCid)
        }
        discovery.textContent = str(value.name) + "\n" + str(value.origin) + "\n" + str(value.hostCid) +
            "\nSigned sequence " + str(value.sequence) + " · valid until " + str(value.validUntil)
        subnet.value = ""
    }
    fun request(path: String, payload: String? = null, execution: Boolean = false, accept: (dynamic, Int) -> Unit) {
        if (closed || page.closing || busy) return
        if (execution && (programRunning || programAdmission != null || cases.busy || truthy(graph.controls?.inspectionOnly))) {
            status.textContent = "A run is active or this view is inspection-only."; return
        }
        busy = true
        for (control in controls) control.disabled = true
        if (execution) programRunning = true
        var released = false
        fun release() {
            if (released) return
            released = true; busy = false
            if (execution) programRunning = false
            if (!closed) for (control in controls) control.disabled = false
        }
        status.textContent = if (execution) "Pinned remote work requested. Awaiting its actual receipt…" else "Reading signed network evidence…"
        val completion = try { page.submit {
            try {
                if (page.closing) return@submit
                val response = if (payload == null) fetchJs(path) else fetchJs(path, jsonInit("POST", payload))
                val value = JSON.parse<dynamic>(PatchRead.text(response))
                if (!closed && !page.closing) accept(value, num(response.status).toInt())
                else if (execution && !page.closing) this@openWorkNetwork.status("Remote work returned HTTP ${response.status}; reopen the host's run records for its receipt.")
            } catch (failure: CancellationException) {
                if (!closed) status.textContent = if (execution) "Response interrupted; remote outcome unknown. Inspect host records before retrying." else "Network read interrupted."
                throw failure
            } catch (failure: dynamic) {
                if (!closed) status.textContent = (if (execution) "Remote outcome unknown: " else "Network request failed: ") + failureText(failure)
            } finally { release() }
        } } catch (failure: dynamic) { release(); status.textContent = "Request not admitted: " + failureText(failure); null }
        if (completion == null) { release(); status.textContent = "Page queue full or closing; request not sent." }
        else completion.invokeOnCompletion { release() }
    }
    fun refusal(value: dynamic, code: Int): Boolean {
        if (value.error == null && code in 200..299) return false
        status.textContent = "HTTP $code · " + str(value.error ?: "incomplete publication") + " · " + str(value.detail ?: "")
        return true
    }
    fun action(label: String, execute: () -> Unit): dynamic {
        val control = button(label, execute); controls.add(control); return control
    }
    action("Publish stored constructions") {
        val payload = objOf { it.origin = str(origin.value).trim() }
        request("/api/lcnc/network/publish", JSON.stringify(payload)) { value, code ->
            if (value.name != null) {
                local.textContent = "Host " + str(value.name) + "\n" + str(value.hostCid) + "\nDHT acknowledgements " + str(value.acknowledgements) + "/" + str(value.replicaTarget)
                name.value = value.name
                clearDiscovery("Host publication changed. Resolve its signed name before selecting work.")
            }
            if (!refusal(value, code)) status.textContent = "Host published. Resolve its signed name to inspect the exact construction versions."
        }
    }.setAttribute("data-network-publish", "")
    action("Resolve signed host") {
        clearDiscovery("Resolving signed host…")
        request("/api/lcnc/network/resolve", JSON.stringify(objOf { it.name = str(name.value).trim() })) { value, code ->
            if (!refusal(value, code)) { showHost(value); status.textContent = "Host signature and DHT quorum verified. Choose a construction version." }
        }
    }.setAttribute("data-network-resolve", "")
    on(choices, "change") { subnet.value = selected()?.scope ?: "" }
    on(name, "input") { clearDiscovery("Host name changed. Resolve again before selecting work.") }
    action("Run selected network construction") {
        try {
            val chosen = selected() ?: error("Choose a discovered construction version")
            val capturedHost = host
            val capturedProgram = str(chosen.program); val capturedCid = str(chosen.programCid)
            val capturedScope = str(chosen.scope); val capturedSubnet = str(subnet.value).trim()
            val raw = str(inputs.value).trim()
            val parsed = JSON.parse<dynamic>(raw)
            require(parsed != null && typeOf(parsed) == "object" && !isArray(parsed)) { "Inputs must be a JSON object" }
            require(capturedSubnet.isNotBlank()) { "Choose the requested subnet" }
            val payload = "{\"name\":" + JSON.stringify(capturedHost.name) + ",\"hostCid\":" + JSON.stringify(capturedHost.hostCid) +
                ",\"program\":" + JSON.stringify(capturedProgram) + ",\"programCid\":" + JSON.stringify(capturedCid) +
                ",\"subnet\":" + JSON.stringify(capturedSubnet) + ",\"inputs\":" + raw + "}"
            result.textContent = ""
            request("/api/lcnc/network/run", payload, execution = true) { value, code ->
                if (value.receipt != null) {
                    val receipt = value.receipt
                    require(value.name == capturedHost.name && value.hostCid == capturedHost.hostCid &&
                        receipt.program == capturedProgram && receipt.programCid == capturedCid &&
                        receipt.subnet == capturedSubnet && receipt.claimedAtSubnet == capturedScope &&
                        typeOf(receipt.runId) == "string" && typeOf(receipt.receiptCid) == "string") { "Remote receipt does not match the selected construction" }
                    ContentId(str(receipt.receiptCid))
                    val rendered = JSON.stringify(value, null, 2)
                    result.textContent = rendered.take(32_768) +
                        if (rendered.length > 32_768) "\n… display truncated; retained receipt " + str(receipt.receiptCid) else ""
                    status.textContent = "HTTP $code · " + str(receipt.status ?: receipt.error) + " · receipt " + str(receipt.receiptCid)
                } else if (!refusal(value, code)) error("Remote response has no matching durable receipt")
            }
        } catch (failure: dynamic) { status.textContent = "Run not admitted: " + failureText(failure) }
    }.setAttribute("data-network-run", "")
    button("Local registrations & subnet declaration") {
        close()
        if (facets.drawer == null) { facets.tab = "workgroups"; facets.toggle() } else facets.show("workgroups")
    }
    on(window, "keydown") { if (it.key == "Escape") close() }
    on(dialog, "keydown") { if (it.key == "Escape") close(); it.stopPropagation() }
    on(dialog, "pointerdown") { it.stopPropagation() }
    on(dialog, "wheel") { it.stopPropagation() }
    forget = page.own { close() }
    document.body!!.appendChild(dialog)
    closeButton.focus()
    request("/api/lcnc/network/status") { value, code ->
        if (value.state != null) local.textContent = "State " + str(value.state) + " · bootstrap peers " + str(value.bootstrapPeers) + " · registered constructions " + str(value.registeredPrograms) +
            "\nHost " + str(value.name ?: "not available")
        if (!refusal(value, code)) {
            status.textContent = if (value.state == "ACTIVE") "Ready for explicit publication or discovery." else "DHT owner unavailable. Local registration remains available."
        }
    }
}
