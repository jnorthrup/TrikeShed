package borg.trikeshed.web.spacegraph

import borg.trikeshed.context.nuid.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncConfixControls
import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** The existing stored-program declaration and run routes, with explicit operator selection. */
class PanelsWorkgroups(val panels: Panels) {
    private data class Registration(val name: String, val program: String, val cid: String, val scope: String) {
        val key: String get() = "$program@$cid"
    }

    private var registrations: Series<Registration> = emptySeriesOf()
    private var refusals: dynamic = null
    private var state = "unavailable"
    private var selected: Registration? = null
    private var requestedSubnet = ""
    private var inputs = "{}"
    private var sourceReceipt = ""
    private var receipt: dynamic = null
    private var message = "Choose a registered stored construction explicitly. Opening does not save or run."
    private var canvas: dynamic = null
    private var canvasRevision = -1
    private var declaration = ""
    private val bindings = mutableListOf<() -> Unit>()

    init { panels.page.own { clearBindings() } }

    private fun clearBindings() {
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
    }

    private fun on(target: dynamic, type: String, handler: (dynamic) -> Unit) {
        panels.page.on(target, type, handler)
        bindings.add { panels.page.off(target, type, handler) }
    }

    private fun current(serial: Int): Boolean = !panels.page.closing && panels.facets.drawer != null &&
        panels.facets.tab == "workgroups" && panels.facets.revision == serial

    private fun visible(): Boolean = !panels.page.closing && panels.facets.drawer != null &&
        panels.facets.tab == "workgroups"

    private fun occupied(): Boolean = panels.programRunning || panels.programAdmission != null || panels.cases.busy

    private fun editable(): Boolean = !panels.page.closing && !truthy(panels.graph.controls?.inspectionOnly)

    private fun subnet(value: String): Subnet {
        LcncConfixControls(subnet = value)
        return Subnet.parse(value)
    }

    private fun registration(row: dynamic): Registration {
        fun text(value: dynamic): String {
            require(typeOf(value) == "string" && str(value).isNotBlank()) { "Invalid registered construction" }
            return str(value)
        }
        val program = text(row.program)
        val cid = text(row.programCid)
        ContentId(cid)
        val scope = text(row.scope)
        subnet(scope)
        val name = text(row.name)
        require(name == "lcnc/program/$program") { "Registered construction identity mismatch" }
        return Registration(name, program, cid, scope)
    }

    /** Called in the existing page-owned facet read; stale reads cannot replace this view. */
    suspend fun show(serial: Int) {
        try {
            val response = fetchJs("/api/lcnc/workgroups")
            val value = JSON.parse<dynamic>(PatchRead.text(response))
            require(num(response.status).toInt() == 200 && value.error == null && isArray(value.workgroups)) {
                str(value.error ?: "Workgroup inventory unavailable")
            }
            val rows = arr(value.workgroups).map { registration(it) }.toSeries()
            require(rows.view.map { it.key }.distinct().size == rows.size) { "Duplicate registered construction" }
            if (!current(serial)) return
            registrations = rows; refusals = value.refusals; state = str(value.state)
            render()
        } catch (failure: CancellationException) { throw failure }
        catch (failure: dynamic) {
            if (!current(serial)) return
            registrations = emptySeriesOf(); refusals = null; state = "unavailable"
            message = "Workgroups unavailable: " + failureText(failure) + ". No source substituted."
            render()
        }
    }

    private fun repaint() { if (visible()) render() }

