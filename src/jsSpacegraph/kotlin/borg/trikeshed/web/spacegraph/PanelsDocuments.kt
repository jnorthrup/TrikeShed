package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

// Credentials live only in this tab's memory, never in graph JSON, receipts or browser storage.
private var documentReviewCredential: String? = null

private suspend fun reviewRequest(path: String, body: dynamic = null, authenticated: Boolean = false): dynamic {
    val init = objOf {
        it.method = if (body == null) "GET" else "POST"
        it.headers = objOf { headers ->
            if (body != null) headers["Content-Type"] = "application/json"
            if (authenticated) headers.Authorization = "Bearer " +
                (documentReviewCredential ?: error("Authenticate a reviewer first"))
        }
        if (body != null) it.body = JSON.stringify(body)
    }
    val response = fetchJs("/api/documents/review" + path, init)
    val result = JSON.parse<dynamic>(PatchRead.text(response))
    require(truthy(response.ok)) { str(result?.detail ?: result?.error ?: "Review request failed") }
    return result
}

private fun Panels.documentReviewPane(target: dynamic) {
    val pane = target.closest(".result") ?: return
    if (pane.classList.contains("document-review-result") == true) return
    pane.classList.add("document-review-result")
    page.on(pane, "wheel", { event -> event.stopPropagation() }, objOf { it.passive = true })
    var observer: dynamic = null
    var forget: (() -> Unit)? = null
    observer = jsNew(js("ResizeObserver"), { _: dynamic ->
        if (page.closing || !truthy(pane.isConnected)) { observer.disconnect(); forget?.invoke() }
        else {
            val owner = nodes().firstOrNull { it.el === pane.parentElement }
            if (owner != null) growRingWorldFor(owner)
            resolveTopLevelOverlaps()
            redraw()
        }
    })
    forget = page.own { observer.disconnect() }
    observer.observe(pane)
}

