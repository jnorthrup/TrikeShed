package borg.trikeshed.docs

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.lib.*

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

suspend fun main() = DocumentSurfacePage.mount()

/**
 * Kotlin/JS browser adapter for the document surface (DOM mounting, fetch, clicks).
 * Every string it paints comes
 * from [DocumentSurface] in commonMain; every JSON it reads is parsed by the
 * one commonMain parser. Mounted by the bundle's entry point when the served
 * shell carries `#document-surface`.
 *
 * The run blocks (AutoTools, Cut B) add three fetches and no decisions: [RunBlock.scan] finds the
 * page's build targets, [RunBlock.headQuery] says what to ask about each one, [RunBlock.state]
 * turns the answer into the frame's state, and a press sends [RunBlock.buildRequest] or
 * [RunBlock.rebuildRequest]. A three-second poll while a document is open is what makes a Stale
 * transition visible without an event stream; a generation counter cancels it the moment the
 * reader navigates away.
 *
 * WHAT A POLL MAY TOUCH. `#ds-doc` is the scroll container, so re-rendering the pane every three
 * seconds would yank the reader back to the top of a long page, drop the text they were selecting,
 * and — for a tick that lands between mousedown and mouseup — replace the button under the cursor
 * so the click never fires. A poll therefore repaints ONE frame, in place, and only when its state
 * actually moved; a full [render] is for navigation alone.
 */
object DocumentSurfacePage {
    fun present(): Boolean = document.getElementById(DocumentSurface.ROOT_ID) != null

    private const val POLL_MS = 3_000L

    private var projects: List<ProjectRow> = emptyList()
    private var project: String? = null
    private var docs: List<DocRow> = emptyList()
    private var doc: DocView? = null
    private var docError: String? = null
    private var blocks: List<RunBlock.Block> = emptyList()
    private var view: SurfaceView = SurfaceView.PAGE
    private var column: DocColumn = DocColumn.NAME
    private var ascending: Boolean = true
    private val runStates: MutableMap<Int, RunBlock.State> = mutableMapOf()

    /** Bumped on every navigation; a poll whose generation has moved on stops. */
    private var generation = 0
    private var passageView: String? = null
    private var pdfSelection: DocRevision? = null
    private var sourceRetention: SourceRetentionState? = null
    private val retainingSources = mutableSetOf<DocRevision>()

    // ── curation state: reachable from the sidebar and #curation, independent of a project ──
    private var curationMode = false
    private var curationAvailable = false
    private var curationDestination: CurationDestination? = null
    private var curationRecords: List<CurationRecordRow> = emptyList()
    private var curationResult: CurationResult? = null
    private var curationPendingName = ""
    private var curationPendingText = ""
    private var curationFormError: String? = null
    private var curationListError: String? = null
    private var curationResultError: String? = null
    private var curationBusy = false

    suspend fun mount() {
        borg.trikeshed.web.AppNav.mount()
        val root = document.getElementById(DocumentSurface.ROOT_ID) as HTMLElement
        root.innerHTML = DocumentSurface.layout()
        DocumentsUpload.mount()
        window.addEventListener("pagehide", { DocumentsPdfLinks.clear() })
        root.addEventListener("click", { e -> onClick(e) })
        root.addEventListener("submit", { e -> onSubmit(e) })
        root.addEventListener("input", { e -> onInput(e) })
        projects = DocumentSurface.projects(getJson("/api/projects"))
        // Keep the original deep link intact while its project, receipt and document load.
        render(keepLocation = true)
        window.addEventListener("popstate", { MainScope().launch { openLocation() } })
        openLocation()
        js("window.forgeKotlin = Object.assign(window.forgeKotlin || {}, { documentSurface: true })")
    }

