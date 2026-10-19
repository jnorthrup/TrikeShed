package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

/** KeyMux identities and ModelMux routes are inspected without executing the construction. */
fun Panels.openModelSelection(nodeId: String? = null) {
    if (page.closing) return
    byId("panelModelSelection")?.querySelector("[data-model-close]")?.click()
    val focus = document.activeElement.asDynamic()
    val canvas = graph
    val captured = documentRevision
    val program = try { boardDefinition() }
        catch (failure: dynamic) { status("Model selection unavailable: " + failureText(failure)); return }
    val seats = LcncModelSelection.seats(program)
    val dialog = document.createElement("section").asDynamic()
    dialog.id = "panelModelSelection"; dialog.setAttribute("role", "dialog")
    dialog.setAttribute("aria-label", "Provider keys and models")
    dialog.style.cssText = "position:fixed;top:58px;right:16px;width:min(600px,calc(100vw - 32px));max-height:82vh;overflow:auto;z-index:62;background:var(--panel);color:var(--ink);border:1px solid var(--wire);border-radius:10px;padding:18px;box-shadow:0 12px 40px #0009;font:12px/1.5 var(--sans)"
    val bindings = mutableListOf<() -> Unit>()
    var closed = false
    var forget: () -> Unit = {}
    fun close() {
        if (closed) return
        closed = true
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
        dialog.remove(); forget()
        if (!page.closing && truthy(focus?.isConnected)) focus.focus()
    }
    fun current() = !closed && !page.closing && graph === canvas && documentRevision == captured
    fun on(target: dynamic, event: String, action: (dynamic) -> Unit) {
        page.on(target, event, action); bindings.add { page.off(target, event, action) }
    }
    fun text(tag: String, value: String, parent: dynamic = dialog): dynamic {
        val el = document.createElement(tag).asDynamic(); el.textContent = value; parent.appendChild(el); return el
    }
    fun button(label: String, action: () -> Unit): dynamic {
        val el = text("button", label); el.type = "button"; on(el, "click") { action() }; return el
    }
    text("h3", "Provider keys & models")
    val closeButton = button("Close", ::close)
    closeButton.setAttribute("data-model-close", "")
    text("p", "Choose a chat seat, then its endpoint and model. Key values stay in KeyMux. Applying changes the construction; Run remains a separate action.")
    val state = text("p", "Reading the current key roster and model routes…")
    state.setAttribute("role", "status"); state.setAttribute("data-model-state", "")
    val seatLabel = text("label", "Chat seat ")
    val seat = document.createElement("select").asDynamic(); seat.setAttribute("aria-label", "Chat seat"); seatLabel.appendChild(seat)
    for (n in seats.view) {
        val option = text("option", n.id + " · " + (n.params["system"]?.take(70) ?: "chat"), seat)
        option.value = n.id
    }
    if (nodeId != null && seats.view.any { it.id == nodeId }) seat.value = nodeId
    val keyLabel = text("label", "Provider endpoint ")
    keyLabel.style.display = "block"
    val key = document.createElement("select").asDynamic(); key.setAttribute("aria-label", "Provider endpoint"); keyLabel.appendChild(key)
    val keyDetail = text("p", "")
    val modelLabel = text("label", "Model ")
    val model = document.createElement("select").asDynamic(); model.setAttribute("aria-label", "Model"); modelLabel.appendChild(model)
    val selected = mutableListOf<EndpointModel>()
    var available: Series<EndpointModel> = emptySeriesOf()
    var roster: Array<dynamic> = emptyArray()
    val routes = text("ol", "")
    routes.setAttribute("aria-label", "Ordered model routes")
    val existing = text("p", "")
    fun renderRoutes() {
        routes.textContent = ""
        for ((index, route) in selected.withIndex()) text("li", "${index + 1}. ${route.a} / ${route.b}", routes)
        existing.textContent = if (selected.isEmpty()) "No routes selected. Add one or more in failover order." else "Only these routes will be admitted for this seat, in this order."
    }
    fun loadSeat() {
        selected.clear()
        val n = seats.view.firstOrNull { it.id == str(seat.value) }
        if (n != null) try { selected.addAll(BrainChatOptions.parse(n.params).models.view) }
        catch (failure: Throwable) { state.textContent = failure.message ?: "Invalid existing seat routes" }
        renderRoutes()
    }
    fun renderModels() {
        val endpoint = str(key.value)
        model.textContent = ""
        for (route in available.view.filter { it.a == endpoint }) {
            val option = text("option", route.b, model); option.value = route.b
        }
        val row = roster.firstOrNull { str(it.name) == endpoint }
        keyDetail.textContent = when {
            row == null -> "Key presence was not reported for this endpoint."
            truthy(row.keyPresent) -> "Key resolved · " + str(row.envVar ?: "") + " · " + str(row.base ?: "")
            else -> "Key not currently resolved · " + str(row.envVar ?: "") + " · configure the existing KeyMux binding before Run."
        }
    }
    on(seat, "change") { loadSeat() }
    on(key, "change") { renderModels() }
    val add = button("Add route") {
        if (!current()) { state.textContent = "Canvas changed. Reopen model selection."; return@button }
        val endpoint = str(key.value); val id = str(model.value)
        if (available.view.none { it.a == endpoint && it.b == id }) return@button
        if (selected.none { it.a == endpoint && it.b == id }) selected.add(endpoint j id)
        renderRoutes()
    }
    val clear = button("Clear selected routes") { selected.clear(); renderRoutes() }
    var applying = false
    val apply = button("Apply routes to seat") {
        if (!current() || programRunning || programAdmission != null || cases.busy) {
            state.textContent = "Canvas changed or is running. Reopen after it is editable."; return@button
        }
        if (applying) return@button
        val target = str(seat.value)
        val targetNode = node(target)
        val parameters = targetNode?._paramsHost
        val modelControl = parameters?.querySelector("textarea[aria-label='models JSON']")
        if (targetNode == null || modelControl == null || !truthy(modelControl.isConnected)) {
            state.textContent = "Chat seat controls are unavailable. Reopen its editor before applying."; return@button
        }
        val selection = selected.toTypedArray().toSeries()
        applying = true
        val admitted = page.submit {
            try {
                // Re-read availability at application time; a stale discovery is not routing authority.
                val response = fetchJson("/api/mux/models")
                require(response.error == null && isArray(response.models)) { "Model routes unavailable" }
                val catalog = arr(response.models).map { str(it.name) j str(it.model) }.toSeries()
                if (!current()) { if (!closed) state.textContent = "Canvas changed. Reopen model selection."; return@submit }
                require(!programRunning && programAdmission == null && !cases.busy) { "Construction is not editable" }
                val updated = LcncModelSelection.select(program, target, selection, catalog)
                require(node(target) === targetNode && targetNode._paramsHost === parameters && truthy(modelControl.isConnected)) { "Chat seat owner changed" }
                val value = LcncModelSelection.seats(updated).view.single { it.id == target }.params.getValue("models")
                history.boundary()
                modelControl.value = value
                modelControl.dispatchEvent(jsNew(js("Event"), "input"))
                history.boundary()
                status("Routes applied to $target. Inspect the construction, then choose Run.")
                close()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: dynamic) { if (!closed) state.textContent = "Selection refused: " + failureText(failure) }
            finally { applying = false }
        }
        if (admitted == null) {
            applying = false
            state.textContent = "Model selection was not admitted; the page is busy or closing."
        }
    }
    button("KeyMux bindings") { launchJs { openKeyMuxDlg() } }
    button("Concentric work network") {
        close()
        openWorkNetwork()
    }
    on(window, "keydown") { if (it.key == "Escape") close() }
    on(window, "lcnc:shadow-update") {
        if (!current()) {
            state.textContent = "Canvas changed. Reopen model selection before applying."
            apply.disabled = true; add.disabled = true
        }
    }
    on(dialog, "pointerdown") { it.stopPropagation() }
    on(dialog, "keydown") { if (it.key == "Escape") close(); it.stopPropagation() }
    on(dialog, "wheel") { it.stopPropagation() }
    forget = page.own { close() }
    document.body!!.appendChild(dialog)
    closeButton.focus()
    add.disabled = true; apply.disabled = true
    clear.disabled = seats.size == 0
    loadSeat()
    launchJs {
        try {
            val keys = fetchJson("/api/mux/keys")
            val models = fetchJson("/api/mux/models")
            require(keys.error == null && isArray(keys.roster)) { "Key roster unavailable" }
            require(models.error == null && isArray(models.models)) { "Model routes unavailable" }
            if (!current()) return@launchJs
            roster = arr(keys.roster)
            available = arr(models.models).map { str(it.name) j str(it.model) }.toSeries()
            for (name in available.view.map { it.a }.distinct()) {
                val row = roster.firstOrNull { str(it.name) == name }
                val option = text("option", name + if (row != null && truthy(row.keyPresent)) " · key resolved" else " · key unresolved", key)
                option.value = name
            }
            renderModels()
            val editable = seats.size > 0
            add.disabled = !editable || available.size == 0; apply.disabled = !editable || available.size == 0
            state.textContent = if (seats.size == 0) "This construction has no mux.chat or council.seat nodes. Open a debate or legal-panel use case to configure its seats."
                else "${seats.size} chat seats · ${available.size} endpoint/model routes. Key presence is a discovery snapshot; Run resolves credentials again."
        } catch (failure: CancellationException) { throw failure }
        catch (failure: dynamic) { if (!closed) state.textContent = "Discovery unavailable: " + failureText(failure) }
    }
}
