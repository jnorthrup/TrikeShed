package borg.trikeshed.web.spacegraph

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.web.*
import borg.trikeshed.parse.confix.Syntax
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

/** Operator commands over the existing case/run protocol; no browser workflow engine. */
class PanelsCases(val panels: Panels) {
    private data class CaseProgram(val caseId: String, val cid: String, val document: String, val value: LcncProgram)

    var busy = false
        private set
    private var program = ""
    private var caseId = ""
    private var brief = ""
    private var target = ""
    private var message = "Choose a program and case ID. Opening this view performs no case command."
    private var catalog: dynamic = null
    private var receipt: dynamic = null
    private var head: dynamic = null
    private var observedCase: dynamic = null
    private var retained: CaseProgram? = null
    private var retry: String? = null
    private var lastRequest: String? = null
    private val bindings = mutableListOf<() -> Unit>()

    init { panels.page.own { clearBindings() } }

    private fun clearBindings() {
        while (bindings.isNotEmpty()) bindings.removeAt(bindings.lastIndex).invoke()
    }

    private fun on(target: dynamic, type: String, handler: (dynamic) -> Unit) {
        panels.page.on(target, type, handler)
        bindings.add { panels.page.off(target, type, handler) }
    }

    private fun repaint() {
        if (!panels.page.closing && panels.facets.drawer != null && panels.facets.tab == "cases") render(catalog)
    }