/** A decision acts on the displayed immutable snapshot and revision, not on editable canvas values. */
private fun Panels.documentReviewActions(target: dynamic, initial: dynamic, current: () -> Boolean) {
    documentReviewPane(target)
    var review = initial
    var generation = 0
    val box = document.createElement("section").asDynamic()
    box.className = "document-review"
    target.appendChild(box)
    fun render() {
        val rendered = ++generation
        fun present() = current() && truthy(box.isConnected) && generation == rendered
        box.textContent = ""
        val title = document.createElement("h4")
        title.textContent = "Analyst review · " + str(review.state) + " · revision " + str(review.revision)
        box.appendChild(title)
        val details = document.createElement("details")
        val detailTitle = document.createElement("summary")
        detailTitle.textContent = "Frozen source, ontology and evidence"
        details.appendChild(detailTitle); box.appendChild(details)
        val evidence = document.createElement("pre")
        evidence.textContent = JSON.stringify(review.snapshot, null, 2)
        evidence.asDynamic().style.cssText = "white-space:pre-wrap;max-height:320px;overflow:auto"
        details.appendChild(evidence)
        if (review.sourceCurrent == true && isArray(review.snapshot?.selections)) {
            val subset = document.createElement("details").asDynamic()
            val summary = document.createElement("summary")
            summary.textContent = "Choose a smaller evidence selection"
            subset.appendChild(summary); box.appendChild(subset)
            documentSnapshotAction(subset, str(review.snapshot.kind), str(review.snapshot.curationReceiptCid),
                arr(review.snapshot.selections), ::present) { frozen -> review = frozen; render() }
        }
        val identity = document.createElement("p")
        identity.textContent = if (review.synthetic == true) "Synthetic validation decision by " + str(review.reviewer)
            else if (typeOf(review.reviewer) == "string") "Decision by " + str(review.reviewer) else "No authenticated decision yet."
        box.appendChild(identity)
        val credential = document.createElement("input").asDynamic()
        credential.type = "password"; credential.autocomplete = "off"
        credential.placeholder = "Reviewer credential"
        credential.setAttribute("aria-label", "Reviewer credential")
        box.appendChild(credential)
        val note = document.createElement("textarea").asDynamic()
        note.placeholder = "Decision note or correction reason"; note.maxLength = 4096
        note.setAttribute("aria-label", "Review decision note")
        note.style.cssText = "display:block;width:100%;margin:8px 0"
        box.appendChild(note)
        fun button(label: String, operation: suspend () -> Unit) {
            val button = document.createElement("button").asDynamic()
            button.textContent = label; box.appendChild(button)
            on(button, "click", { event ->
                event.stopPropagation(); button.disabled = true
                launchJs {
                    try { require(present()) { "The source result or review changed; reopen its current review" }; operation() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: dynamic) { status("Review: " + failureText(error)) }
                    finally { button.disabled = false }
                }
            })
        }
        button("Authenticate reviewer") {
            val supplied = str(credential.value).trim()
            if (supplied.isNotEmpty()) documentReviewCredential = supplied
            credential.value = ""
            val session = reviewRequest("/session", authenticated = true)
            require(present()) { "The review changed while authenticating; reopen it" }
            identity.textContent = (if (session.synthetic == true) "Synthetic validation reviewer: " else "Authenticated reviewer: ") + str(session.reviewer)
        }
        button("Forget credential") {
            documentReviewCredential = null; credential.value = ""
            identity.textContent = "Reviewer credential removed from this tab."
        }
        button("Refresh review") {
            val refreshed = reviewRequest("?snapshotCid=" + encodeURIComponent(str(review.snapshotCid)))
            require(present()) { "The source result changed while reading the review" }
            review = refreshed
            render()
        }
        for ((label, action) in arrayOf("Save draft" to "draft", "Approve exact selection" to "approve",
            "Reject selection" to "reject", "Correct / withdraw approval" to "correct")) {
            button(label) {
                require(review.current == true) { "This review is stale; create a snapshot from current evidence" }
                val request = objOf {
                    it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision
                    it.action = action; it.note = str(note.value)
                }
                val decided = reviewRequest("/decision", request, authenticated = true)
                require(present()) { "Decision retained, but this result changed; reopen the review before continuing" }
                review = decided
                render()
                status(if (action == "correct") "Approval withdrawn. Correct the source and curate a new snapshot before approval."
                    else "Review " + action + " retained at revision " + str(review.revision))
            }
        }
        if (review.state == "approve" && review.current == true) {
            val reference = objOf {
                it.snapshotCid = review.snapshotCid; it.approvalCid = review.decisionCid; it.expectedRevision = review.revision
            }
            val receipt = document.createElement("pre")
            receipt.textContent = JSON.stringify(reference, null, 2)
            receipt.asDynamic().style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
            box.appendChild(receipt)
            val instruction = document.createElement("p")
            instruction.textContent = "Put this reference in the workflow's approvals array, then run export. Export issues and consumes its own one-use permit."
            box.appendChild(instruction)
            for ((label, value) in arrayOf("Copy approval reference" to JSON.stringify(reference),
                "Copy approvals array" to JSON.stringify(arrayOf(reference)))) {
                val copy = document.createElement("button").asDynamic(); copy.textContent = label; box.appendChild(copy)
                on(copy, "click", { event ->
                    event.stopPropagation()
                    if (present()) copyText(value) else status("Review changed; refresh before copying approval references.")
                })
            }
        }
        if (review.current != true) {
            val warning = document.createElement("p")
            warning.textContent = "This snapshot or source is no longer current. Decisions and release are refused."
            box.appendChild(warning)
        }
    }
    render()
}