    private fun render() {
        if (!visible()) return
        val facets = panels.facets
        val body = facets.body ?: return
        clearBindings()
        body.textContent = ""
        if (canvas !== panels.graph || canvasRevision != panels.documentRevision) {
            canvas = panels.graph; canvasRevision = panels.documentRevision
            declaration = if (typeOf(panels.graph.controls?.subnet) == "string") str(panels.graph.controls.subnet) else ""
        }
        val locked = occupied() || !editable()
        fun field(label: String, value: String, multiline: Boolean = false, change: (String) -> Unit): dynamic {
            facets.text(label)
            val element = document.createElement(if (multiline) "textarea" else "input").asDynamic()
            element.setAttribute("aria-label", label); element.value = value; element.disabled = locked
            element.style.cssText = "width:100%;box-sizing:border-box;font:inherit" + if (multiline) ";min-height:90px" else ""
            body.appendChild(element)
            on(element, "input", { change(str(element.value)) })
            return element
        }
        fun action(label: String, enabled: Boolean = true, execute: () -> Unit) {
            val button = document.createElement("button").asDynamic()
            button.textContent = label; button.disabled = locked || !enabled
            button.style.cssText = "margin:6px 6px 6px 0"; body.appendChild(button)
            on(button, "click", { execute() })
        }
        facets.text("Current canvas declaration. Apply or Clear changes only local construction/history. Use the existing ⇪ store toolbar action to publish it. Loading and discovery do not install programs; ordinary canvas Run remains inline/local.")
        field("Declared subnet", declaration) { declaration = it }
        action("Apply subnet") { applyDeclaration(declaration) }
        action("Clear subnet") { applyDeclaration(null) }
        action("DHT host discovery") { panels.openWorkNetwork() }
        facets.text("Registered stored constructions · " + state + ". Refresh reads the actual registry; it never selects a newer version or runs one.")
        val choice = document.createElement("select").asDynamic()
        choice.setAttribute("aria-label", "Stored program"); choice.disabled = locked
        fun option(key: String, label: String) {
            val entry = document.createElement("option").asDynamic()
            entry.value = key; entry.textContent = label; choice.appendChild(entry)
        }
        option("", "Choose explicitly")
        for (row in registrations.view) option(row.key, row.program + " · " + row.cid + " · " + row.scope)
        val selection = selected
        val available = selection != null && registrations.view.any { it == selection }
        if (selection != null && !available) option(selection.key,
            selection.program + " · " + selection.cid + " (selected version no longer registered)")
        choice.value = selection?.key ?: ""; body.appendChild(choice)
        on(choice, "change", {
            selected = registrations.view.singleOrNull { it.key == str(choice.value) }
            requestedSubnet = selected?.scope ?: ""
            repaint()
        })
        if (selection != null && !available) facets.text("The selected version is unavailable. Choose a current registration explicitly; no replacement was selected.")
        if (refusals != null) for (key in keysOf(refusals).sorted()) facets.text("Registration refused: " + key + " · " + str(refusals[key]))
        field("Requested subnet", requestedSubnet) { requestedSubnet = it }
        field("Construction inputs (JSON object)", inputs, true) { inputs = it }
        field("Source run receipt CID", sourceReceipt) { sourceReceipt = it }
        facets.text("Run stored construction executes the selected saved version, independent of this canvas. The optional source CID transfers an existing completed receipt as inputs.evidence; it does not replay its producer or infer a ballot. The server checks scope, version and source authority.")
        action("Run stored construction", state == "ACTIVE" && available) { runStored() }
        facets.text(message)
        val result = receipt
        if (result != null) {
            facets.text("Recorded stored run: " + str(result.runId) + " · " + str(result.status) +
                "\nProgram: " + str(result.programCid) + "\nWorkgroup: " + str(result.workgroup) +
                "\nRequested subnet: " + str(result.subnet) + " · claimed scope: " + str(result.claimedAtSubnet))
            facets.pre(executionPreview(result.returns))
            fun content(label: String, cid: dynamic) {
                if (typeOf(cid) == "string" && Regex("sha256:[0-9a-f]{64}").matches(str(cid)))
                    action(label) { facets.inspectContent(str(cid)) }
            }
            content("Run receipt in Confix", result.receiptCid)
            content("Stored program in Confix", result.programCid)
            content("Run invocations in Confix", result.invocationsCid)
        }
        facets.text("Unknown responses are not automatically retried. Inspect run records before another execution. Closing the drawer does not cancel admitted work; outputs are not painted onto this canvas.")
    }