    private suspend fun openLocation() {
        val (head, tail) = DocumentSurface.fragmentParts(window.location.hash)
        val projectOrCuration = decode(head)
        val selectedId = decode(tail)
        val query = URLSearchParams(window.location.search)
        val passageRequested = query.has("passageReceipt") || query.has("passageProposal")
        val receipt = query.get("passageReceipt").orEmpty()
        val ordinal = query.get("passageProposal")?.let { if (Regex("^(0|[1-9][0-9]*)$").matches(it)) it.toIntOrNull() else null } ?: -1
        if (projectOrCuration == DocumentSurface.CURATION_HASH) {
            openCuration(selectedId.ifEmpty { null }, keepLocation = true)
        } else if (projectOrCuration.isNotEmpty()) {
            if (passageRequested && selectedId.isNotEmpty()) openPassage(projectOrCuration, selectedId, receipt, ordinal)
            else {
                if (openProject(projectOrCuration, keepLocation = true) && selectedId.isNotEmpty()) {
                    view = SurfaceView.PAGE
                    openDoc(selectedId, keepLocation = true)
                }
            }
        } else {
            generation++; clearPassage(); curationMode = false; project = null; doc = null; docError = null; pdfSelection = null
            docs = emptyList(); blocks = emptyList(); runStates.clear(); view = SurfaceView.PAGE
            render(keepLocation = true)
        }
    }

    /** Remove passage state when leaving it without changing unrelated query or hash state. */
    private fun clearPassage() {
        passageView = null
        val url = URL(window.location.href)
        val contextPresent = SourceSelection.keys.view.any { url.searchParams.has(it) }
        if (!url.searchParams.has("passageReceipt") && !url.searchParams.has("passageProposal") && !contextPresent) return
        for (key in SourceSelection.keys.view) url.searchParams.delete(key)
        url.searchParams.delete("passageReceipt"); url.searchParams.delete("passageProposal")
        window.history.replaceState(window.history.asDynamic().state, "", url.pathname + url.search + url.hash)
        window.dispatchEvent(Event("hashchange"))
    }

    private fun onClick(e: Event) {
        val target = (e.target as? Element)
            ?.closest("[data-project],[data-doc],[data-source-project],[data-retain-source],[data-run-build],[data-run-rebuild],[data-view],[data-sort],[data-curate],[data-curate-open]")
            ?: return
        e.preventDefault()
        if (target.hasAttribute("data-retain-source")) { MainScope().launch { retainSource() }; return }
        val p = target.getAttribute("data-project")
        val d = target.getAttribute("data-doc")
        val sourceProject = target.getAttribute("data-source-project")
        val sourceDocument = target.getAttribute("data-source-document")
        val build = target.getAttribute("data-run-build")
        val rebuild = target.getAttribute("data-run-rebuild")
        val wantView = target.getAttribute("data-view")
        val sort = target.getAttribute("data-sort")
        val curate = target.getAttribute("data-curate")
        val curateOpen = target.getAttribute("data-curate-open")
        // A view switch and a sort are projections of what is already loaded: they repaint from
        // the rows in hand and never re-read the daemon.
        if (wantView != null) {
            if (wantView == "table") {
                if (passageView != null) generation++
                clearPassage()
            }
            view = if (wantView == "table") SurfaceView.TABLE else SurfaceView.PAGE
            render(); return
        }
        if (sort != null) {
            val next = DocColumn.of(sort)
            ascending = if (next == column) !ascending else true
            column = next
            render(); return
        }
        if (curate != null) { MainScope().launch { openCuration(null) }; return }
        if (curateOpen != null) { MainScope().launch { openCuration(curateOpen) }; return }
        MainScope().launch {
            when {
                sourceProject != null && sourceDocument != null -> { if (openProject(sourceProject)) { view = SurfaceView.PAGE; openDoc(sourceDocument) }; Unit }
                p != null -> { openProject(p); Unit }
                // Opening a page from the table is how a reader leaves the table: the row they
                // picked is the page they meant, so the view follows the gesture.
                d != null -> { view = SurfaceView.PAGE; openDoc(d) }
                build != null -> press(build.toIntOrNull(), rebuild = false)
                rebuild != null -> press(rebuild.toIntOrNull(), rebuild = true)
            }
        }
    }

