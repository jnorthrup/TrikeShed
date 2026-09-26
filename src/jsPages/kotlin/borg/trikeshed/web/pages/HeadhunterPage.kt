package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.xhr.FormData
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Date

/** A request failure that keeps the service's body so a run receipt in it can still be shown. */
private class ApiError(message: String, val response: dynamic = null) : Throwable(message)

/** The job agent (headhunter.js): records, evidence, sources, application preparation and review. */
object HeadhunterPage {
    private val schema = HeadhunterSchema.forms
    private val labels = HeadhunterSchema.labels
    private val categories = HeadhunterSchema.categories

    private var records: MutableList<dynamic> = ArrayList()
    private var runs: List<dynamic> = emptyList()
    private var model: dynamic = js("({configured:false})")
    private var extraction: dynamic = obj()
    private var program: dynamic = null
    private var applicationId = ""
    private class Edit(val kind: String, val value: dynamic, val defaults: dynamic, val historyCid: String?)
    private var edit: Edit? = null
    private var artifact: dynamic = null
    private var selectionBase: dynamic = null
    private var selectionDirty = false
    private var recordDirty = false
    private var artifactDirty = false
    private var preparing = false
    private var collisionTicket = 0
    private var loaded = false

    private fun el(id: String): HTMLElement = byId(id)
    private fun form(id: String): HTMLFormElement = byId(id) as HTMLFormElement
    private fun elements(f: HTMLFormElement): dynamic = f.asDynamic().elements
    private fun encodeUri(s: String): String = js("encodeURIComponent")(s) as String
    private fun dialog(id: String): HTMLDialogElement = byId(id) as HTMLDialogElement