    private fun applyDeclaration(value: String?) {
        if (!editable()) return
        if (canvas !== panels.graph || canvasRevision != panels.documentRevision) {
            message = "Declaration not changed: canvas changed; refresh Workgroups before applying."
            panels.status(message)
            return
        }
        try {
            require(!occupied()) { "Execution is busy; declaration not changed" }
            val path = value?.trim()?.also { subnet(it) }
            val controls = if (panels.graph.controls == null) obj() else spread(panels.graph.controls)
            if (path == null) js("Reflect.deleteProperty")(controls, "subnet") else controls.subnet = path
            panels.stopSources(); panels.pulseReceipt = null; panels.pulseProgram = null
            panels.graph.controls = controls
            panels.save()
            message = if (path == null) "Local subnet declaration cleared. Use ⇪ store to withdraw the saved registration."
                else "Local subnet declaration applied: $path. Use ⇪ store to install it; no program ran."
            repaint()
        } catch (failure: dynamic) { message = "Declaration not changed: " + failureText(failure); repaint() }
    }

    /** Reserve the existing global execution signal before this callback enters the page queue. */
    private fun runStored() {
        if (!editable()) return
        try {
            require(!occupied()) { "Execution is busy; no stored run admitted" }
            val chosen = selected ?: error("Choose a stored construction explicitly")
            require(state == "ACTIVE" && registrations.view.any { it == chosen }) { "Selected registration is no longer available; refresh" }
            val requested = requestedSubnet.trim()
            val destination = subnet(requested)
            val scope = subnet(chosen.scope)
            require((scope contains destination) && destination.level - scope.level <= 3) { "Requested subnet is outside the selected construction's scope" }
            val data = inputs.trim()
            val value = JSON.parse<dynamic>(data)
            require(value != null && typeOf(value) == "object" && !isArray(value)) { "Construction inputs must be a JSON object" }
            val source = sourceReceipt.trim()
            if (source.isNotEmpty()) ContentId(source)
            // Keep the captured data's numeric tokens; parsing checks its object shape, not its wire encoding.
            val request = "{\"program\":" + JSON.stringify(chosen.program) +
                ",\"expectProgramCid\":" + JSON.stringify(chosen.cid) + ",\"subnet\":" + JSON.stringify(requested) +
                ",\"inputs\":" + data + (if (source.isEmpty()) "" else ",\"sourceReceiptCid\":" + JSON.stringify(source)) + "}"
            panels.programRunning = true
            receipt = null
            message = "Stored construction admitted · " + chosen.key + " · awaiting its durable response…"
            var released = false
            fun release() {
                if (!released) { released = true; panels.programRunning = false; repaint() }
            }
            val completion = try {
                repaint()
                panels.page.submit {
                try {
                    if (panels.page.closing) return@submit
                    panels.stopSources(); panels.pulseReceipt = null; panels.pulseProgram = null
                    val response = fetchJs("/api/lcnc/run", jsonInit("POST", request))
                    val result = JSON.parse<dynamic>(PatchRead.text(response))
                    if (!panels.page.closing) {
                        val valid = typeOf(result?.receiptCid) == "string" && typeOf(result.runId) == "string" &&
                            result.programCid == chosen.cid && result.program == chosen.program
                        receipt = if (valid) result else null
                        if (valid) { panels.lastProgramRun = result; panels.facets.selectedReceipt = result }
                        message = "HTTP " + str(response.status) + " · " + str(result.status ?: result.error ?: "unrecognized response") +
                            (if (result.error != null) " · " + str(result.error) else "") +
                            if (valid) " · actual receipt retained; no canvas execution or automatic retry."
                            else " · outcome may be unknown; inspect run records. No automatic retry."
                    }
                } catch (failure: CancellationException) {
                    message = "Stored run response interrupted; outcome unknown. No automatic retry."
                    throw failure
                } catch (failure: dynamic) {
                    message = "Stored run outcome unknown: " + failureText(failure) + ". Inspect run records; no automatic retry."
                } finally { release() }
            } } catch (failure: dynamic) { release(); throw failure }
            if (completion == null) {
                message = "Page queue is full or closing; stored run not sent"
                release()
            } else completion.invokeOnCompletion { release() }
        } catch (failure: dynamic) { message = "Stored run not admitted: " + failureText(failure); repaint() }
    }
}
