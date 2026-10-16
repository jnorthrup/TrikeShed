package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Resolve a saved task at load time; execution still requires the ordinary explicit Run action. */
suspend fun Panels.loadDocumentTask(taskId: String): Boolean {
    require(taskId.isNotBlank()) { "A document task identity is required" }
    val before = JSON.stringify(toConfix())
    val response = fetchJs("/api/board?documentTask=" + encodeURIComponent(taskId))
    val task = JSON.parse<dynamic>(PatchRead.text(response))
    require(truthy(response.ok)) { str(task?.detail ?: task?.error ?: "Document task is unavailable") }
    require(task.jobId == taskId && typeOf(task.revision) == "number" && typeOf(task.receiptCid) == "string") {
        "Document task response identity is invalid"
    }
    val doc = task.document
    require(doc != null && doc.controls?.humanOversight == true && isArray(doc.nodes) && isArray(doc.wires)) {
        "Document correction must require human oversight"
    }
    val correction = arr(doc.nodes).single { it.id == "correction" && it.type == "document.correct" }
    require(correction.params.taskId == task.jobId && correction.params.expectedRevision == str(task.revision) &&
        correction.params.priorReceiptCid == task.receiptCid) { "Correction does not match the current task snapshot" }
    require(before == JSON.stringify(toConfix())) { "Canvas changed while reading the task; load it again to replace the current canvas" }
    if (!load(fromConfix(doc))) return false
    panelName().value = "document-correction"
    status("Document task · revision " + str(task.revision) + " · " + str(task.column) +
        " loaded without execution. Inspect source and evidence, then Run; later task changes are refused.")
    return true
}

/** Shared inert handoff for node results and retained cursor pages. */
fun Panels.documentConstructionAction(target: dynamic, label: String, preset: String,
    current: () -> Boolean, language: (() -> String)? = null, configure: (dynamic) -> Unit) {
    val button = document.createElement("button").asDynamic()
    button.textContent = label; button.style.cssText = "display:block;margin:8px 0"
    target.appendChild(button)
    on(button, "click", { e ->
        e.stopPropagation()
        val before = JSON.stringify(toConfix())
        val selectedLanguage = language?.invoke()
        button.disabled = true
        launchJs {
            try {
                require(current()) { "Document evidence changed; use the current result" }
                val recipe = if (selectedLanguage == null) {
                    val response = fetchJson("/api/panels/presets")
                    arr(response.presets).singleOrNull { str(it.name) == preset }
                        ?: error("Document construction is unavailable: $preset")
                } else {
                    val response = fetchJson("/api/lcnc/workspace")
                    val entry = arr(response.languages).singleOrNull { it.id == selectedLanguage }
                    require(entry?.available == true && entry.documentRoutingConstruction?.document != null) {
                        "Document routing unavailable for $selectedLanguage: " + str(entry?.note ?: "language or runners missing")
                    }
                    entry.documentRoutingConstruction
                }
                val doc = JSON.parse<dynamic>(JSON.stringify(recipe.document))
                configure(doc)
                require(before == JSON.stringify(toConfix()) && current() && selectedLanguage == language?.invoke()) {
                    "Canvas or result changed; use the current result to load the construction"
                }
                if (!load(fromConfix(doc))) return@launchJs
                panelName().value = str(recipe.name).removePrefix("preset-")
                status(str(recipe.title ?: recipe.name) + " loaded without execution — inspect parameters, then Run. Undo restores the previous canvas.")
            } catch (e: CancellationException) { throw e } catch (e: dynamic) {
                status("Document construction not loaded: " + failureText(e))
            } finally { button.disabled = false }
        }
    })
}

/** Language availability is checked on load; selecting a language never executes or saves a construction. */
fun Panels.documentRoutingAction(target: dynamic, current: () -> Boolean, configure: (dynamic) -> Unit) {
    val label = document.createElement("label").asDynamic()
    label.textContent = "Route authoring language "
    val select = document.createElement("select").asDynamic()
    select.setAttribute("aria-label", "Document route language")
    for (language in borg.trikeshed.vm.VmLanguage.entries) {
        val option = document.createElement("option").asDynamic()
        option.value = language.id; option.textContent = language.label; select.appendChild(option)
    }
    select.value = "js"; label.appendChild(select); target.appendChild(label)
    documentConstructionAction(target, "Load language-authored document route (no execution)", "preset-document-routing",
        current, { str(select.value) }, configure)
}

/** Preserve the returned page, including group ordinals in its exact immutable receipt. */
fun pinDocumentRulePage(doc: dynamic, cid: String, page: dynamic) {
    require(page.id == "document/$cid/rules" && isArray(page.axes) && isArray(page.selections)) {
        "Expected a retained document rules page"
    }
    fun ordinal(value: dynamic): String {
        require(typeOf(value) == "number" && num(value).isFinite() && num(value) >= 0 && num(value) % 1.0 == 0.0) {
            "Invalid rules page window"
        }
        return str(value)
    }
    require(num(page.pageSize) in 1.0..128.0) { "Invalid rules page size" }
    val sheet = arr(doc.nodes).single { it.id == "sheet" && it.type == "document.sheet" }
    sheet.params.receiptCid = cid
    sheet.params.view = "rules"
    sheet.params.offset = ordinal(page.offset)
    sheet.params.limit = ordinal(page.pageSize)
    sheet.params.axes = JSON.stringify(page.axes)
    sheet.params.selections = JSON.stringify(page.selections)
}