    /** Keeps the draft alive across a repaint (a saved-result open, a poll) that replaces the DOM. */
    private fun onInput(e: Event) {
        when ((e.target as? Element)?.getAttribute("id")) {
            "ds-curate-name" -> curationPendingName = (e.target as HTMLInputElement).value
            "ds-curate-text" -> curationPendingText = (e.target as HTMLTextAreaElement).value
        }
    }

    private fun onSubmit(e: Event) {
        (e.target as? Element)?.closest("[data-curate-form]") ?: return
        e.preventDefault()
        if (curationBusy) return
        MainScope().launch { submitCuration() }
    }

    /** Independent of project browsing: leaving it and returning finds the workspace unchanged. */
    suspend fun openCuration(cid: String?, keepLocation: Boolean = false) {
        val ticket = ++generation
        pdfSelection = null
        clearPassage()
        curationMode = true
        curationFormError = null
        curationListError = null
        curationResultError = null
        val listJson = requestJson("/api/documents")
        if (ticket != generation) return
        curationListError = DocumentSurface.curationError(listJson)
        curationAvailable = DocumentSurface.curationAvailable(listJson)
        curationDestination = DocumentSurface.curationDestination(listJson)
        curationRecords = DocumentSurface.curationList(listJson)
        if (cid == null) {
            curationResult = null
        } else {
            val resultJson = requestJson("/api/documents?cid=" + encode(cid))
            if (ticket != generation) return
            val err = DocumentSurface.curationError(resultJson)
            curationResult = if (err == null) DocumentSurface.curationResult(resultJson) else null
            curationResultError = err ?: if (curationResult == null) "That saved result could not be read." else null
        }
        render(keepLocation)
    }

    private suspend fun retainSource() {
        val selected = doc?.let(DocumentSurface::retainableRevision) ?: return
        if (!retainingSources.add(selected)) return
        val ticket = generation
        sourceRetention = SourceRetentionState(selected, true, "Retaining source…")
        render(keepLocation = true)
        val response = requestJson("/api/projects/" + encode(selected.project) + "/docs/" + encode(selected.id) + "/retain-source",
            "POST", jsonOf(mapOf("cid" to selected.cid, "rev" to selected.rev, "seq" to selected.seq))) as? Map<*, *>
        retainingSources.remove(selected)
        if (ticket != generation || curationMode || doc?.let(DocumentSurface::retainableRevision) != selected) {
            if (sourceRetention?.revision == selected && sourceRetention?.pending == true) sourceRetention = null
            if (!curationMode && doc?.let(DocumentSurface::retainableRevision) == selected) render(keepLocation = true)
            return
        }
        val cid = response?.get("sourceCid") as? String
        val message = when {
            response?.get("error") != null -> "Source could not be retained: ${response["error"]}"
            response?.get("action") == "retained-source" && response["reviewStatus"] == "unreviewed" &&
                cid != null && Regex("^sha256:[0-9a-f]{64}$").matches(cid) -> "Source retained for review; unreviewed. Receipt: $cid"
            else -> "Source retention could not be verified."
        }
        sourceRetention = SourceRetentionState(selected, false, message)
        render(keepLocation = true)
    }

    private suspend fun submitCuration() {
        if (curationBusy) return
        val text = curationPendingText
        if (text.isBlank()) { curationFormError = "Enter some text to curate."; render(); return }
        curationBusy = true; curationFormError = null
        render()
        val source = mapOf(
            "text" to text, "mediaType" to "text/plain",
            "name" to curationPendingName.ifBlank { "Untitled" },
        )
        val json = requestJson("/api/documents/curate", "POST", jsonOf(mapOf("source" to source)))
        curationBusy = false
        val err = DocumentSurface.curationError(json)
        val result = if (err == null) DocumentSurface.curationResult(json) else null
        when {
            json == null -> curationFormError = "Could not reach the curation service."
            err != null -> curationFormError = err
            result == null -> curationFormError = "The curation service returned an unexpected response."
            else -> {
                curationResult = result
                curationRecords = (listOf(CurationRecordRow(result.receiptCid, result.name)) + curationRecords)
                    .distinctBy { it.receiptCid }
                curationPendingName = ""; curationPendingText = ""
            }
        }
        render()
    }