    private fun node(tag: String, cls: String? = null, text: Any? = null): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        if (!cls.isNullOrEmpty()) element.className = cls
        if (text != null) element.textContent = str(text)
        return element
    }

    private fun button(text: String, cls: String = "quiet", action: (Event) -> Unit): HTMLButtonElement {
        val b = node("button", cls, text) as HTMLButtonElement
        b.type = "button"
        b.addEventListener("click", action)
        return b
    }

    private fun recordsOf(kind: String): List<dynamic> = records.filter { it.kind == kind }
    private fun record(id: dynamic): dynamic = if (id == null) null else records.firstOrNull { it.id == id }
    private fun f(r: dynamic): dynamic = if (truthy(r.fields)) r.fields else obj()

    private fun title(r: dynamic): String {
        if (!truthy(r)) return "Unavailable record"
        val fl = f(r)
        if (truthy(fl.title) || truthy(fl.name)) return str(if (truthy(fl.title)) fl.title else fl.name)
        if (r.kind == "application") return title(record(fl.listingId)) + " · " + (if (truthy(fl.status)) str(fl.status) else "research")
        if (r.kind == "representation") return listOf(title(record(if (truthy(fl.employerId)) fl.employerId else fl.listingId)), if (truthy(fl.status)) str(fl.status) else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        return str(if (truthy(fl.type)) fl.type else r.id)
    }

    private fun date(value: dynamic): String {
        val d = Date(js("Number")(value) as Double)
        return if (d.getTime().isFinite()) d.toLocaleString() else "Date unavailable"
    }

    private fun badge(text: dynamic, cls: String = ""): HTMLElement = node("span", "badge $cls", text)

    private fun notice(message: String?, error: Boolean = false) {
        val n = el("notice")
        n.hidden = message.isNullOrEmpty()
        n.textContent = message ?: ""
        n.classList.toggle("error", error)
    }

    private fun errorText(e: Throwable?): String = e?.message?.takeIf { it.isNotEmpty() } ?: "The request could not be completed."

    private suspend fun api(path: String, body: dynamic = undefined, secret: Boolean = false): dynamic {
        val init = if (body == undefined) requestInit(cache = "no-store") else requestInit("POST", jsonHeaders(), JSON.stringify(body))
        val response = fetchResponse("/api/headhunter$path", init)
        val value: dynamic
        try {
            value = response.jsonValue()
        } catch (_: Throwable) {
            throw ApiError(if (secret) "Source access could not be saved." else "The job agent returned an unreadable response. Refresh or check the service.")
        }
        if (!response.ok || (value != null && value.ok == false)) {
            val message = if (response.status.toInt() == 409) "This record changed after you opened it. Your edits are still here. Review the latest version before saving again."
            else if (secret) "Source access could not be saved. Check the source and origin."
            else if (value != null && isString(value.error)) str(value.error)
            else if (value != null && isString(value.message)) str(value.message)
            else "Request failed (" + response.status + ")."
            throw ApiError(message, if (secret) null else value)
        }
        return value
    }

    private fun upsert(value: dynamic): dynamic {
        val r: dynamic = if (value != null && truthy(value.record)) value.record else value
        if (r == null || !truthy(r.id) || !truthy(r.kind) || !truthy(r.fields) || !truthy(r.cid)) throw ApiError("The service did not return a saved record.")
        val index = records.indexOfFirst { it.id == r.id }
        if (index < 0) records.add(0, r) else records[index] = r
        return r
    }

    private suspend fun save(kind: String, fields: dynamic, previous: dynamic = null): dynamic {
        val b: dynamic = obj()
        b.kind = kind
        if (previous != null) { b.id = previous.id; b.baseCid = previous.cid }
        b.fields = fields
        return upsert(api("/record", b))
    }

    private fun copy(o: dynamic): dynamic = js("Object").assign(obj(), o)

    private suspend fun <T> busy(target: HTMLElement, action: suspend () -> T): T {
        val controls: List<dynamic> = if (target.matches("button")) listOf(target.asDynamic())
        else { val l = target.querySelectorAll("button,input,select,textarea"); (0 until l.length).map { l.item(it).asDynamic() } }
        val previous = controls.map { it.disabled as Boolean }
        target.setAttribute("aria-busy", "true")
        try {
            controls.forEach { it.disabled = true }
            return action()
        } finally {
            controls.forEachIndexed { i, c -> c.disabled = previous[i] }
            target.removeAttribute("aria-busy")
        }
    }

    private fun option(select: HTMLSelectElement, value: String, text: String) {
        val o = node("option", "", text)
        o.asDynamic().value = value
        select.append(o)
    }

    private fun options(select: HTMLSelectElement, kind: String, blank: String, selected: String = select.value) {
        select.asDynamic().replaceChildren()
        option(select, "", blank)
        recordsOf(kind).forEach { option(select, str(it.id), title(it)) }
        if (selected.isNotEmpty() && recordsOf(kind).none { it.id == selected }) option(select, selected, "Unavailable record · $selected")
        select.value = selected
    }

    private fun go(section: String) { window.location.hash = section }

    private fun showSection() {
        val section = HeadhunterSchema.section(window.location.hash)
        val sections = document.querySelectorAll(".hh-section")
        for (i in 0 until sections.length) (sections.item(i) as HTMLElement).let { it.hidden = it.id != section }
        val links = document.querySelectorAll("[data-section]")
        for (i in 0 until links.length) (links.item(i) as Element).let {
            if (it.getAttribute("data-section") == section) it.setAttribute("aria-current", "page") else it.removeAttribute("aria-current")
        }
    }

    private fun empty(parent: HTMLElement, heading: String, detail: String, action: HTMLElement? = null) {
        val e = node("div", "empty")
        e.append(node("h3", "", heading), node("p", "small", detail))
        if (action != null) { val row = node("div", "actions"); row.append(action); e.append(row) }
        parent.append(e)
    }

    private fun recordCard(r: dynamic): HTMLElement {
        val card = node("article", "record-card")
        val fl = f(r)
        card.append(badge(if (truthy(fl.category)) fl.category else if (truthy(fl.status)) fl.status else r.kind), node("h3", "", title(r)))
        val excerpt: dynamic = listOf(fl.text, fl.notes, fl.url, fl.email).firstOrNull { truthy(it) } ?: ""
        if (truthy(excerpt)) card.append(node("p", "record-preview", excerpt))
        if (r.kind == "source" && truthy(fl.lastCapture)) {
            val ex: dynamic = fl.lastCapture
            card.append(badge(if (truthy(ex.status)) ex.status else "captured", if (ex.status == "ready") "" else "warning"))
            if (truthy(ex.error)) card.append(node("p", "small error-text", ex.error))
        }
        val related = HeadhunterSchema.related.filter { truthy(fl[it]) }
        if (related.isNotEmpty()) card.append(node("p", "record-meta", related.joinToString(" · ") { title(record(fl[it])) }))
        card.append(node("p", "record-meta", "Updated " + date(r.updatedAtMs)))
        val actions = node("div", "actions")
        val kind = str(r.kind)
        actions.append(button("Edit") { openRecord(kind, r) }, button("History") { openRecord(kind, r, true) })
        if (kind == "listing") actions.append(button("Create application", "primary") { val d: dynamic = obj(); d.listingId = r.id; openRecord("application", null, false, d) })
        if (kind == "application") actions.append(button("Prepare") { selectApplication(str(r.id)) })
        if (kind == "source") actions.append(button("Use saved source") {
            (el("capture-source") as HTMLSelectElement).value = str(r.id)
            elements(form("capture-form")).inputMode.value = "saved"
            if (fl.captureKind == "evidence" || fl.captureKind == "listing") (el("capture-kind") as HTMLSelectElement).value = str(fl.captureKind)
            if (truthy(fl.category) && str(fl.category) in categories) (el("capture-category") as HTMLSelectElement).value = str(fl.category)
            captureMode(); go("sources"); form("capture-form").asDynamic().scrollIntoView(js("({block:'start'})"))
        })
        card.append(actions)
        return card
    }

    private fun renderRecords() {
        val counts = document.querySelectorAll("[data-count]")
        for (i in 0 until counts.length) (counts.item(i) as HTMLElement).let { it.textContent = recordsOf(it.getAttribute("data-count") ?: "").size.toString() }
        val evidence = el("evidence-records"); evidence.asDynamic().replaceChildren()
        recordsOf("evidence").forEach { evidence.append(recordCard(it)) }
        if (evidence.children.length == 0) empty(evidence, "Your experience belongs here", "Import a document or write a note. Your saved evidence will be available for selection in every application.",
            button("Import evidence", "primary") { capture("evidence") })
        val kind = (el("record-kind") as HTMLSelectElement).value
        val search = (el("record-search") as HTMLInputElement).value.lowercase()
        el("new-record").textContent = "New " + labels[kind]
        val list = el("record-list"); list.asDynamic().replaceChildren()
        recordsOf(kind).filter { (JSON.stringify(it.fields) as String).lowercase().contains(search) }.forEach { list.append(recordCard(it)) }
        if (list.children.length == 0) empty(list, if (search.isNotEmpty()) "No matching records" else "No " + labels[kind] + " records yet",
            if (search.isNotEmpty()) "Try a different name or clear the search." else "Add the details you have. Link related records as you learn more.")
        val sources = el("source-records"); sources.asDynamic().replaceChildren(); recordsOf("source").forEach { sources.append(recordCard(it)) }
        if (sources.children.length == 0) sources.append(node("p", "small muted", "No saved sources. Add one to keep its origin and access configuration together."))
        options(el("capture-source") as HTMLSelectElement, "source", "No saved source")
        options(el("credential-source") as HTMLSelectElement, "source", "Choose a source")
        options(el("capture-contact") as HTMLSelectElement, "contact", "No contact")
        options(el("application-select") as HTMLSelectElement, "application", "Choose an application", applicationId)
        el("welcome").hidden = recordsOf("application").isNotEmpty()
        el("extraction-status").textContent = if (extraction.documents == true) "Document extraction is available for PDF, DOCX, HTML, and other supported documents."
        else if (extraction.documents == false) "Plain text import is available. Document extraction is unavailable; failed parsing retains the source for a later attempt."
        else "Document extraction availability has not been reported by the service."
    }

    private fun applicationInputs(): dynamic {
        val application = record(applicationId)
        if (!truthy(application)) return null
        val fl = f(application)
        val o: dynamic = obj()
        o.applicationId = application.id
        o.evidenceIds = if (js("Array").isArray(fl.evidenceIds) as Boolean) fl.evidenceIds else js("[]")
        o.instructions = if (truthy(fl.instructions)) fl.instructions else ""
        return o
    }

    private fun selectedInputs(): dynamic {
        val runId = (el("run-select") as HTMLSelectElement).value
        if (runId.isEmpty()) return applicationInputs()
        val inputs: dynamic = runs.firstOrNull { it.runId == runId }?.inputs
        return if (inputs != null && truthy(inputs.request)) inputs.request else if (truthy(inputs)) inputs else null
    }

    private fun renderModel() {
        val configured = truthy(model.configured)
        el("model-status").textContent = (if (configured) "Model configured. " else "Model preparation unavailable. ") +
            (if (truthy(model.detail)) str(model.detail) else if (configured) "Availability is checked when invoked." else "Check credential presence in KeyMux and model routing in ModelMux.")
        el("model-status").className = "small " + (if (configured) "success-text" else "muted")
        (el("prepare-model") as HTMLButtonElement).disabled = !configured || preparing || applicationId.isEmpty()
        (el("prepare-assemble") as HTMLButtonElement).disabled = preparing || applicationId.isEmpty()
        (el("rerun-model") as HTMLButtonElement).disabled = !configured || preparing || !truthy(selectedInputs())
        (el("rerun-assemble") as HTMLButtonElement).disabled = preparing || !truthy(selectedInputs())
        val checks = el("evidence-selection").querySelectorAll("input")
        val controls = listOf(el("application-select"), el("prepare-instructions"), el("save-selection")) + (0 until checks.length).map { checks.item(it) as HTMLElement }
        controls.forEach { it.asDynamic().disabled = preparing }
    }

    private fun renderApplication(resetChoices: Boolean = false) {
        val app = record(applicationId)
        el("application-workspace").hidden = !truthy(app)
        if (!truthy(app)) { renderProgram(); renderModel(); return }
        val fl = f(app)
        val listing = record(fl.listingId)
        val summary = el("application-summary"); summary.asDynamic().replaceChildren()
        summary.append(badge(if (truthy(fl.status)) fl.status else "research"), node("h3", "", title(listing)))
        val details = node("dl")
        val lf: dynamic = if (truthy(listing)) f(listing) else null
        val employerTitle = title(record(lf?.employerId))
        val rows = listOf(
            "Employer" to (if (employerTitle == "Unavailable record") lf?.employer else employerTitle),
            "Requisition" to lf?.requisition,
            "Contact" to (if (truthy(fl.contactId)) title(record(fl.contactId)) else null),
        )
        for ((label, value) in rows) if (truthy(value)) details.append(node("dt", "", label), node("dd", "", value))
        summary.append(details)
        if (truthy(listing)) {
            val text = node("details")
            text.append(node("summary", "", "Read listing"), node("p", "artifact-preview", if (truthy(lf.text)) lf.text else "No listing text saved."))
            summary.append(text, button("Edit listing") { openRecord("listing", listing) })
        } else summary.append(node("p", "error-text small", "This application needs an available listing. Edit the application to choose one."))
        if (truthy(fl.notes)) summary.append(node("p", "record-preview", fl.notes))
        if (resetChoices || !selectionDirty) {
            selectionBase = app
            val selection = el("evidence-selection"); selection.asDynamic().replaceChildren()
            val ids = LinkedHashSet<String>()
            if (js("Array").isArray(fl.evidenceIds) as Boolean) jsArray(fl.evidenceIds).forEach { ids.add(str(it)) }
            recordsOf("evidence").forEach { e ->
                val row = node("div", "selection"); val label = node("label"); val check = node("input") as HTMLInputElement
                check.type = "checkbox"; check.value = str(e.id); check.checked = str(e.id) in ids
                check.addEventListener("change", { selectionDirty = true })
                val ef = f(e)
                val words = node("span")
                words.append(node("strong", "", title(e)), node("small", "", (if (truthy(ef.category)) str(ef.category) else "evidence") + " · " + (if (truthy(ef.text)) str(ef.text) else "").take(115)))
                label.append(check, words); row.append(label, button("Read") { openRecord("evidence", e) }); selection.append(row)
            }
            val missing = ids.filter { !truthy(record(it)) }
            if (missing.isNotEmpty()) selection.append(node("p", "error-text small", "Unavailable evidence: " + missing.joinToString(", ") + ". Save a corrected selection before preparation."))
            if (recordsOf("evidence").isEmpty()) selection.append(node("p", "small muted", "Add your evidence before preparing materials."))
            (el("prepare-instructions") as HTMLTextAreaElement).value = if (truthy(fl.instructions)) str(fl.instructions) else ""
            selectionDirty = false
        }
        renderArtifacts(); renderActions(); renderProgram(); renderModel()
    }

    private fun selectApplication(id: String) {
        if (selectionDirty && id != applicationId && !window.confirm("Discard unsaved preparation choices?")) { (el("application-select") as HTMLSelectElement).value = applicationId; return }
        applicationId = id; selectionDirty = false; (el("application-select") as HTMLSelectElement).value = id
        val url = URL(window.location.href)
        if (id.isNotEmpty()) url.searchParams.set("application", id) else url.searchParams.delete("application")
        window.history.replaceState(null, "", url.href)
        el("run-summary").hidden = true; renderApplication(true); launchPage { loadCollisions() }; go("applications")
    }

    private suspend fun saveSelection(): dynamic {
        val app = selectionBase
        if (!truthy(app) || app.id != applicationId) throw ApiError("Choose an application first.")
        val fields = copy(app.fields)
        val checked = el("evidence-selection").querySelectorAll("input:checked")
        fields.evidenceIds = (0 until checked.length).map { (checked.item(it) as HTMLInputElement).value }.toTypedArray()
        fields.instructions = (el("prepare-instructions") as HTMLTextAreaElement).value
        if (JSON.stringify(fields) != JSON.stringify(app.fields)) save("application", fields, app)
        selectionBase = record(applicationId); selectionDirty = false; renderProgram()
        return applicationInputs()
    }

    private suspend fun loadCollisions() {
        val id = applicationId
        val ticket = ++collisionTicket
        val host = el("collisions")
        host.asDynamic().replaceChildren(node("p", "small muted", if (id.isNotEmpty()) "Checking saved records…" else "Choose an application."))
        if (id.isEmpty()) return
        try {
            val result = api("/collisions?applicationId=" + encodeUri(id))
            if (ticket == collisionTicket) renderCollisions(if (truthy(result.collisions)) result.collisions else js("[]"))
        } catch (e: Throwable) {
            if (ticket == collisionTicket) host.asDynamic().replaceChildren(node("p", "error-text small", errorText(e)))
        }
    }

    private fun renderCollisions(collisions: dynamic) {
        val host = el("collisions"); host.asDynamic().replaceChildren()
        val list = jsArray(collisions)
        if (list.isEmpty()) { host.append(node("p", "small", "No matching conflicts were returned for this application.")); return }
        for (match in list) {
            val c = node("article", "collision")
            c.append(node("strong", "", if (truthy(match.reason)) match.reason else "Matching " + (if (truthy(match.kind)) str(match.kind) else "record")))
            if (truthy(match.record)) c.append(node("p", "", title(match.record)))
            for (ev in jsArray(match.evidence)) c.append(node("p", "", (if (truthy(ev.field)) str(ev.field) else "Match") + ": " + strOrEmpty(ev.value)))
            val found = record(match.id)
            val target: dynamic = if (truthy(found)) found else match.record
            if (truthy(target) && schema.containsKey(str(target.kind))) {
                val review = reviewFor(target)
                if (truthy(review)) c.append(badge(review.fields.decision))
                c.append(button("Review record") { openRecord(str(target.kind), target) })
            }
            host.append(c)
        }
    }

    private fun renderActions() {
        val host = el("application-actions"); host.asDynamic().replaceChildren()
        val actions = recordsOf("action").filter { it.fields.applicationId == applicationId }
        for (r in actions) {
            val fl = f(r)
            val item = node("div", "activity-item")
            item.append(badge(if (truthy(fl.status)) fl.status else "recorded"), node("strong", "", if (truthy(fl.type)) fl.type else title(r)),
                node("p", "record-preview", if (truthy(fl.notes)) fl.notes else ""), node("p", "record-meta", date(r.updatedAtMs)), button("View record") { openRecord("action", r) })
            host.append(item)
        }
        if (actions.isEmpty()) host.append(node("p", "small muted", "No actions recorded for this application."))
    }

    private fun reviewFor(r: dynamic): dynamic {
        var latest: dynamic = null
        for (review in recordsOf("review")) {
            if (review.fields.subjectId == r.id && review.fields.subjectCid == r.cid &&
                (latest == null || (js("Number")(review.updatedAtMs) as Double) > (js("Number")(latest.updatedAtMs) as Double))) latest = review
        }
        return latest
    }

    private fun provenance(host: HTMLElement, art: dynamic) {
        val list = node("ul", "sources-list")
        for (source in jsArray(art.fields.sources)) {
            val item = node("li")
            val sourceRecord = record(source.id)
            val label = if (truthy(sourceRecord)) title(sourceRecord) else if (truthy(source.id)) str(source.id) else "Unavailable evidence"
            if (truthy(sourceRecord) && schema.containsKey(str(sourceRecord.kind))) item.append(button(label) { openRecord(str(sourceRecord.kind), sourceRecord, true, obj(), if (source.cid == null) null else str(source.cid)) })
            else item.append(node("span", "small muted", label))
            item.append(node("span", "record-meta", " · " + (if (truthy(source.cid)) str(source.cid) else "version unavailable") +
                (if (truthy(sourceRecord) && source.cid != sourceRecord.cid) " · newer version available" else "")))
            list.append(item)
        }
        host.append(node("h4", "", "Evidence references"))
        if (list.children.length > 0) host.append(list) else host.append(node("p", "small error-text", "No evidence references are attached. Review this before use."))
    }

    private fun renderArtifacts() {
        val host = el("artifacts"); host.asDynamic().replaceChildren()
        val artifacts = recordsOf("artifact").filter { it.fields.applicationId == applicationId }
            .sortedByDescending { js("Number")(it.updatedAtMs) as Double }
        if (artifacts.isEmpty()) { empty(host, "No prepared artifacts yet", "Select evidence and run preparation. Saved materials and their sources will appear here."); return }
        for (a in artifacts) {
            val fl = f(a)
            val card = node("article", "artifact-card")
            val review = reviewFor(a)
            card.append(badge(if (truthy(fl.type)) fl.type else "artifact"), badge(if (fl.generation == "model") "Model draft" else "Evidence assembly"),
                badge(if (truthy(review)) review.fields.decision else "Needs review", if (truthy(review) && review.fields.decision == "approved") "" else "warning"), node("h3", "", title(a)))
            card.append(node("pre", "artifact-preview", if (truthy(fl.content)) fl.content else "No content saved.")); provenance(card, a)
            val actions = node("div", "actions"); actions.append(button("Edit & review", "primary") { openArtifact(a) })
            for (format in listOf("markdown", "html", "text")) {
                val link = node("a", "", if (format == "markdown") "Markdown" else if (format == "html") "HTML" else "Text") as HTMLAnchorElement
                link.href = "/api/headhunter/artifact?id=" + encodeUri(str(a.id)) + "&format=" + format
                link.setAttribute("download", "")
                actions.append(link)
            }
            card.append(actions); host.append(card)
        }
    }

    private fun renderProgram() {
        val p = program
        (el("open-program") as HTMLButtonElement).disabled = !(p != null && truthy(p.name))
        el("program-name").textContent = if (p != null && truthy(p.name)) str(p.name) else "Composition unavailable"
        el("program-document").textContent = if (p != null) (if (isString(p.document)) str(p.document) else strOrEmpty(JSON.stringify(p.document, null, 2))) else "The service did not return a program."
        val nodes = el("program-nodes"); nodes.asDynamic().replaceChildren()
        var doc: dynamic = p?.document
        if (isString(doc)) { doc = try { JSON.parse<dynamic>(doc as String) } catch (_: Throwable) { null } }
        for (n in jsArray(doc?.nodes)) {
            val e = node("div", "program-node")
            val label: dynamic = listOf(n.label, n.title, n.type, n.id).firstOrNull { truthy(it) }
            e.append(node("strong", "", label), node("span", "muted", listOf(n.id, n.type).filter { truthy(it) }.joinToString(" · ") { str(it) }))
            nodes.append(e)
        }
        val runSelect = el("run-select") as HTMLSelectElement
        val selected = runSelect.value
        runSelect.asDynamic().replaceChildren(); option(runSelect, "", "Current application choices")
        runs.forEach { option(runSelect, str(it.runId), date(it.startedAtMs) + " · " + (if (truthy(it.status)) str(it.status) else "recorded")) }
        runSelect.value = if (runs.any { it.runId == selected }) selected else ""
        val inputs = selectedInputs()
        el("program-inputs").textContent = if (truthy(inputs)) JSON.stringify(inputs, null, 2) else "No application selected."
        el("program-selection").textContent = if (truthy(inputs)) "Saved application: " + str(inputs.applicationId) +
            (if (runSelect.value.isNotEmpty()) " · This replay uses the evidence versions pinned by that run." else " · Current saved choices use current evidence versions.") +
            (if (selectionDirty && runSelect.value.isEmpty()) " Unsaved choices in Applications are not shown here." else "")
        else "Select an application in the Applications section."
        (el("rerun-assemble") as HTMLButtonElement).disabled = !truthy(inputs) || preparing
        (el("rerun-model") as HTMLButtonElement).disabled = !truthy(inputs) || !truthy(model.configured) || preparing
    }

    private fun runSummary(host: HTMLElement, run: dynamic) {
        host.asDynamic().replaceChildren(); host.hidden = false
        host.append(badge(if (truthy(run.status)) run.status else "recorded"), node("p", "", "Run " + (if (truthy(run.runId)) str(run.runId) else "receipt unavailable")))
        if (truthy(run.error)) host.append(node("p", "error-text", if (isString(run.error)) run.error else JSON.stringify(run.error)))
        if (truthy(run.receiptCid)) host.append(node("p", "record-meta", "Receipt " + str(run.receiptCid)))
        val details = node("details"); details.append(node("summary", "", "Run receipt"), node("pre", "code", JSON.stringify(run, null, 2))); host.append(details)
    }

    private suspend fun prepare(mode: String, rerun: Boolean = false) {
        if (preparing) return
        notice(""); preparing = true; renderModel()
        try {
            val inputs = if (rerun) selectedInputs() else saveSelection()
            if (inputs == null || !truthy(inputs.applicationId)) throw ApiError("Choose a saved application first.")
            if (!(js("Array").isArray(inputs.evidenceIds) as Boolean) || (inputs.evidenceIds.length as Int) == 0) throw ApiError("Select at least one evidence record before preparation.")
            notice(if (mode == "model") "Preparing drafts with the configured model…" else "Assembling selected evidence…")
            val b: dynamic = obj()
            b.applicationId = inputs.applicationId; b.evidenceIds = inputs.evidenceIds; b.instructions = if (truthy(inputs.instructions)) inputs.instructions else ""
            if (rerun && truthy(inputs.versions)) b.versions = inputs.versions
            b.mode = mode
            val result = api("/prepare", b)
            if (!truthy(result.run)) throw ApiError("Preparation returned no run receipt.")
            refresh(false)
            if (applicationId != str(inputs.applicationId)) { applicationId = str(inputs.applicationId); selectionDirty = false }
            renderRecords(); renderApplication(true); renderCollisions(if (truthy(result.collisions)) result.collisions else js("[]"))
            runSummary(el("run-summary"), result.run); runSummary(el("program-run"), result.run)
            val status = strOrEmpty(result.run.status).lowercase()
            if (status in listOf("failed", "refused", "cancelled", "error") || result.run.ok == false) notice("Preparation " + status.ifEmpty { "failed" } + ". Read the run receipt for the reason.", true)
            else notice("Preparation returned a run receipt. Review the saved artifacts and their evidence before use.")
        } catch (e: Throwable) {
            val resp: dynamic = (e as? ApiError)?.response
            if (resp != null && truthy(resp.run)) {
                runSummary(el("run-summary"), resp.run); runSummary(el("program-run"), resp.run)
                try { refresh() } catch (_: Throwable) { /* The returned failure receipt remains available even if refresh fails. */ }
            }
            notice(errorText(e), true)
        } finally {
            preparing = false; renderModel()
        }
    }

    private suspend fun refresh(render: Boolean = true) {
        val data = api("")
        if (!(js("Array").isArray(data.records) as Boolean)) throw ApiError("The job agent did not return its record list.")
        records = jsArray(data.records).toMutableList()
        runs = jsArray(data.runs).toList()
        model = if (truthy(data.model)) data.model else js("({configured:false, detail:'Model configuration is unavailable.'})")
        extraction = if (truthy(data.extraction)) data.extraction else obj()
        program = if (truthy(data.program)) data.program else null
        if (!loaded) {
            val selected = URLSearchParams(window.location.search).get("application")
            val sel = record(selected)
            applicationId = if (truthy(sel) && sel.kind == "application") selected!! else recordsOf("application").firstOrNull().unsafeCast<Any?>().let { a -> if (a == null) "" else str(a.asDynamic().id) }
            loaded = true
        }
        if (render) { renderRecords(); renderApplication(); renderProgram(); launchPage { loadCollisions() } }
    }

    private fun replacementRow(from: String = "", to: String = ""): HTMLElement {
        val row = node("div", "fields replacement-row")
        for ((key, text, value) in listOf(Triple("from", "Original text", from), Triple("to", "Replacement text", to))) {
            val label = node("label", "field"); val input = node("textarea") as HTMLTextAreaElement
            input.rows = 2; input.setAttribute("data-replacement", key); input.value = value
            label.append(node("span", "", text), input); row.append(label)
        }
        row.append(button("Remove correction") { row.remove(); recordDirty = true })
        return row
    }

    private fun openRecord(kind: String, value: dynamic, historyOpen: Boolean = false, defaults: dynamic = obj(), historyCid: String? = null) {
        val fields0 = schema[kind] ?: return
        val dlg = dialog("record-dialog")
        if (dlg.open && recordDirty && !window.confirm("Discard unsaved record edits?")) return
        edit = Edit(kind, value, defaults, historyCid); recordDirty = false
        el("record-dialog-title").textContent = (if (truthy(value)) "Edit " else "New ") + labels[kind]; el("record-message").textContent = ""
        val fields = copy(if (truthy(value) && truthy(value.fields)) value.fields else defaults)
        if (kind == "source") js("Object").assign(fields, if (truthy(fields.extraction)) fields.extraction else obj())
        if (kind == "action" && !truthy(fields.applicationId)) fields.applicationId = applicationId
        val host = el("record-fields"); host.asDynamic().replaceChildren()
        for (field in fields0) {
            if (field.type == "replacements") {
                val wrapper = node("div", "field wide"); val list = node("div", "stack"); list.id = "source-replacements"
                wrapper.append(node("h4", "", field.label), node("p", "small muted", "Replace exact text after extraction. The original material stays available."))
                val reps: dynamic = if (truthy(fields.replacements)) fields.replacements else obj()
                for (k in js("Object.keys")(reps).unsafeCast<Array<String>>()) list.append(replacementRow(k, str(reps[k])))
                wrapper.append(list, button("Add text correction") { list.append(replacementRow()); recordDirty = true }); host.append(wrapper); continue
            }
            val wrapper = node("label", "field" + (if (field.type == "textarea") " wide" else ""))
            wrapper.append(node("span", "", field.label + (if (field.required) " *" else "")))
            val current: dynamic = fields[field.name]
            val input: HTMLElement
            if (field.type == "choice" || field.type.startsWith("@")) {
                val sel = node("select") as HTMLSelectElement
                if (field.type == "choice") {
                    field.choices.forEach { option(sel, it, HeadhunterSchema.choiceLabel(it)) }
                    if (truthy(current) && str(current) !in field.choices) option(sel, str(current), str(current) + " (unavailable)")
                } else {
                    val ref = field.type.substring(1)
                    options(sel, ref, "Choose " + labels[ref], if (truthy(current)) str(current) else "")
                }
                input = sel
            } else {
                input = node(if (field.type == "textarea") "textarea" else "input")
                if (field.type != "textarea") (input as HTMLInputElement).type = field.type
                else (input as HTMLTextAreaElement).rows = if (kind == "evidence" || kind == "listing") 10 else 4
            }
            input.asDynamic().name = field.name; input.asDynamic().required = field.required
            if (current != null) input.asDynamic().value = str(current)
            wrapper.append(input); host.append(wrapper)
        }
        val history = el("record-history")
        history.hidden = !truthy(value); history.asDynamic().open = historyOpen; el("history-list").asDynamic().replaceChildren()
        val neighbors = el("record-neighbors")
        neighbors.hidden = !truthy(value); neighbors.asDynamic().open = false; el("neighbor-records").asDynamic().replaceChildren()
        el("record-review").hidden = !truthy(value) || kind !in HeadhunterSchema.reviewable
        (el("record-review-notes") as HTMLTextAreaElement).value = ""; (el("record-review-decision") as HTMLSelectElement).value = "approved"
        if (truthy(value)) { val id = str(value.id); launchPage { loadHistory(id) } }
        if (!dlg.open) dlg.showModal()
    }

    private fun editingId(): String? { val v: dynamic = edit?.value; return if (truthy(v)) str(v.id) else null }

    private suspend fun loadHistory(id: String) {
        el("history-list").asDynamic().replaceChildren(node("p", "small muted", "Loading versions…"))
        try {
            val result = api("/history?id=" + encodeUri(id))
            if (editingId() != id) return
            val host = el("history-list"); host.asDynamic().replaceChildren()
            for (r in jsArray(result.versions)) {
                val item = node("details", "history-item")
                item.asDynamic().open = r.cid == edit?.historyCid
                item.append(node("summary", "", date(r.updatedAtMs) + " · " + str(r.cid)), node("pre", "code", JSON.stringify(r.fields, null, 2)))
                host.append(item)
            }
            if (host.children.length == 0) host.append(node("p", "small muted", "No versions were returned."))
        } catch (e: Throwable) {
            if (editingId() == id) el("history-list").asDynamic().replaceChildren(node("p", "error-text small", errorText(e)))
        }
    }

    private suspend fun loadNeighbors() {
        val id = editingId() ?: return
        if (!(el("record-neighbors").asDynamic().open as Boolean)) return
        val host = el("neighbor-records"); host.asDynamic().replaceChildren(node("p", "small muted", "Finding related facts…"))
        try {
            val response = fetchResponse("/blackboard/neighbors?key=" + encodeUri("headhunter/$id"))
            if (!response.ok) throw ApiError("Related facts are unavailable (" + response.status + ").")
            val data = response.jsonValue()
            if (editingId() != id) return
            host.asDynamic().replaceChildren()
            for (neighbor in jsArray(data.neighbors)) {
                val row = node("div", "history-item")
                val related = record(str(neighbor.key).replace(Regex("^headhunter/"), ""))
                row.append(node("strong", "", if (truthy(related)) title(related) else neighbor.key))
                if (jsArray(neighbor.terms).isNotEmpty()) row.append(node("p", "small muted", "Shared text: " + (neighbor.terms.join(", ") as String)))
                if (jsArray(neighbor.concepts).isNotEmpty()) row.append(node("p", "small muted", "Shared concepts: " + (neighbor.concepts.join(", ") as String)))
                for (reference in jsArray(neighbor.references)) row.append(node("p", "record-meta", "Recorded reference: " + str(reference.from) + " → " + str(reference.to)))
                if (truthy(related) && schema.containsKey(str(related.kind))) row.append(button("Review related record") { openRecord(str(related.kind), related) })
                val link = node("a", "small", "Inspect relationships") as HTMLAnchorElement
                link.href = "/blackboard/neighbors?key=" + encodeUri(str(neighbor.key)); link.target = "_blank"; link.rel = "noopener"; row.append(link); host.append(row)
            }
            if (host.children.length == 0) host.append(node("p", "small muted", "No supported related facts were returned."))
        } catch (e: Throwable) {
            if (editingId() == id) host.asDynamic().replaceChildren(node("p", "small error-text", errorText(e)))
        }
    }

    private suspend fun saveRecord(formEl: HTMLFormElement) {
        val ed = edit ?: return
        busy(formEl) {
            try {
                val fields = copy(if (truthy(ed.value) && truthy(ed.value.fields)) ed.value.fields else ed.defaults)
                val values = FormData(formEl)
                for (field in schema.getValue(ed.kind)) {
                    if (field.type != "replacements" && !(ed.kind == "source" && (field.name == "startAfter" || field.name == "endBefore"))) {
                        val v: dynamic = values.asDynamic().get(field.name)
                        fields[field.name] = if (truthy(v)) v else ""
                    }
                }
                if (ed.kind == "source") {
                    val ex = copy(if (truthy(fields.extraction)) fields.extraction else obj())
                    for (key in listOf("startAfter", "endBefore")) {
                        val marker: dynamic = values.asDynamic().get(key)
                        if (truthy(marker)) ex[key] = marker else js("Reflect").deleteProperty(ex, key)
                    }
                    val replacements: dynamic = obj()
                    val rows = el("source-replacements").children
                    for (i in 0 until rows.length) {
                        val row = rows.item(i)!!
                        val from = (row.querySelector("[data-replacement=from]") as HTMLTextAreaElement).value
                        val to = (row.querySelector("[data-replacement=to]") as HTMLTextAreaElement).value
                        if (from.isEmpty() && to.isEmpty()) continue
                        if (from.isEmpty()) throw ApiError("A text correction needs original text to match.")
                        if (js("Object").hasOwn(replacements, from) as Boolean) throw ApiError("Each text correction must have different original text.")
                        val desc: dynamic = obj(); desc.value = to; desc.enumerable = true; desc.writable = true; desc.configurable = true
                        js("Object").defineProperty(replacements, from, desc)
                    }
                    ex.replacements = replacements; fields.extraction = ex
                }
                if (ed.kind == "representation" && !truthy(fields.employerId) && !truthy(fields.listingId)) throw ApiError("Choose an employer or listing for this representation.")
                val result = save(ed.kind, fields, if (truthy(ed.value)) ed.value else null)
                recordDirty = false; dialog("record-dialog").close()
                if (ed.kind == "application") applicationId = str(result.id)
                renderRecords(); renderApplication(); launchPage { loadCollisions() }; notice("Saved " + title(result) + ".")
            } catch (e: Throwable) {
                el("record-message").textContent = errorText(e); el("record-message").className = "small error-text"
            }
        }
    }

    private fun openArtifact(r: dynamic) {
        val dlg = dialog("artifact-dialog")
        if (dlg.open && artifactDirty && !window.confirm("Discard unsaved artifact edits?")) return
        artifact = r; artifactDirty = false; el("artifact-dialog-title").textContent = title(r)
        val fe = elements(form("artifact-form"))
        fe.title.value = if (truthy(r.fields.title)) r.fields.title else title(r)
        fe.content.value = if (truthy(r.fields.content)) r.fields.content else ""
        el("artifact-provenance").asDynamic().replaceChildren(); provenance(el("artifact-provenance"), r)
        el("artifact-message").textContent = ""; el("review-message").textContent = ""; form("review-form").reset()
        el("review-version").textContent = "Review applies to saved version " + str(r.cid) + ". Save content changes before reviewing."
        if (!dlg.open) dlg.showModal()
    }

    private suspend fun saveArtifact(formEl: HTMLFormElement) {
        busy(formEl) {
            try {
                val r = artifact
                val fields = copy(r.fields)
                val fe = elements(formEl)
                fields.title = fe.title.value; fields.content = fe.content.value; fields.reviewStatus = "proposed"
                val saved = save("artifact", fields, r)
                artifactDirty = false; openArtifact(saved); renderArtifacts(); el("artifact-message").textContent = "Revision saved. Review this version before use."
            } catch (e: Throwable) {
                el("artifact-message").textContent = errorText(e)
            }
        }
    }

    private suspend fun saveReview(formEl: HTMLFormElement) {
        busy(formEl) {
            try {
                if (artifactDirty) throw ApiError("Save the artifact revision before reviewing it.")
                val a = artifact
                val fe = elements(formEl)
                val b: dynamic = obj()
                b.subjectId = a.id; b.subjectCid = a.cid; b.applicationId = a.fields.applicationId; b.decision = fe.decision.value; b.notes = fe.notes.value
                save("review", b)
                renderArtifacts(); el("review-message").textContent = "Review saved for this artifact version."
            } catch (e: Throwable) {
                el("review-message").textContent = errorText(e)
            }
        }
    }

    private fun capture(kind: String) {
        (el("capture-kind") as HTMLSelectElement).value = kind; captureMode(); go("sources"); form("capture-form").asDynamic().scrollIntoView(js("({block:'start'})"))
    }

    private fun captureMode() {
        val fm = form("capture-form")
        val mode = str(elements(fm).inputMode.value)
        val parts = fm.querySelectorAll("[data-input]")
        for (i in 0 until parts.length) {
            val p = parts.item(i) as HTMLElement
            p.hidden = p.getAttribute("data-input") != mode
            val inputs = p.querySelectorAll("input,textarea")
            for (j in 0 until inputs.length) inputs.item(j).asDynamic().disabled = p.hidden
        }
        el("capture-listing-fields").hidden = (el("capture-kind") as HTMLSelectElement).value != "listing"
        el("capture-category-field").hidden = (el("capture-kind") as HTMLSelectElement).value != "evidence"
    }

    private suspend fun fileBase64(file: File): String = suspendCoroutine { cont ->
        val reader = FileReader()
        reader.onload = { cont.resume(str(reader.result).split(",")[1]) }
        reader.onerror = { cont.resumeWithException(ApiError("Could not read " + file.name)) }
        reader.readAsDataURL(file)
    }

    private suspend fun importMaterial(formEl: HTMLFormElement) {
        busy(formEl) {
            el("capture-results").asDynamic().replaceChildren(); el("capture-status").textContent = "Importing…"; el("capture-status").className = "small"
            try {
                val values: dynamic = js("Object").fromEntries(FormData(formEl))
                val mode = str(elements(formEl).inputMode.value)
                val base: dynamic = obj(); base.kind = values.kind
                for (field in listOf("sourceId", "title", "employer", "requisition", "contactId")) {
                    if (truthy(values[field]) && (values.kind == "listing" || field !in listOf("employer", "requisition", "contactId"))) base[field] = values[field]
                }
                if (values.kind == "evidence") base.category = values.category
                val inputs = ArrayList<dynamic>()
                if (mode == "file") {
                    val list = elements(formEl).files.files
                    val files = (0 until (list.length as Int)).map { list.item(it).unsafeCast<File>() }
                    if (files.isEmpty()) throw ApiError("Choose a document to import.")
                    for (file in files) if (file.size.toDouble() > 2 * 1024 * 1024) throw ApiError(file.name + " exceeds the 2 MiB browser upload limit.")
                    for (file in files) {
                        val i = copy(base); i.filename = file.name; i.mediaType = if (file.type.isNotEmpty()) file.type else undefined; i.base64 = fileBase64(file)
                        inputs.add(i)
                    }
                } else if (mode == "saved") {
                    val saved = record(base.sourceId)
                    if (!truthy(saved) || (!truthy(saved.fields.url) && !truthy(saved.fields.originalCid))) throw ApiError("Choose a saved source with a URL or a retained original.")
                    inputs.add(base)
                } else {
                    val text = strOrEmpty(values[mode]).trim()
                    if (text.isEmpty()) throw ApiError(if (mode == "url") "Enter a source URL." else "Paste some text to import.")
                    val i = copy(base); i[mode] = text; inputs.add(i)
                }
                var saved = 0
                for (input in inputs) {
                    el("capture-status").textContent = "Importing " + (saved + 1) + " of " + inputs.size + "…"
                    val result = api("/capture", input)
                    val ex: dynamic = if (truthy(result.extraction)) result.extraction else obj()
                    if (truthy(result.source) && truthy(result.source.id)) upsert(result.source)
                    if (truthy(result.record) && truthy(result.record.id)) { val r = upsert(result.record); saved++; el("capture-results").append(recordCard(r)) }
                    val status = if (truthy(ex.status)) str(ex.status) else if (truthy(result.record)) "ready" else "unknown"
                    if (status != "ready") {
                        el("capture-results").append(node("p", "error-text small", if (truthy(ex.error)) ex.error else "Import status: $status"))
                        if (!truthy(result.record)) throw ApiError(if (truthy(ex.error)) str(ex.error) else "The material could not be extracted ($status).")
                    }
                }
                renderRecords(); renderApplication(); el("capture-status").textContent = "$saved record" + (if (saved == 1) "" else "s") + " saved."
            } catch (e: Throwable) {
                val resp: dynamic = (e as? ApiError)?.response
                if (resp != null && truthy(resp.source) && truthy(resp.source.id)) {
                    val source = upsert(resp.source)
                    el("capture-results").append(recordCard(source), node("p", "small muted", "The source is retained. Evidence or listing text was not created for this failed extraction."))
                }
                el("capture-status").textContent = errorText(e); el("capture-status").className = "small error-text"; renderRecords()
            }
        }
    }

    private suspend fun saveCredential(formEl: HTMLFormElement) {
        busy(formEl) {
            val fe = elements(formEl)
            try {
                val url = URL(str(fe.origin.value))
                if (url.protocol != "http:" && url.protocol != "https:") throw ApiError("Use an HTTP origin.")
                val origin = url.origin
                val b: dynamic = obj(); b.sourceId = fe.sourceId.value; b.origin = origin; b.cookie = fe.cookie.value
                api("/credential", b, true)
                fe.cookie.value = ""; el("credential-status").textContent = "Source access saved for $origin."
                try { refresh() } catch (_: Throwable) { notice("Source access was saved, but the record list could not be refreshed.", true) }
            } catch (_: Throwable) {
                el("credential-status").textContent = "Source access could not be saved. Check the source, origin, and service configuration."
            } finally {
                fe.cookie.value = ""
            }
        }
    }

    private fun dirtyOf(dlg: Element): Boolean = if (dlg.id == "record-dialog") recordDirty else artifactDirty
    private fun clearDirty(dlg: Element) { if (dlg.id == "record-dialog") recordDirty = false else artifactDirty = false }

    private fun onSubmit(id: String, action: suspend (HTMLFormElement) -> Unit) {
        form(id).addEventListener("submit", { e: Event -> e.preventDefault(); val fm = e.currentTarget as HTMLFormElement; launchPage { action(fm) } })
    }

    private fun onBusyClick(id: String, action: suspend () -> Unit) {
        el(id).addEventListener("click", { e: Event -> val t = e.currentTarget as HTMLElement; launchPage { busy(t) { action() } } })
    }

    fun mount() {
        document.addEventListener("click", { event: Event ->
            val target = (event.target as? Element)?.closest("button") ?: return@addEventListener
            target.getAttribute("data-new")?.takeIf { it.isNotEmpty() }?.let { openRecord(it, null) }
            target.getAttribute("data-go")?.takeIf { it.isNotEmpty() }?.let { go(it) }
            target.getAttribute("data-capture")?.takeIf { it.isNotEmpty() }?.let { capture(it) }
            if (target.hasAttribute("data-close-dialog")) {
                val dlg = target.closest("dialog") as HTMLDialogElement
                if (!dirtyOf(dlg) || window.confirm("Discard unsaved edits?")) { dlg.close(); clearDirty(dlg) }
            }
        })
        val dialogs = document.querySelectorAll("dialog")
        for (i in 0 until dialogs.length) {
            val dlg = dialogs.item(i) as HTMLDialogElement
            dlg.addEventListener("cancel", { event: Event ->
                if (dirtyOf(dlg) && !window.confirm("Discard unsaved edits?")) event.preventDefault() else clearDirty(dlg)
            })
        }
        onSubmit("record-form") { saveRecord(it) }
        form("record-form").addEventListener("input", { event: Event -> if ((event.target as Element).closest("#record-fields") != null) recordDirty = true })
        el("record-neighbors").addEventListener("toggle", { launchPage { loadNeighbors() } })
        onBusyClick("save-record-review") {
            try {
                if (recordDirty) throw ApiError("Save record changes before reviewing this version.")
                val subject = edit?.value
                if (!truthy(subject)) throw ApiError("Save this record before reviewing it.")
                val b: dynamic = obj()
                b.subjectId = subject.id; b.subjectCid = subject.cid
                b.decision = (el("record-review-decision") as HTMLSelectElement).value; b.notes = (el("record-review-notes") as HTMLTextAreaElement).value
                if (applicationId.isNotEmpty()) b.applicationId = applicationId
                save("review", b)
                el("record-message").textContent = "Review saved for this record version."; el("record-message").className = "small success-text"; launchPage { loadCollisions() }
            } catch (e: Throwable) {
                el("record-message").textContent = errorText(e); el("record-message").className = "small error-text"
            }
        }
        onSubmit("artifact-form") { saveArtifact(it) }
        form("artifact-form").addEventListener("input", { artifactDirty = true })
        onSubmit("review-form") { saveReview(it) }
        onSubmit("capture-form") { importMaterial(it) }
        onSubmit("credential-form") { saveCredential(it) }
        form("capture-form").addEventListener("change", { captureMode() })
        el("record-kind").addEventListener("change", { renderRecords() })
        el("record-search").addEventListener("input", { renderRecords() })
        el("new-record").addEventListener("click", { openRecord((el("record-kind") as HTMLSelectElement).value, null) })
        el("application-select").addEventListener("change", { event: Event -> selectApplication((event.target as HTMLSelectElement).value) })
        el("edit-application").addEventListener("click", { openRecord("application", record(applicationId)) })
        el("prepare-instructions").addEventListener("input", { selectionDirty = true })
        onBusyClick("save-selection") {
            try { saveSelection(); notice("Preparation choices saved.") } catch (e: Throwable) { notice(errorText(e), true) }
        }
        el("prepare-assemble").addEventListener("click", { launchPage { prepare("assemble") } })
        el("prepare-model").addEventListener("click", { launchPage { prepare("model") } })
        el("rerun-assemble").addEventListener("click", { launchPage { prepare("assemble", true) } })
        el("rerun-model").addEventListener("click", { launchPage { prepare("model", true) } })
        el("run-select").addEventListener("change", {
            renderProgram()
            val run = runs.firstOrNull { it.runId == (el("run-select") as HTMLSelectElement).value }
            if (run != null) runSummary(el("program-run"), run) else el("program-run").asDynamic().replaceChildren()
        })
        el("refresh-collisions").addEventListener("click", { launchPage { loadCollisions() } })
        onBusyClick("refresh") {
            try { refresh(); notice("Records refreshed.") } catch (e: Throwable) { notice(errorText(e), true) }
        }
        onBusyClick("open-program") {
            try {
                val p = api("/program", obj())
                if (!truthy(p.name)) throw ApiError("The service did not return the saved program.")
                window.location.href = "/panels?load=" + encodeUri(str(p.name))
            } catch (e: Throwable) { notice(errorText(e), true) }
        }
        el("credential-source").addEventListener("change", { event: Event ->
            val source = record((event.target as HTMLSelectElement).value)
            if (truthy(source) && truthy(source.fields.origin)) elements(form("credential-form")).origin.value = source.fields.origin
        })
        window.addEventListener("hashchange", { showSection() })
        window.addEventListener("beforeunload", { event: Event ->
            if (selectionDirty || recordDirty || artifactDirty) { event.preventDefault(); event.asDynamic().returnValue = "" }
        })
        window.addEventListener("pagehide", { elements(form("credential-form")).cookie.value = "" })
        showSection(); captureMode()
        launchPage { try { refresh() } catch (e: Throwable) { notice(errorText(e), true) } }
    }
}