/** Read-only sheet navigation can hand its visible selection to an authored construction. */
fun Panels.documentRulePageActions(target: dynamic, page: dynamic, current: () -> Boolean) {
    val cid = Regex("^document/(sha256:[0-9a-f]{64})/rules$").matchEntire(str(page.id))?.groupValues?.get(1) ?: return
    val note = document.createElement("p")
    note.textContent = "Load this visible rules page with its receipt, offset and indexes. Assumptions start empty; loading does not run. AI deliberation sends selected evidence to the configured provider only on Run."
    target.appendChild(note)
    for ((label, preset) in arrayOf(
        "Load simulation from this rules page (no execution)" to "preset-document-simulation",
        "Load AI deliberation from this rules page (no execution)" to "preset-document-deliberation",
    )) documentConstructionAction(target, label, preset, current) { doc ->
        pinDocumentRulePage(doc, cid, page)
        arr(doc.nodes).single { it.id == "assumptions" && it.type == "json.value" }.params.value = "[]"
    }
    documentRoutingAction(target, current) { doc ->
        pinDocumentRulePage(doc, cid, page)
        arr(doc.nodes).single { it.id == "assumptions" && it.type == "json.value" }.params.value = "[]"
    }
}

/** Presentation of the existing document runners. Buttons load authored constructions, never execute them. */
fun Panels.renderDocumentReview(n: dynamic, out: dynamic) {
    setResult(n, out)
    val target = n.el.querySelector(".result") ?: return
    fun action(label: String, preset: String, configure: (dynamic) -> Unit) =
        documentConstructionAction(target, label, preset, { n._lastOut === out && truthy(n.el.isConnected) }, configure = configure)
    if (n.type == "document.curate" && typeOf(out.receiptCid) == "string") {
        action("Load review tasks for this receipt (no execution)", "preset-document-review") { doc ->
            val receipt = arr(doc.nodes).single { it.id == "receipt" && it.type == "scope.in" }
            receipt.params.default = out.receiptCid
        }
    }
    if (n.type in arrayOf("document.curate", "document.sheet") && typeOf(out.receiptCid) == "string") {
        fun pinSimulation(doc: dynamic) {
            val sheet = arr(doc.nodes).single { it.id == "sheet" && it.type == "document.sheet" }
            sheet.params.receiptCid = out.receiptCid
            // Preserve an actual returned rule page, not parameters edited since it ran.
            if (n.type == "document.sheet" && out.sheet?.id == "document/" + str(out.receiptCid) + "/rules") {
                pinDocumentRulePage(doc, str(out.receiptCid), out.sheet)
            }
            // Carry only an explicitly wired JSON assumption source; never infer parties from text.
            val wires = arr(graph.wires)
            val consumers = wires.filter { it.from[0] == n.id && it.from[1] == "ruleCandidates" }
                .map { it.to[0] }
            val simulation = arr(graph.nodes).singleOrNull { it.id in consumers && it.type == "nars.reteFire" }
            val assertionWire = if (simulation == null) null else wires.singleOrNull {
                it.to[0] == simulation.id && str(it.to[1]).removeSuffix("?") == "assertions"
            }
            val source = if (assertionWire == null || assertionWire.from[1] != "value") null else
                arr(graph.nodes).singleOrNull { it.id == assertionWire.from[0] && it.type == "json.value" }
            if (source != null && typeOf(source.params?.value) == "string") {
                arr(doc.nodes).single { it.id == "assumptions" }.params.value = source.params.value
            }
        }
        action("Load retained simulation for this receipt (no execution)", "preset-document-simulation", ::pinSimulation)
        action("Load AI deliberation for this receipt (no execution)", "preset-document-deliberation", ::pinSimulation)
        documentRoutingAction(target, { n._lastOut === out && truthy(n.el.isConnected) }, ::pinSimulation)
    }
    if (n.type == "document.tasks") {
        val tasks = (arr(out.tasks) + arr(out.unchanged)).distinctBy { str(it.jobId) }
        for (task in tasks.take(32)) {
            if (typeOf(task.jobId) != "string" || typeOf(task.receiptCid) != "string" ||
                typeOf(task.revision) != "number") continue
            action("Load correction · " + str(task.issue?.kind ?: "document") + " · revision " + str(task.revision) +
                " · " + str(task.column), "preset-document-correction") { doc ->
                val correction = arr(doc.nodes).single { it.id == "correction" && it.type == "document.correct" }
                correction.params.taskId = task.jobId
                correction.params.expectedRevision = str(task.revision)
                correction.params.priorReceiptCid = task.receiptCid
            }
        }
        if (tasks.size > 32) {
            val notice = document.createElement("p")
            notice.textContent = "First 32 task actions shown; all task references remain in the result above."
            target.appendChild(notice)
        }
    }
}