    suspend fun openProject(name: String, keepLocation: Boolean = false): Boolean {
        val ticket = ++generation
        clearPassage()
        curationMode = false
        project = name; doc = null; docError = null; docs = emptyList(); pdfSelection = null
        view = SurfaceView.TABLE
        blocks = emptyList(); runStates.clear()
        render(keepLocation = true)
        val loaded = DocumentSurface.docs(getJson("/api/projects/" + encode(name) + "/docs?limit=512"))
        if (ticket != generation || project != name || curationMode) return false
        docs = loaded
        render(keepLocation)
        return true
    }

    suspend fun openDoc(id: String, keepLocation: Boolean = false) {
        val p = project ?: return
        curationMode = false
        clearPassage()
        val generationHere = ++generation
        doc = null; docError = null; blocks = emptyList(); runStates.clear(); pdfSelection = null
        render(keepLocation = true)
        val response = requestJson("/api/projects/" + encode(p) + "/docs/" + encode(id))
        if (generationHere != generation || project != p || curationMode) return
        docError = DocumentSurface.documentError(response)
        doc = if (docError == null) DocumentSurface.document(response) else null
        if (doc == null && docError == null) docError = "Document could not be read."
        val pdf = pdfRevision(p, id, response)
        if (generationHere != generation || project != p || curationMode) return
        pdfSelection = pdf
        blocks = doc?.takeIf { DocumentSurface.isMarkdown(it) }?.let { RunBlock.scan(it.text) } ?: emptyList()
        runStates.clear()
        render(keepLocation)
        if (blocks.none { it is RunBlock.Spec }) return
        refreshBlocks()
        MainScope().launch {
            while (generationHere == generation) {
                delay(POLL_MS)
                if (generationHere != generation) return@launch
                refreshBlocks()
            }
        }
    }

    /** The receipt, project listing and current document belong to the same navigation ticket. */
    private suspend fun openPassage(p: String, id: String, receipt: String, ordinal: Int) {
        val ticket = ++generation
        project = p; doc = null; docError = null; curationMode = false; view = SurfaceView.PAGE; pdfSelection = null
        docs = emptyList(); blocks = emptyList(); runStates.clear()
        passageView = "<section data-passage-result=\"1\"><p>Reading selected passage…</p></section>"
        render(keepLocation = true)
        val listed = DocumentSurface.docs(requestJson("/api/projects/" + encode(p) + "/docs?limit=512"))
        if (ticket != generation) return
        docs = listed; render(keepLocation = true)
        val retained = if (Regex("^sha256:[0-9a-f]{64}$").matches(receipt) && ordinal >= 0)
            DocumentSurface.curationResult(requestJson("/api/documents?cid=" + encode(receipt))) else null
        if (ticket != generation) return
        val selected = retained?.takeIf { it.receiptCid == receipt }?.let { DocumentSurface.passage(it, ordinal) }
        val response = requestJson("/api/projects/" + encode(p) + "/docs/" + encode(id))
        if (ticket != generation || project != p || curationMode) return
        docError = DocumentSurface.documentError(response)
        val current = if (docError == null) DocumentSurface.document(response) else null
        if (current == null && docError == null) docError = "Document could not be read."
        doc = current
        val pdf = pdfRevision(p, id, response)
        if (ticket != generation || project != p || curationMode) return
        pdfSelection = pdf
        blocks = doc?.takeIf { DocumentSurface.isMarkdown(it) }?.let { RunBlock.scan(it.text) } ?: emptyList()
        runStates.clear()
        passageView = DocumentSurface.passageHtml(current, selected)
        if (selected != null && selected.source.project == p && selected.source.id == id) {
            val url = URL(window.location.href)
            for ((key, values) in SourceSelection.queryValues(SourceSelection.from(selected).query()))
                url.searchParams.set(key, values.single())
            window.history.replaceState(window.history.asDynamic().state, "", url.pathname + url.search + url.hash)
            window.dispatchEvent(Event("hashchange"))
        }
        render(keepLocation = true)
        if (blocks.none { it is RunBlock.Spec }) return
        refreshBlocks()
        MainScope().launch {
            while (ticket == generation) {
                delay(POLL_MS)
                if (ticket != generation) return@launch
                refreshBlocks()
            }
        }
    }

