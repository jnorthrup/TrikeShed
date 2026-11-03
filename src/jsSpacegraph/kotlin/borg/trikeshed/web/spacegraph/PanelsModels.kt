package borg.trikeshed.web.spacegraph

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException

private data class ModelStrength(val key: String, val model: String, val strength: String)

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
    text("p", "Choose a chat seat, its system instruction and its endpoint/model routes. Key values stay in KeyMux. Applying changes the construction; Run remains a separate action.")
    val state = text("p", "Reading the current key roster and model routes…")
    state.setAttribute("role", "status"); state.setAttribute("data-model-state", "")
    val seatLabel = text("label", "Chat seat ")
    val seat = document.createElement("select").asDynamic(); seat.setAttribute("aria-label", "Chat seat"); seatLabel.appendChild(seat)
    for (n in seats.view) {
        val option = text("option", n.id + " · " + (n.params["system"]?.take(70) ?: "chat"), seat)
        option.value = n.id
    }
    if (nodeId != null && seats.view.any { it.id == nodeId }) seat.value = nodeId
    val systemLabel = text("label", "Declared seat system instruction ")
    systemLabel.style.display = "block"
    val system = document.createElement("textarea").asDynamic()
    system.setAttribute("aria-label", "Declared seat system instruction")
    system.rows = 4; systemLabel.appendChild(system)
    val roleLabel = text("label", "Declared council role ")
    val role = document.createElement("input").asDynamic()
    role.setAttribute("aria-label", "Declared council role"); roleLabel.appendChild(role)
    text("p", "The role and instruction are authored, not measured model strengths. A wired system input takes precedence where supported.")
    val keyLabel = text("label", "Provider endpoint ")
    keyLabel.style.display = "block"
    val key = document.createElement("select").asDynamic(); key.setAttribute("aria-label", "Provider endpoint"); keyLabel.appendChild(key)
    val keyDetail = text("p", "")
    val modelLabel = text("label", "Model ")
    val model = document.createElement("select").asDynamic(); model.setAttribute("aria-label", "Model"); modelLabel.appendChild(model)
    val strengthLabel = text("label", "Declared strength for this selected route ")
    strengthLabel.style.display = "block"
    val strength = document.createElement("input").asDynamic()
    strength.setAttribute("aria-label", "Declared model strength"); strength.maxLength = 500
    strengthLabel.appendChild(strength)
    val strengthHelp = text("p", "Operator declaration only; no measurement, score, or routing weight is inferred. Add the route first.")
    val selected = mutableListOf<EndpointModel>()
    var available: Series<EndpointModel> = emptySeriesOf()
    var roster: Array<dynamic> = emptyArray()
    val systems = mutableMapOf<String, String>()
    val roles = mutableMapOf<String, String>()
    val systemsEdited = mutableSetOf<String>()
    val rolesEdited = mutableSetOf<String>()
    val routeDrafts = mutableMapOf<String, List<EndpointModel>>()
    val strengthDrafts = mutableMapOf<String, MutableList<ModelStrength>>()
    val strengthFailures = mutableMapOf<String, String>()
    val strengthsEdited = mutableSetOf<String>()
    fun encodeStrengths(rows: List<ModelStrength>): String = JSON.stringify(rows.map { row ->
        objOf { it.key = row.key; it.model = row.model; it.strength = row.strength }
    }.toTypedArray())
    fun readStrengths(raw: String): MutableList<ModelStrength> {
        if (raw.isBlank()) return mutableListOf()
        require(raw.length <= 2_000_000) { "Strengths JSON exceeds the inspection limit" }
        val value: dynamic = JSON.parse(raw)
        require(isArray(value) && num(value.length) <= 256) { "Strengths must be an array of at most 256 rows" }
        val rows = mutableListOf<ModelStrength>()
        for (item in arr(value)) {
            require(item != null && typeOf(item) == "object" && !isArray(item) &&
                keysOf(item).all { it == "key" || it == "model" || it == "strength" } &&
                typeOf(item.key) == "string" && typeOf(item.model) == "string" && typeOf(item.strength) == "string") {
                "Strength rows must contain only key, model and strength strings"
            }
            val row = ModelStrength(str(item.key), str(item.model), str(item.strength))
            require(row.key.isNotBlank() && row.model.isNotBlank() && row.strength.isNotBlank() &&
                row.key.length <= 256 && row.model.length <= 256 && row.strength.length <= 500 &&
                rows.none { it.key == row.key && it.model == row.model }) { "Invalid or duplicate strength row" }
            rows.add(row)
        }
        return rows
    }
    lateinit var renderStrength: () -> Unit
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
        system.value = if (n == null) "" else systems.getOrPut(n.id) { n.params["system"] ?: "" }
        roleLabel.hidden = n?.type != "council.seat"
        role.value = if (n == null || n.type != "council.seat") "" else roles.getOrPut(n.id) { n.params["role"] ?: "" }
        if (n != null && n.id !in strengthDrafts && n.id !in strengthFailures) {
            try { strengthDrafts[n.id] = readStrengths(n.params["strengths"] ?: "") }
            catch (failure: dynamic) { strengthFailures[n.id] = failureText(failure) }
        }
        if (n != null) try { selected.addAll(routeDrafts[n.id] ?: BrainChatOptions.parse(n.params).models.view) }
        catch (failure: Throwable) { state.textContent = failure.message ?: "Invalid existing seat routes" }
        renderRoutes()
        renderStrength()
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
        renderStrength()
    }
    renderStrength = {
        val seatId = str(seat.value); val endpoint = str(key.value); val modelId = str(model.value)
        val error = strengthFailures[seatId]
        val rows = strengthDrafts[seatId]
        val chosen = selected.any { it.a == endpoint && it.b == modelId }
        strength.disabled = error != null || rows == null || !chosen
        strength.value = rows?.firstOrNull { it.key == endpoint && it.model == modelId }?.strength ?: ""
        strengthHelp.textContent = if (error != null)
            "Declared strengths cannot be edited here: $error. Repair the strengths JSON in the seat; unchanged data is retained."
        else "Operator declaration only; no measurement, score, or routing weight is inferred. Add the route first."
    }
    on(seat, "change") { loadSeat() }
    on(system, "input") { val id = str(seat.value); systems[id] = str(system.value); systemsEdited.add(id) }
    on(role, "input") { val id = str(seat.value); roles[id] = str(role.value); rolesEdited.add(id) }
    on(key, "change") { renderModels() }
    on(model, "change") { renderStrength() }
    on(strength, "input") {
        val seatId = str(seat.value); val endpoint = str(key.value); val modelId = str(model.value)
        val rows = strengthDrafts[seatId]
        if (rows != null && selected.any { it.a == endpoint && it.b == modelId }) {
            val index = rows.indexOfFirst { it.key == endpoint && it.model == modelId }
            val value = str(strength.value)
            if (value.isEmpty()) { if (index >= 0) rows.removeAt(index) }
            else if (index >= 0) rows[index] = ModelStrength(endpoint, modelId, value)
            else rows.add(ModelStrength(endpoint, modelId, value))
            strengthsEdited.add(seatId)
        }
    }
    val add = button("Add route") {
        if (!current()) { state.textContent = "Canvas changed. Reopen model selection."; return@button }
        val endpoint = str(key.value); val id = str(model.value)
        if (available.view.none { it.a == endpoint && it.b == id }) return@button
        if (selected.none { it.a == endpoint && it.b == id }) selected.add(endpoint j id)
        routeDrafts[str(seat.value)] = selected.toList()
        renderRoutes(); renderStrength()
    }
    val clear = button("Clear selected routes") {
        selected.clear(); routeDrafts[str(seat.value)] = selected.toList(); renderRoutes(); renderStrength()
    }
    var applying = false
    val apply = button("Apply seat configuration") {
        if (!current() || programRunning || programAdmission != null || cases.busy) {
            state.textContent = "Canvas changed or is running. Reopen after it is editable."; return@button
        }
        if (applying) return@button
        val target = str(seat.value)
        val systemDraft = str(system.value)
        val roleDraft = str(role.value)
        val changeSystem = target in systemsEdited
        val changeRole = target in rolesEdited
        val changeStrengths = target in strengthsEdited
        val strengthRows = strengthDrafts[target]
        if (changeStrengths && (strengthRows == null || strengthRows.size > 256 ||
                strengthRows.any { it.key.isBlank() || it.model.isBlank() || it.strength.isBlank() ||
                    it.key.length > 256 || it.model.length > 256 || it.strength.length > 500 })) {
            state.textContent = "Declared strengths need valid route names, nonblank text of at most 500 characters, and at most 256 rows."; return@button
        }
        val strengthValue = if (changeStrengths) encodeStrengths(strengthRows!!) else null
        val instruction = if (changeSystem) systemDraft else systems[target] ?: ""
        val declaredRole = if (changeRole) roleDraft else roles[target] ?: ""
        val targetNode = node(target)
        val parameters = targetNode?._paramsHost
        val modelControl = parameters?.querySelector("textarea[aria-label='models JSON']")
        val strengthControl: dynamic = parameters?.querySelector("textarea[aria-label='strengths JSON']")
        fun parameterControl(name: String): dynamic = if (parameters == null) null else
            arr(js("Array.from")(parameters.children)).firstOrNull {
                str(it.tagName) == "LABEL" && str(it.textContent) == name
            }?.nextElementSibling
        val systemControl = parameterControl("system")
        val council = str(targetNode?.type) == "council.seat"
        val roleControl: dynamic = parameterControl("role")
        if (targetNode == null || modelControl == null || !truthy(modelControl.isConnected) ||
            systemControl == null || !truthy(systemControl.isConnected) || truthy(systemControl.disabled) ||
            (str(systemControl.tagName) != "INPUT" && str(systemControl.tagName) != "TEXTAREA") ||
            (changeStrengths && (!truthy(strengthControl) || !truthy(strengthControl.isConnected))) ||
            (council && (!truthy(roleControl) || !truthy(roleControl.isConnected) || truthy(roleControl.disabled) ||
                str(roleControl.tagName) != "INPUT"))) {
            state.textContent = "Chat seat controls are unavailable. Reopen its editor before applying."; return@button
        }
        if (changeSystem && str(systemControl.tagName) == "INPUT" &&
            ('\n' in instruction || '\r' in instruction)) {
            state.textContent = "This seat's system field accepts one line. Remove line breaks before applying."; return@button
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
                require(str(seat.value) == target && str(system.value) == systemDraft && str(role.value) == roleDraft &&
                    (target in systemsEdited) == changeSystem && (target in rolesEdited) == changeRole &&
                    (target in strengthsEdited) == changeStrengths &&
                    (!changeStrengths || encodeStrengths(strengthDrafts.getValue(target)) == strengthValue) &&
                    selected.size == selection.size && selected.indices.all { selected[it] == selection[it] }) {
                    "Seat or draft changed while checking routes; apply again"
                }
                require(!programRunning && programAdmission == null && !cases.busy) { "Construction is not editable" }
                val updated = LcncModelSelection.select(program, target, selection, catalog)
                require(node(target) === targetNode && targetNode._paramsHost === parameters &&
                    truthy(modelControl.isConnected) && truthy(systemControl.isConnected) &&
                    truthy(parameters.contains(modelControl)) && truthy(parameters.contains(systemControl)) &&
                    !truthy(systemControl.disabled) && (!council || (truthy(roleControl.isConnected) &&
                    truthy(parameters.contains(roleControl)) && !truthy(roleControl.disabled))) &&
                    (!changeStrengths || (truthy(strengthControl.isConnected) && truthy(parameters.contains(strengthControl))))) { "Chat seat owner changed" }
                val value = LcncModelSelection.seats(updated).view.single { it.id == target }.params.getValue("models")
                history.boundary()
                if (changeSystem) {
                    targetNode.params["system"] = instruction
                    systemControl.value = instruction
                }
                if (council && changeRole) { targetNode.params["role"] = declaredRole; roleControl.value = declaredRole }
                if (changeStrengths) {
                    targetNode.params["strengths"] = strengthValue
                    strengthControl.value = strengthValue
                }
                modelControl.value = value
                modelControl.dispatchEvent(jsNew(js("Event"), "input"))
                if (changeStrengths) strengthControl.dispatchEvent(jsNew(js("Event"), "input"))
                history.boundary()
                status("Seat configuration applied to $target. Inspect the construction, then choose Run.")
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