    fun render(value: dynamic) {
        catalog = value
        val facets = panels.facets
        val body = facets.body ?: return
        clearBindings()
        var transitionButton: dynamic = null
        var targetSelect: dynamic = null
        body.textContent = ""
        facets.text("Case commands use the existing durable run queue, not the canvas. Initialize stores the brief without calling seats. Read case records a run receipt but advances no case stage and calls no model. Transition may call the authored model seat or settle the case; it never advances automatically.")
        val choices = arr(catalog?.cases)
        fun select(label: String, selected: String, options: List<Pair<String, String>>, change: (String) -> Unit): dynamic {
            facets.text(label)
            val field = document.createElement("select").asDynamic()
            field.setAttribute("aria-label", label)
            for ((key, title) in listOf("" to "Choose explicitly") + options) {
                val option = document.createElement("option").asDynamic()
                option.value = key; option.textContent = title; field.appendChild(option)
            }
            field.value = selected; field.disabled = busy
            body.appendChild(field)
            on(field, "change", { change(str(field.value)); repaint() })
            return field
        }
        val frozen = retained?.takeIf { it.caseId == caseId }
        val retainedKey = frozen?.let { "retained/" + it.cid }
        val sources = choices.map { ("named/" + str(it.name)) to (str(it.title) + " · " + str(it.name)) } +
            if (frozen == null) emptyList() else listOf(checkNotNull(retainedKey) to
                ("Retained case program · " + frozen.value.name + " · " + frozen.cid))
        select("Case program", retainedKey ?: program.takeIf { it.isNotBlank() }?.let { "named/$it" }.orEmpty(), sources) {
            if (it != retainedKey) {
                program = it.removePrefix("named/"); retained = null; head = null; receipt = null; target = ""
            }
        }
        fun input(label: String, selected: String, multiline: Boolean = false, enabled: Boolean = true, change: (String) -> Unit) {
            facets.text(label)
            val field = document.createElement(if (multiline) "textarea" else "input").asDynamic()
            field.setAttribute("aria-label", label); field.value = selected; field.disabled = busy || !enabled
            field.style.cssText = "width:100%;box-sizing:border-box;font:inherit" + if (multiline) ";min-height:90px" else ""
            body.appendChild(field)
            on(field, "input", { change(str(field.value)) })
        }
        input("Case ID", caseId, enabled = frozen == null) {
            caseId = it; head = null; receipt = null; observedCase = null; retained = null; target = ""
            if (transitionButton != null) transitionButton.disabled = true
            if (targetSelect != null) { targetSelect.value = ""; targetSelect.disabled = true }
        }
        input("Initial brief", brief, true) { brief = it }
        fun action(label: String, enabled: Boolean = true, run: () -> Unit): dynamic {
            val button = document.createElement("button").asDynamic()
            button.textContent = label; button.disabled = busy || !enabled || truthy(panels.graph.controls?.inspectionOnly)
            button.style.cssText = "margin:6px 6px 6px 0"; body.appendChild(button)
            on(button, "click", { run() })
            return button
        }
        action("Initialize case (no model)", retry == null && frozen == null) { admit("initialize") }
        action("Read case") { admit("read") }
        val observed = observedCase
        if (observed != null && observed.caseId == caseId) {
            facets.text("Last observed case program: " + str(observed.programCid) +
                ". Reading this case does not rebind it to the named selection.")
            action("Load frozen case program") { loadFrozen() }
        }
        if (frozen != null) facets.text("Retained construction selected · " + frozen.cid +
            ". Commands use these frozen bytes, even if its former name changed or disappeared. Loading does not read or advance the case; choose Read case before a new transition.")
        val offering = choices.singleOrNull { it.name == program }
        val selected = head
        val sourceCid = frozen?.cid ?: offering?.programCid
        val active = selected != null && selected.caseId == caseId && selected.lifecycle == "active" &&
            selected.programCid == sourceCid && selected.phase != "terminal-pending" && selected.phase != "settled"
        val targets = if (!active) emptyList() else if (frozen != null)
            frozen.value.kanban!!.edges.view.filter { it.from == str(selected.lane) || it.mode == KanbanEdgeMode.ABORT }
                .map { it.to }.distinct()
        else arr(offering?.edges).filter { it.from == selected.lane || it.mode == "ABORT" }.map { str(it.to) }.distinct()
        if (target !in targets) target = ""
        targetSelect = select("Requested case target", target, targets.map { it to it }) { target = it }
        facets.text("Targets are authored edges, not permission to proceed. The server checks the current head, JOIN inputs, predicates and LOOP limits. Read the current head before every new transition. A stale head is refused, never refreshed and retried automatically.")
        transitionButton = action("Transition case", active && target.isNotBlank() && retry == null) { admit("transition") }
        if (selected != null && selected.programCid != sourceCid)
            facets.text("The named selection differs from this case's frozen version. Load the frozen case program, then read the current case again. No newer definition or canvas is substituted.")
        retry?.let { request ->
            val saved = JSON.parse<dynamic>(request)
            facets.text("Saved retry: " + str(saved.program ?: saved.name) + " / " + str(saved.case.caseId) + " / " +
                str(saved.case.intent) + " / " + str(saved.case.requestedTarget ?: "initialize") +
                ". It belongs to this identity regardless of the current selection. Read case preserves it; new commands cannot replace it.")
            action("Retry identical case command") { send(request, replay = true) }
            action("Dismiss saved retry (does not cancel)") {
                retry = null; head = null; target = ""
                message = "Saved retry dismissed locally; no server work cancelled. Read case before a new transition."
                repaint()
            }
        }
        facets.text(message)
        lastRequest?.let { request ->
            val detail = document.createElement("details").asDynamic()
            val summary = document.createElement("summary").asDynamic(); summary.textContent = "Last submitted case command"
            detail.appendChild(summary); body.appendChild(detail)
            facets.pre(executionPreview(JSON.parse<dynamic>(request)), detail)
        }
        val result = receipt
        if (result != null) {
            facets.text("Recorded run: " + str(result.runId) + " · " + str(result.status) + "\nCase: " + str(result.case?.caseId) +
                " · lane " + str(result.case?.lane) + " · phase " + str(result.case?.phase) +
                " · lifecycle " + str(result.case?.lifecycle) + " · revision " + str(result.case?.revision))
            facets.pre(executionPreview(result.case))
            if (result.returns != null) facets.pre(executionPreview(result.returns))
            fun content(label: String, cid: dynamic, syntax: Syntax = Syntax.JSON) {
                if (typeOf(cid) == "string" && Regex("sha256:[0-9a-f]{64}").matches(str(cid)))
                    action(label) { facets.inspectContent(str(cid), syntax = syntax) }
            }
            content("Case run receipt in Confix", result.receiptCid)
            content("Case snapshot in Confix", result.case?.snapshotCid, Syntax.CBOR)
            content("Case state in Confix", result.case?.stateCid, Syntax.CBOR)
            content("Case stage evidence in Confix", result.case?.evidenceCid, Syntax.CBOR)
            content("Case frozen program in Confix", result.case?.programCid)
        }
        facets.text("Closing this drawer does not cancel an admitted command. Exact retries can return historical results; only Read case supplies a head for a new transition. Request text and retry state are page-local, not recovery storage.")
    }