    /**
     * One read per block. A fetch that fails leaves the block's last known state alone, and a
     * quiet answer — the same [RunBlock.State] the frame already wears — repaints nothing at all:
     * `State` is a data class, so an unmoved head compares equal and the reader's page is left
     * exactly as they are using it.
     */
    private suspend fun refreshBlocks() {
        val ticket = generation
        // Bolt: avoid intermediate List/Sequence allocation from filterIsInstance
        for (spec in blocks) {
            if (spec !is RunBlock.Spec) continue
            val state = RunBlock.state(getJson(headUrl(spec))) ?: continue
            if (ticket != generation) return
            if (runStates.put(spec.ordinal, state) != state) repaint(spec, state)
        }
    }

    /** The head read's URL: the query is [RunBlock.headQuery]'s, the encoding is the browser's. */
    private fun headUrl(spec: RunBlock.Spec): String =
        RunBlock.HEAD_PATH + "?" + RunBlock.headQuery(spec).joinToString("&") { (name, value) -> name + "=" + encode(value) }

    /** One frame, replaced where it stands. A frame that is somehow gone falls back to a render. */
    private fun repaint(spec: RunBlock.Spec, state: RunBlock.State) {
        val frame = document.getElementById(RunBlock.frameId(spec.ordinal))
        if (frame == null) render() else frame.outerHTML = RunBlock.frameHtml(spec, state)
    }

    /**
     * Build sends the block's body verbatim to the run route — timeoutMs, maxNodes and all — and
     * Rebuild sends the artifact's run id. Both hold the request open for the whole run (the route
     * answers with the terminal receipt), so the pressing tab wears an optimistic Running until it
     * resolves; another tab reads the same run off the board.
     */
    private suspend fun press(ordinal: Int?, rebuild: Boolean) {
        // Bolt: avoid intermediate List/Sequence allocation from filterIsInstance
        val spec = blocks.firstOrNull { it is RunBlock.Spec && it.ordinal == ordinal } as? RunBlock.Spec ?: return
        val prior = runStates[spec.ordinal]
        val (url, body) = if (rebuild) (RunBlock.rebuildRequest(prior) ?: return) else RunBlock.buildRequest(spec)
        val pending = RunBlock.pending(prior)
        runStates[spec.ordinal] = pending
        repaint(spec, pending)
        postJson(url, body)
        refreshBlocks()
    }

    /** Metadata names a selection; the explicit inventory POST verifies its current bytes. */
    private suspend fun pdfRevision(p: String, id: String, response: Any?): DocRevision? {
        val fields = response as? Map<*, *>
        val type = (fields?.get("contentType") as? String).orEmpty().substringBefore(';').trim()
        if (!id.endsWith(".pdf", ignoreCase = true) && type != "application/pdf") return null
        val direct = DocumentSurface.document(response)?.sourceRevision
        val revision = direct ?: DocumentSurface.docs(requestJson("/api/projects/" + encode(p) + "/docs?prefix=" + encode(id) + "&limit=8"))
            .firstOrNull { it.id == id }?.let { DocRevision(p, id, it.cid, it.rev, it.seq) }
        return revision?.takeIf { it.project == p && it.id == id && it.rev.isNotBlank() && it.seq >= 0 && Regex("^sha256:[0-9a-f]{64}$").matches(it.cid) }
    }