private fun Panels.documentSourceActions(target: dynamic, selection: dynamic, current: () -> Boolean) {
    val value = selection.value ?: selection
    val quotation = value.quotation ?: value.sourceEvidence ?: selection.quotation ?: return
    val source = document.createElement("div").asDynamic()
    source.style.cssText = "margin:4px 0 12px 24px;overflow-wrap:anywhere"
    target.appendChild(source)
    val anchors = if (isArray(quotation.anchors)) arr(quotation.anchors) else emptyArray()
    fun physicalPage(value: dynamic): Int? = if (typeOf(value) == "number" && num(value).isFinite() &&
        num(value) >= 1 && num(value) % 1.0 == 0.0) num(value).toInt() else null
    val firstPage = physicalPage(quotation.page) ?: anchors.firstNotNullOfOrNull { physicalPage(it.page) }
    val description = document.createElement("p")
    val span = if (typeOf(quotation.begin) == "number" && typeOf(quotation.end) == "number")
        " · UTF-16 span " + str(quotation.begin) + "–" + str(quotation.end) else ""
    description.textContent = str(quotation.category ?: "source") + span +
        (if (firstPage == null) " · page unresolved" else " · PDF page " + firstPage)
    source.appendChild(description)
    for (anchor in anchors) {
        val fields = arrayOf("page", "folio", "section", "table", "diagram", "coordinates", "coordinateSystem")
            .filter { anchor[it] != null }.joinToString(" · ") { key ->
                key + " " + if (isArray(anchor[key])) JSON.stringify(anchor[key]) else str(anchor[key])
            }
        if (fields.isNotBlank()) {
            val detail = document.createElement("div"); detail.textContent = fields; source.appendChild(detail)
        }
    }
    fun link(label: String, href: String, download: Boolean = false) {
        val link = document.createElement("a").asDynamic()
        link.textContent = label; link.href = href; link.target = "_blank"; link.rel = "noopener noreferrer"
        link.style.cssText = "display:inline-block;margin:4px 12px 0 0;color:var(--wire)"
        if (download) link.download = ""
        on(link, "click", { event ->
            event.stopPropagation()
            if (!current()) { event.preventDefault(); status("Source result changed; reopen its retained evidence.") }
        })
        source.appendChild(link)
    }
    val provenance = quotation.provenance
    for (url in arrayOf(provenance?.requestedUrl, provenance?.finalUrl).filter { typeOf(it) == "string" }
        .map { str(it).trim() }.distinct().filter { Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        link("Source URL", url)
    val cid = str(quotation.originalCid)
    if (!Regex("^sha256:[0-9a-f]{64}$").matches(cid)) return
    val path = "/api/v0/block/get?arg=" + encodeURIComponent(cid)
    link("Download retained original", path, download = true)
    val extracted = str(quotation.extractedTextCid)
    if (extracted != cid && Regex("^sha256:[0-9a-f]{64}$").matches(extracted))
        link("Download extracted text", "/api/v0/block/get?arg=" + encodeURIComponent(extracted), download = true)
    val load = document.createElement("button").asDynamic()
    load.textContent = "Load retained original"; source.appendChild(load)
    on(load, "click", { event ->
        event.stopPropagation(); load.disabled = true
        launchJs {
            try {
                require(current()) { "Source result changed; reopen its retained evidence" }
                val response = fetchJs(path)
                require(truthy(response.ok)) { "Retained original could not be read" }
                val bytes = PatchRead.bytes(response, 64 * 1024 * 1024)
                val digest = awaitJs(js("crypto.subtle.digest('SHA-256', bytes)"))
                val actual: String = "sha256:" + js("Array.from(new Uint8Array(digest), function(b){return b.toString(16).padStart(2,'0')}).join('')")
                require(actual == cid) { "Retained original content identity mismatch" }
                require(current() && truthy(source.isConnected)) { "Source result changed while reading its original" }
                val pdf = num(bytes.length) >= 5 && num(bytes[0]) == 37.0 && num(bytes[1]) == 80.0 &&
                    num(bytes[2]) == 68.0 && num(bytes[3]) == 70.0 && num(bytes[4]) == 45.0
                val mime = if (pdf) "application/pdf" else if (cid == extracted) "text/plain;charset=utf-8" else "application/octet-stream"
                val blob = js("new Blob([bytes], {type:mime})")
                val url: String = js("URL").createObjectURL(blob)
                page.own { js("URL").revokeObjectURL(url) }
                link(if (pdf && firstPage != null) "Open retained PDF · page " + firstPage else "Open retained original",
                    url + if (pdf && firstPage != null) "#page=" + firstPage else "")
                load.hidden = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: dynamic) { status("Retained source: " + failureText(error)) }
            finally { load.disabled = false }
        }
    })
}

/** Only identifiers are submitted; the server resolves tuples and quotations from the retained receipt. */
private fun Panels.documentSnapshotAction(target: dynamic, kind: String, receiptCid: String,
    selections: Array<dynamic>, current: () -> Boolean, frozen: ((dynamic) -> Unit)? = null) {
    if (selections.isEmpty()) return
    documentReviewPane(target)
    val box = document.createElement("section").asDynamic()
    target.appendChild(box)
    val title = document.createElement("h4"); title.textContent = "Choose material for analyst review"; box.appendChild(title)
    val entries = document.createElement("div").asDynamic()
    entries.className = "document-review-choices"
    entries.style.cssText = "max-height:320px;overflow:auto"
    box.appendChild(entries)
    val choices = selections.map { selection ->
        val label = document.createElement("label").asDynamic()
        label.style.cssText = "display:flex;align-items:flex-start;gap:8px;margin:6px 0"
        val input = document.createElement("input").asDynamic(); input.type = "checkbox"
        label.appendChild(input)
        val caption = document.createElement("span")
        val value = selection.value ?: selection
        val relation = arrayOf(value.subject, value.predicate, value.`object`).filter { it != null }.joinToString(" ") { str(it) }
        val mapping = value.ontologyMapping?.status
        val classes = if (isArray(value.ontologyMapping?.classes)) arr(value.ontologyMapping.classes).joinToString(", ") { str(it) } else ""
        caption.textContent = str(selection.label ?: relation.takeIf { it.isNotBlank() } ?: selection.id) +
            (if (mapping == null) "" else " [" + str(mapping) + (if (classes.isBlank()) "" else ": " + classes) + "]") +
            " · " + str(selection.quotation?.quote ?: value.quotation?.quote ?: value.sourceEvidence?.quote ?: selection.quote ?: "")
        label.appendChild(caption); entries.appendChild(label)
        documentSourceActions(entries, selection, current)
        str(selection.id) to input
    }
    val button = document.createElement("button").asDynamic()
    button.textContent = "Freeze selected evidence for review"; box.appendChild(button)
    on(button, "click", { event ->
        event.stopPropagation(); button.disabled = true
        launchJs {
            try {
                require(current()) { "The source result changed; use its current receipt" }
                val selected = choices.filter { it.second.checked == true }.map { it.first }
                require(selected.isNotEmpty()) { "Select at least one relationship or rule" }
                val review = reviewRequest("/snapshot", objOf {
                    it.kind = kind; it.receiptCid = receiptCid; it.selection = selected.toTypedArray()
                })
                require(current()) { "The source result changed while retaining the snapshot; reopen its review" }
                if (frozen != null) frozen(review) else documentReviewActions(target, review, current)
                status("Exact evidence selection retained for analyst review.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: dynamic) { status("Review snapshot: " + failureText(error)) }
            finally { button.disabled = false }
        }
    })
}

/** Composite results carry identifiers only; inspection resolves their authoritative snapshots on demand. */
fun Panels.appendDocumentReviewReferences(n: dynamic, out: dynamic, canvasCurrent: () -> Boolean) {
    val target = n.el.querySelector(".result") ?: return
    if (target.querySelector(".document-review") != null || target.querySelector(".document-review-references") != null) return
    val ids = linkedSetOf<String>()
    val retained = linkedSetOf<String>()
    var visited = 0
    fun collect(value: dynamic, depth: Int) {
        if (++visited > 4096 || depth > 8 || value == null || typeOf(value) != "object") return
        if (isArray(value)) { for (item in arr(value)) collect(item, depth + 1); return }
        if (value.previewOnly == true && typeOf(value.retainedCid) == "string" &&
            Regex("^sha256:[0-9a-f]{64}$").matches(str(value.retainedCid))) retained.add(str(value.retainedCid))
        val cid = value.snapshotCid
        if (typeOf(cid) == "string" && Regex("^sha256:[0-9a-f]{64}$").matches(str(cid))) ids.add(str(cid))
        if (isArray(cid)) for (item in arr(cid))
            if (typeOf(item) == "string" && Regex("^sha256:[0-9a-f]{64}$").matches(str(item))) ids.add(str(item))
        for (key in keysOf(value)) if (key != "snapshot" && key != "history" && key != "selections") collect(value[key], depth + 1)
    }
    collect(out, 0)
    if (ids.isEmpty() && retained.isEmpty()) return
    val box = document.createElement("section").asDynamic(); box.className = "document-review-references"
    target.appendChild(box)
    val current = { canvasCurrent() && n._lastOut === out && truthy(n.el.isConnected) && truthy(box.isConnected) }
    val rendered = mutableSetOf<String>()
    fun renderReferences() { for (cid in ids) {
        if (!rendered.add(cid)) continue
        val entry = document.createElement("section").asDynamic(); box.appendChild(entry)
        val button = document.createElement("button").asDynamic()
        button.textContent = "Inspect retained review · " + cid.take(23) + "…"; entry.appendChild(button)
        val content = document.createElement("div").asDynamic(); entry.appendChild(content)
        on(button, "click", { event ->
            event.stopPropagation(); button.disabled = true
            launchJs {
                try {
                    require(current()) { "The source result changed; use its current review references" }
                    val review = reviewRequest("?snapshotCid=" + encodeURIComponent(cid))
                    require(current() && review.snapshotCid == cid) { "The source result changed while reading the review" }
                    content.textContent = ""
                    documentReviewActions(content, review, current)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: dynamic) { status("Review inspection: " + failureText(error)) }
                finally { button.disabled = false }
            }
        })
    } }
    renderReferences()
    for (cid in retained) {
        val button = document.createElement("button").asDynamic()
        button.textContent = "Load review references from retained output"; box.appendChild(button)
        on(button, "click", { event ->
            event.stopPropagation(); button.disabled = true
            launchJs {
                try {
                    require(current()) { "The source result changed; use its current output" }
                    val response = fetchJs("/api/v0/block/get?arg=" + encodeURIComponent(cid))
                    require(truthy(response.ok)) { "Retained output could not be read" }
                    val value = JSON.parse<dynamic>(PatchRead.text(response))
                    require(current()) { "The source result changed while reading retained output" }
                    visited = 0; collect(value, 0); renderReferences()
                    if (ids.isEmpty()) status("This retained output contains no review snapshot references.")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: dynamic) { status("Retained review: " + failureText(error)) }
                finally { button.disabled = false }
            }
        })
    }
}

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
    val current = { n._lastOut === out && truthy(n.el.isConnected) }
    if (typeOf(out.snapshotCid) == "string" && out.snapshot != null && typeOf(out.revision) == "number")
        documentReviewActions(target, out, current)
    if (n.type in arrayOf("book.curate", "book.snapshot") && typeOf(out.curationReceiptCid) == "string")
        documentSnapshotAction(target, "book", str(out.curationReceiptCid), arr(out.relationships), current)
    if (n.type == "document.curate" && typeOf(out.receiptCid) == "string" && isArray(out.reviewSelections))
        documentSnapshotAction(target, "document", str(out.receiptCid), arr(out.reviewSelections), current)
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