    /** Load only observed immutable content; this supplies no new case-head authority. */
    private fun loadFrozen() {
        if (panels.page.closing) return
        if (busy || panels.programRunning || panels.programAdmission != null) {
            message = "Construction load not admitted while awaiting an execution response"; repaint(); return
        }
        val observed = observedCase
        val selectedCase = caseId
        val selectedProgram = program
        val cid: String
        val name: String
        try {
            require(observed != null && observed.caseId == selectedCase) { "Read this case before loading its construction" }
            cid = str(observed.programCid)
            require(Regex("sha256:[0-9a-f]{64}").matches(cid)) { "The observed case has no frozen program CID" }
            val group = str(observed.run?.workgroup)
            val prefix = "lcnc/program/"
            val suffix = "@$cid"
            require(group.startsWith(prefix) && group.endsWith(suffix)) { "The observed case has no retained program label" }
            name = group.substring(prefix.length, group.length - suffix.length)
            require(name.isNotBlank()) { "The retained program label is empty" }
        } catch (failure: dynamic) { message = "Construction not loaded: " + failureText(failure); repaint(); return }
        busy = true; retained = null; head = null; target = ""
        message = "Loading the frozen construction · $cid · no case command sent"
        repaint()
        val completion = panels.page.submit {
            try {
                if (panels.page.closing) return@submit
                val response = fetchJs("/api/lcnc/content?cid=" + encodeURIComponent(cid), objOf { it.method = "GET" })
                val text = PatchRead.text(response)
                require(num(response.status).toInt() == 200) { "Frozen construction unavailable (HTTP " + str(response.status) + ")" }
                require(ContentId.of(text.encodeToByteArray()).value == cid) { "Frozen construction digest mismatch" }
                val value = LcncProgramConfix.fromJson(name, text)
                LcncCaseProgram.validate(value)
                if (!panels.page.closing && caseId == selectedCase && program == selectedProgram) {
                    retained = CaseProgram(selectedCase, cid, text, value)
                    message = "Frozen construction loaded · $cid. Read case explicitly before a new transition."
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: dynamic) { message = "Construction not loaded: " + failureText(failure) + ". No case command sent." }
            finally { busy = false; repaint() }
        }
        if (completion == null) { busy = false; message = "Page queue is full or closing; construction load not sent"; repaint() }
        else completion.invokeOnCompletion { if (busy) { busy = false; repaint() } }
    }

    private fun admit(intent: String) {
        try {
            require(intent == "read" || retry == null) { "Resolve or explicitly dismiss the saved retry before a new command" }
            require(caseId.isNotBlank()) { "Choose a nonblank case ID" }
            val frozen = retained?.takeIf { it.caseId == caseId }
            require(intent != "initialize" || frozen == null) { "Choose a named program to initialize a new case" }
            val offering = arr(catalog?.cases).singleOrNull { it.name == program }
            require(frozen != null || offering != null) { "Choose a case program from the current catalog" }
            val cid = frozen?.cid ?: str(offering?.programCid)
            val request = objOf {
                if (frozen != null) { it.name = frozen.value.name; it.document = JSON.parse<dynamic>(frozen.document) }
                else it.program = program
                it.expectProgramCid = cid
                it.case = objOf { command ->
                    command.intent = intent; command.caseId = caseId
                    if (intent != "read") command.idempotencyKey = str(js("crypto.randomUUID()"))
                    if (intent == "transition") {
                        val selected = head ?: error("Read the current case head before transitioning")
                        require(selected.caseId == caseId && selected.programCid == cid && selected.lifecycle == "active") { "Case selection or frozen program changed; read again" }
                        val revision = num(selected.revision)
                        require(typeOf(selected.revision) == "number" && revision.isFinite() && revision >= 0 &&
                            revision <= 9007199254740991.0 && revision % 1.0 == 0.0) { "Case revision cannot be represented exactly" }
                        require(target.isNotBlank()) { "Choose an explicit target" }
                        command.expectedRevision = revision; command.priorStateCid = selected.stateCid; command.requestedTarget = target
                    }
                }
                if (intent == "initialize") {
                    require(brief.isNotBlank()) { "Initialization requires a nonblank brief" }
                    it.inputs = objOf { input -> input.brief = brief }
                }
            }
            send(JSON.stringify(request), replay = false)
        } catch (failure: dynamic) { message = "Case command not admitted: " + failureText(failure); repaint() }
    }

    /** Reserve synchronously, then enqueue captured bytes in the existing common page owner. */
    private fun send(request: String, replay: Boolean) {
        if (panels.page.closing) return
        if (busy || panels.programRunning || panels.programAdmission != null || truthy(panels.graph.controls?.inspectionOnly)) {
            message = "Case command not admitted while execution is busy or this page is inspection-only; no replay queued."
            repaint(); return
        }
        busy = true; head = null; target = ""
        val selected = JSON.parse<dynamic>(request)
        message = "Case command admitted · waiting for its durable response…"
        repaint()
        val completion = panels.page.submit {
            try {
                if (panels.page.closing) { message = "Page closed before dispatch; case command not sent"; return@submit }
                panels.stopSources(); panels.pulseReceipt = null; panels.pulseProgram = null
                lastRequest = request
                val response = fetchJs("/api/lcnc/run", jsonInit("POST", request))
                val result = JSON.parse<dynamic>(PatchRead.text(response))
                val valid = typeOf(result?.receiptCid) == "string" && typeOf(result.runId) == "string" &&
                    result.programCid == selected.expectProgramCid && result.case?.caseId == selected.case.caseId
                receipt = if (valid) result else null
                if (valid) {
                    panels.lastProgramRun = result; panels.facets.selectedReceipt = result
                    if (typeOf(result.case?.programCid) == "string" &&
                        Regex("sha256:[0-9a-f]{64}").matches(str(result.case.programCid)) &&
                        typeOf(result.case.run?.workgroup) == "string") observedCase = result.case
                }
                val completed = valid && response.status == 200 && result.ok == true && result.status == "completed"
                head = if (completed && selected.case.intent == "read" && !replay) result.case else null
                if (selected.case.intent != "read")
                    retry = if (!completed || result.case?.phase == "terminal-pending") request else null
                message = "HTTP " + str(response.status) + " · " + str(result.status ?: result.error ?: "unrecognized response") +
                    (if (result.error != null) " · " + str(result.error) else "") +
                    if (head != null) " · current case head observed; no transition sent."
                    else " · no current head authorized for a new transition. Read case explicitly; no automatic retry."
                if (replay) message += " Exact retry reports its original result, not necessarily the latest case head."
            } catch (failure: CancellationException) {
                if (selected.case.intent != "read") retry = request
                message = "Case response interrupted; outcome unknown. No automatic retry."; throw failure
            } catch (failure: dynamic) {
                if (selected.case.intent != "read") retry = request
                message = "Case outcome unknown: " + failureText(failure) + ". Read case or retry the identical command explicitly; no automatic retry."
            }
            finally { busy = false; repaint() }
        }
        if (completion == null) { busy = false; message = "Page queue is full or closing; case command not sent"; repaint() }
        else completion.invokeOnCompletion { if (busy) { busy = false; repaint() } }
    }
}