    private fun render(keepLocation: Boolean = false) {
        val selectedId = doc?.id ?: pdfSelection?.id
        setHtml(DocumentSurface.SIDE_ID, DocumentSurface.sidebarHtml(projects, project, docs, selectedId, curationMode))
        if (curationMode) {
            DocumentsPdfLinks.clear()
            setHtml(DocumentSurface.CRUMB_ID, DocumentSurface.curationCrumbHtml(curationResult))
            setHtml(DocumentSurface.CONTENT_ID, DocumentSurface.curationHtml(
                curationAvailable, curationDestination, curationRecords,
                curationPendingName, curationPendingText, curationFormError, curationListError, curationBusy,
                curationResult, curationResultError,
            ))
            val wanted = "#" + DocumentSurface.CURATION_HASH + (curationResult?.let { "/" + encode(it.receiptCid) } ?: "")
            if (!keepLocation) setHash(wanted)
            document.title = listOfNotNull(curationResult?.name?.ifBlank { null }, "Curate", "Documents").joinToString(" · ")
            return
        }
        setHtml(DocumentSurface.CRUMB_ID, DocumentSurface.crumbHtml(project, selectedId, view))
        setHtml(
            DocumentSurface.CONTENT_ID,
            if (view == SurfaceView.TABLE) DocumentSurface.tableHtml(project, docs, column, ascending, doc?.id)
            else (passageView ?: "") + (docError?.let { error -> DocumentSurface.documentErrorHtml(
                if (pdfSelection != null && error == "Document unavailable: not_text") "PDF retained. Text preview unavailable." else error
            ) } ?: DocumentSurface.documentHtml(doc, runStates.toMap(),
                doc?.let(DocumentSurface::retainableRevision)?.let { selected ->
                    if (selected in retainingSources) SourceRetentionState(selected, true, "Retaining source…")
                    else sourceRetention?.takeIf { it.revision == selected }
                })),
        )
        val pdf = pdfSelection
        if (view == SurfaceView.PAGE && pdf != null) {
            val ticket = generation
            val host = document.createElement("div") as HTMLElement
            document.getElementById(DocumentSurface.CONTENT_ID)?.appendChild(host)
            DocumentsPdfLinks.mount(host, pdf, ticket) { generation == ticket && !curationMode && view == SurfaceView.PAGE && project == pdf.project && pdfSelection == pdf }
        } else DocumentsPdfLinks.clear()
        val p = project
        val wanted = if (p == null) "" else "#" + encode(p) + (selectedId?.let { "/" + encode(it) } ?: "")
        if (!keepLocation) setHash(wanted)
        document.title = listOfNotNull(selectedId, project, "Documents").joinToString(" · ")
    }

    private fun setHash(wanted: String) {
        if (window.location.hash == wanted) return
        window.history.pushState(window.history.asDynamic().state, "", window.location.pathname + window.location.search + wanted)
        window.dispatchEvent(Event("hashchange"))
    }

    private fun setHtml(id: String, html: String) {
        (document.getElementById(id) as? HTMLElement)?.innerHTML = html
    }

    /**
     * Curation reads its own `error` field from the body, but a non-2xx with no body-level error
     * (a proxy timeout, a 404) still must not read as quiet success: the HTTP status becomes the
     * error in that case.
     */
    private suspend fun requestJson(url: String, method: String = "GET", body: String? = null): Any? = runCatching {
        val response = if (method == "GET") window.fetch(url).await() else window.fetch(url, RequestInit(
            method = method, headers = Headers().apply { append("Content-Type", "application/json") }, body = body,
        )).await()
        val parsed = reify(response.text().await())
        if (response.ok) parsed else {
            val fields = (parsed as? Map<*, *>)?.toMutableMap() ?: mutableMapOf<Any?, Any?>()
            if (fields["error"] == null) fields["error"] = "Request failed (HTTP ${response.status})"
            fields
        }
    }.getOrNull()

    private suspend fun getJson(url: String): Any? = runCatching {
        val response = window.fetch(url).await()
        val text = response.text().await()
        reify(text)
    }.getOrNull()

    private suspend fun postJson(url: String, body: String): Any? = runCatching {
        val init = RequestInit(
            method = "POST",
            headers = Headers().apply { append("Content-Type", "application/json") },
            body = body,
        )
        val response = window.fetch(url, init).await()
        val text = response.text().await()
        reify(text)
    }.getOrNull()
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String
private fun decode(s: String): String = runCatching { js("decodeURIComponent(s)") as String }.getOrDefault(s)
