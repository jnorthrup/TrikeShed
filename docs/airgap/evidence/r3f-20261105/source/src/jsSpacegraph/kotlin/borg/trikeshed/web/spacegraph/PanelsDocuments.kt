package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.docs.ReviewQuoteSpan
import borg.trikeshed.docs.reviewQuoteSpan
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException

// Credentials live only in this tab's memory, never in graph JSON, receipts or browser storage.
private var documentReviewCredential: String? = null
private var documentReviewCredentialEpoch = 0
private val documentReviewCredentialListeners = mutableSetOf<() -> Unit>()

private fun updateDocumentReviewCredential(value: String?) {
    documentReviewCredential = value
    documentReviewCredentialEpoch++
    documentReviewCredentialListeners.toList().forEach { it() }
}

private class ConditionEditorRow(
    val id: String,
    val textCid: String,
    val begin: Int,
    val end: Int,
    val quote: String,
    val state: dynamic,
    val spans: MutableList<ReviewQuoteSpan>,
    val hasParserCondition: Boolean,
    var unsupportedSpans: Boolean = false,
)

private class DocumentReviewRequestRefused(val status: Int, val value: dynamic, message: String) : IllegalArgumentException(message)

private fun nativeMap(value: Any?): Map<*, *> = value as? Map<*, *> ?: error("Native inspection record required")
private fun nativeText(value: Any?): String = value as? String ?: error("Native inspection text required")
private fun nativeRows(value: Any?, limit: Int): List<Map<*, *>> {
    val rows = value as? List<*> ?: error("Native inspection rows required")
    require(rows.size <= limit) { "Native inspection row limit" }
    return rows.map(::nativeMap)
}
private fun nativeWhole(value: Any?): Double {
    val number = (value as? Number)?.toDouble() ?: error("Native inspection integer required")
    require(number.isFinite() && number in 0.0..9_007_199_254_740_991.0 && number % 1.0 == 0.0)
    return number
}

/** Cross-bind the host report to the entire displayed approved selection before offering its graph. */
private fun nativeProjection(report: Map<*, *>, approved: Map<*, *>, goal: Map<*, *>): NativeHornProofGraph.Projection {
    require(approved["state"] == "approve" && approved["current"] == true &&
        report["snapshotCid"] == approved["snapshotCid"] && nativeWhole(report["revision"]) == nativeWhole(approved["revision"]) &&
        report["approvalCid"] == approved["decisionCid"] && report["decisionCid"] == approved["decisionCid"] &&
        report["reviewer"] == approved["reviewer"] && report["synthetic"] == approved["synthetic"] &&
        report["query"] == _l[goal["predicate"], goal["subject"], goal["object"]]) { "Native result differs from the approved review and goal" }
    nativeWhole(report["asOf"])
    val checked = report["checkedProof"]?.let(::nativeMap)
    val proof = report["proof"]?.let(::nativeMap)
    require(report["status"] in _s["SUPPORTED", "UNKNOWN"] && (report["status"] == "UNKNOWN") == (proof == null) &&
        (proof == null) == (checked == null)) { "Native proof status and certificate differ" }
    if (checked != null) {
        require(checked["kind"] == "native-horn-checked-proof/1" && checked["firstProofOnly"] == true &&
            checked["verification"] == "supplied-native-certificate-only-not-independent-inference" &&
            checked["referenceMode"] == "normalized-export-atoms-and-rule-scoped-variables-not-source-spans" &&
            checked["mlirNixRevision"] == SumoNixBridge.MLIR_REVISION && report["sumoNixRevision"] == SumoNixBridge.SUMO_REVISION)
        for (key in s_["inputCid", "frameId", "query", "semantics", "sumoNixRevision"].view)
            require(checked[key] == report[key]) { "Native checked frame differs" }
        require((checked["steps"] as? List<*>)?.isNotEmpty() == true) { "Native checked steps required" }
    }
    val selected = nativeRows(nativeMap(approved["snapshot"])["selections"], 123)
    val selections = selected.associateBy { nativeText(it["id"]) }
    require(selected.isNotEmpty() && selected.size == selections.size) { "Distinct frozen native selections required" }
    val evidence = nativeMap(report["sourceEvidence"]).values.map(::nativeMap)
    require(selections.size == evidence.size && evidence.map { it["selectionId"] }.toSet() == selections.keys) { "Native selected evidence differs" }
    for (item in evidence) {
        val quote = nativeMap(selections.getValue(nativeText(item["selectionId"]))["quotation"])
        require(item["originalCid"] == quote["originalCid"] && item["textCid"] == quote["extractedTextCid"] &&
            nativeWhole(item["begin"]) == nativeWhole(quote["begin"]) && nativeWhole(item["end"]) == nativeWhole(quote["end"]) &&
            item["quote"] == quote["quote"]) { "Native quotation differs from the frozen source witness" }
    }
    report["checkedProof"]?.let { raw ->
        val sourceRevision = nativeText(nativeMap(approved["snapshot"])["sourceRevision"])
        for (source in nativeMap(nativeMap(raw)["sources"]).values.map(::nativeMap))
            if (!nativeText(source["id"]).startsWith("host-rule:"))
                require(source["sourceRevision"] == sourceRevision) { "Native source revision differs from the frozen review" }
    }
    return NativeHornProofGraph.project(report)
}

private fun nativeElement(parent: dynamic, tag: String, value: String, attribute: String? = null): dynamic {
    val element = document.createElement(tag).asDynamic()
    element.textContent = value
    if (attribute != null) element.setAttribute(attribute, "")
    parent.appendChild(element)
    return element
}

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
    if (!truthy(response.ok)) throw DocumentReviewRequestRefused(num(response.status).toInt(), result,
        str(result?.detail ?: result?.error ?: "Review request failed"))
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
        else if (!truthy(pane.hasAttribute("data-review-focus-hold"))) {
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
    var savedAssessment: dynamic = null
    var savedAssessmentEpoch = -1
    var reviewerSession: dynamic = null
    var busy = false
    var generation = 0
    val box = document.createElement("section").asDynamic()
    box.className = "document-review"
    target.appendChild(box)
    var credentialChanged: () -> Unit = {}
    val listener: () -> Unit = { if (truthy(box.isConnected)) credentialChanged() }
    documentReviewCredentialListeners.add(listener)
    page.own { documentReviewCredentialListeners.remove(listener) }
    fun render() {
        val rendered = ++generation
        fun present() = current() && reviewFocus.sourceConnected(box) && truthy(box.isConnected) && generation == rendered
        val book = review.snapshot?.kind == "book"
        val kif = review.snapshot?.kind == "taxonomy" || review.snapshot?.kind == "taxonomy-partition"
        val legacyWithdraw = book && review.conditionReviewState == "legacy-unassessed" && review.sourceCurrent == true
        val recoverAssessment = book && review.sourceCurrent == true && review.state == "draft" &&
            typeOf(review.assessmentCid) == "string" && review.assessmentAvailability != "available"
        val mayAssess = review.current == true || recoverAssessment
        var conditionFields: dynamic = null
        var approvalButton: dynamic = null
        var taxonomyResult: dynamic = null
        var nativeQueryEpoch = 0
        var nativeCurrencyUnconfirmed = false
        var savedAssessmentInspectable = false
        fun forgetSavedAssessment() {
            savedAssessment = null
            savedAssessmentEpoch = -1
            if (approvalButton != null) approvalButton.disabled = true
            box.querySelector("[data-condition-export]")?.remove()
            box.querySelector("[data-condition-saved-status]")?.remove()
        }
        fun assessmentMatches(value: dynamic, forDraft: Boolean = false, completeRows: Boolean = false): Boolean {
            if (value == null || review.current != true || value.snapshotCid != review.snapshotCid ||
                value.revision != review.revision || value.assessmentCid != review.assessmentCid ||
                !isArray(value.assessments) || !isArray(review.snapshot?.selections)) return false
            if (forDraft && (review.state != "draft" || value.draftCid != review.decisionCid)) return false
            val selections = arr(review.snapshot.selections)
            val rows = arr(value.assessments)
            if (selections.any { it == null || typeOf(it.id) != "string" } ||
                rows.any { it == null || typeOf(it.selectionId) != "string" || typeOf(it.state) != "string" }) return false
            val ids = selections.map { str(it.id) }
            val selected = rows.map { str(it.selectionId) }
            return ids.isNotEmpty() && ids.distinct().size == ids.size && selected.size <= ids.size &&
                selected.distinct().size == selected.size && ids.toSet().containsAll(selected) &&
                (!completeRows || selected.size == ids.size) &&
                rows.all { it.state in arrayOf("unresolved", "unconditional", "conditional") }
        }
        fun approvalReady(): Boolean = savedAssessmentEpoch == documentReviewCredentialEpoch && savedAssessmentInspectable && assessmentMatches(savedAssessment, forDraft = true, completeRows = true) &&
            savedAssessment.status == "complete" && arr(savedAssessment.assessments).none { it.state == "unresolved" }
        fun exportReady(): Boolean = savedAssessmentEpoch == documentReviewCredentialEpoch && savedAssessmentInspectable && assessmentMatches(savedAssessment, completeRows = true) && savedAssessment.status == "complete" &&
            arr(savedAssessment.assessments).all { it.state == "unconditional" }
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
        identity.textContent = if (reviewerSession != null)
            (if (reviewerSession.synthetic == true) "Synthetic validation reviewer: " else "Authenticated reviewer: ") + str(reviewerSession.reviewer)
            else if (review.synthetic == true) "Synthetic validation decision by " + str(review.reviewer)
            else if (typeOf(review.reviewer) == "string") "Decision by " + str(review.reviewer) else "No authenticated decision yet."
        box.appendChild(identity)
        credentialChanged = {
            reviewerSession = null
            forgetSavedAssessment()
            val nativeResult = taxonomyResult?.hasAttribute("data-taxonomy-native-result") == true
            if (nativeResult) nativeCurrencyUnconfirmed = true
            nativeQueryEpoch++; taxonomyResult?.remove(); taxonomyResult = null
            val nativeRun = box.querySelector("[data-native-run]")
            if (nativeResult && nativeRun != null) nativeRun.disabled = true
            val taxonomyInspect = box.querySelector("[data-taxonomy-inspect]")
            if (nativeResult && taxonomyInspect != null) taxonomyInspect.disabled = true
            identity.textContent = if (nativeResult) "Reviewer credential changed; native currency is unconfirmed. Authenticate and refresh the review."
                else "Reviewer credential changed; authenticate and reload the assessment."
        }
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
        fun button(label: String, operation: suspend () -> Unit): dynamic {
            val button = document.createElement("button").asDynamic()
            button.textContent = label; box.appendChild(button)
            on(button, "click", { event ->
                event.stopPropagation()
                if (busy) return@on
                busy = true; button.disabled = true
                val fields = conditionFields
                val fieldsDisabled = fields?.disabled
                if (fields != null) fields.disabled = true
                launchJs {
                    try { require(present()) { "The source result or review changed; reopen its current review" }; operation() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: dynamic) { status("Review: " + failureText(error)) }
                    finally {
                        busy = false
                        button.disabled = if (button === approvalButton) !approvalReady() else false
                        if (fields != null) fields.disabled = fieldsDisabled
                    }
                }
            })
            return button
        }
        button("Authenticate reviewer") {
            val supplied = str(credential.value).trim()
            if (supplied.isNotEmpty()) {
                updateDocumentReviewCredential(supplied)
                identity.textContent = "Authenticating reviewer…"
            }
            credential.value = ""
            val requestEpoch = documentReviewCredentialEpoch
            val session = reviewRequest("/session", authenticated = true)
            require(present() && requestEpoch == documentReviewCredentialEpoch) {
                "The review or credential changed while authenticating; authenticate again"
            }
            reviewerSession = session
            if (savedAssessment != null) { savedAssessment = null; render(); return@button }
            identity.textContent = (if (session.synthetic == true) "Synthetic validation reviewer: " else "Authenticated reviewer: ") + str(session.reviewer)
        }
        button("Forget credential") {
            updateDocumentReviewCredential(null); credential.value = ""
            if (savedAssessment != null) { savedAssessment = null; render(); return@button }
            identity.textContent = "Reviewer credential removed from this tab."
        }
        button("Refresh review") {
            val refreshed = reviewRequest("?snapshotCid=" + encodeURIComponent(str(review.snapshotCid)))
            require(present()) { "The source result changed while reading the review" }
            savedAssessment = null
            review = refreshed
            render()
        }

        if (book && isArray(review.snapshot?.selections)) {
            val selections = arr(review.snapshot.selections)
            val label = document.createElement("h4")
            label.textContent = "Conditions and exceptions"
            box.appendChild(label)
            val help = document.createElement("p")
            help.textContent = "Each claim starts unresolved. Inspect the retained quotation and choose whether it is unconditional or has conditions or exceptions. A conditional assessment keeps source text for review; it cannot be exported as an executable condition. If a qualifier is outside the displayed quotation, leave it unresolved."
            box.appendChild(help)
            if (assessmentMatches(savedAssessment)) {
                val loaded = document.createElement("p").asDynamic()
                loaded.setAttribute("data-condition-saved-status", "")
                loaded.textContent = "Authenticated saved assessment · " + str(savedAssessment.status) + " · " + str(savedAssessment.assessmentCid)
                box.appendChild(loaded)
            }
            val fields = document.createElement("fieldset").asDynamic()
            fields.setAttribute("data-condition-editor", "")
            fields.disabled = !mayAssess || review.state !in arrayOf("snapshot", "draft", "reject")
            conditionFields = fields
            box.appendChild(fields)
            val rows = mutableListOf<ConditionEditorRow>()
            var valid = selections.isNotEmpty() && selections.size <= 256
            if (!valid) {
                val warning = document.createElement("p")
                warning.textContent = "Choose between 1 and 256 claims for each condition assessment."
                fields.appendChild(warning)
            }
            fun index(value: dynamic): Int? = if (typeOf(value) == "number" && num(value).isFinite() &&
                num(value) >= 0 && num(value) <= Int.MAX_VALUE.toDouble() && num(value) % 1.0 == 0.0) num(value).toInt() else null
            val prior = if (assessmentMatches(savedAssessment)) arr(savedAssessment.assessments) else emptyArray()
            for (selection in selections) {
                if (selection == null) { valid = false; continue }
                val value = selection.value ?: selection
                val quote = selection.quotation ?: value.quotation
                val id = if (typeOf(selection.id) == "string") str(selection.id) else ""
                val sourceCid = str(quote?.extractedTextCid)
                val begin = index(quote?.begin)
                val end = index(quote?.end)
                val quoted = if (typeOf(quote?.quote) == "string") str(quote.quote) else ""
                if (id.isBlank() || !Regex("^sha256:[0-9a-f]{64}$").matches(sourceCid) || begin == null || end == null ||
                    quoted.length > 32768 || reviewQuoteSpan(begin, end, quoted, 0, quoted.length) == null) {
                    valid = false
                    val warning = document.createElement("p")
                    warning.textContent = "A claim has no usable exact extracted-text quotation; choose a different selection."
                    fields.appendChild(warning)
                    continue
                }
                val row = document.createElement("section").asDynamic()
                row.setAttribute("data-condition-selection", id)
                row.style.cssText = "border:1px solid var(--line);padding:8px;margin:8px 0"
                fields.appendChild(row)
                val claim = document.createElement("p")
                claim.textContent = arrayOf(value.subject, value.predicate, value.`object`).filter { it != null }
                    .joinToString(" ") { str(it) } + " · parser condition: " +
                    (if (value.condition == null) "not identified" else str(value.condition)) +
                    " · ontology: " + str(value.ontologyMapping?.status ?: "unresolved")
                row.appendChild(claim)
                val sourceLabel = document.createElement("p")
                sourceLabel.textContent = "Retained extracted text · UTF-16 offsets " + begin + "–" + end +
                    " (end exclusive). Page and layout markers are supplied source metadata."
                row.appendChild(sourceLabel)
                val quotation = document.createElement("pre").asDynamic()
                quotation.setAttribute("data-condition-quote", "")
                quotation.style.cssText = "white-space:pre-wrap;user-select:text;max-height:240px;overflow:auto"
                quotation.textContent = quoted
                row.appendChild(quotation)
                val quoteNode = quotation.firstChild
                documentSourceActions(row, selection, ::present)
                val stateLabel = document.createElement("label").asDynamic()
                stateLabel.textContent = "Assessment "
                val state = document.createElement("select").asDynamic()
                state.setAttribute("data-condition-state", "")
                state.setAttribute("aria-label", "Condition assessment for " + id)
                for ((choice, description) in arrayOf("unresolved" to "Unresolved",
                    "unconditional" to "Unconditional", "conditional" to "Conditional or has exceptions")) {
                    val option = document.createElement("option").asDynamic()
                    option.value = choice; option.textContent = description
                    if (choice == "unconditional" && value.condition != null) option.disabled = true
                    state.appendChild(option)
                }
                stateLabel.appendChild(state); row.appendChild(stateLabel)
                if (value.condition != null) {
                    val warning = document.createElement("p")
                    warning.textContent = "The parser recorded a condition. An unconditional assessment conflicts with this retained claim; correction requires a new reviewed claim."
                    row.appendChild(warning)
                }
                val spanList = document.createElement("div").asDynamic()
                spanList.setAttribute("data-condition-spans", "")
                row.appendChild(spanList)
                val editor = ConditionEditorRow(id, sourceCid, begin, end, quoted, state, mutableListOf(), value.condition != null)
                rows.add(editor)
                val stored = prior.firstOrNull { it.selectionId == id }
                if (stored != null) {
                    state.value = str(stored.state)
                    if (!isArray(stored.spans)) editor.unsupportedSpans = true
                    else for (span in arr(stored.spans)) {
                        if (span == null) { editor.unsupportedSpans = true; continue }
                        val spanBegin = index(span.begin); val spanEnd = index(span.end)
                        val resolved = if (spanBegin != null && spanEnd != null && spanBegin >= begin && spanEnd <= end &&
                            span.textCid == sourceCid) reviewQuoteSpan(begin, end, quoted, spanBegin - begin, spanEnd - begin) else null
                        if (resolved == null || resolved.quote != span.quote) editor.unsupportedSpans = true else editor.spans.add(resolved)
                    }
                    if (state.value == "conditional" && editor.spans.isEmpty() ||
                        state.value != "conditional" && editor.spans.isNotEmpty()) editor.unsupportedSpans = true
                }
                fun showSpans() {
                    spanList.textContent = ""
                    for (span in editor.spans) {
                        val line = document.createElement("p")
                        line.textContent = "Qualifier · " + span.begin + "–" + span.end + " · " + span.quote
                        spanList.appendChild(line)
                    }
                    if (editor.unsupportedSpans) {
                        val warning = document.createElement("p")
                        warning.textContent = "A saved qualifier is outside this quotation. It cannot be edited here; leave this claim unresolved or use another review surface."
                        spanList.appendChild(warning)
                    }
                }
                showSpans()
                on(state, "change", { _ ->
                    if (busy) return@on
                    editor.spans.clear(); editor.unsupportedSpans = false; showSpans(); forgetSavedAssessment()
                })
                fun addSpan(localBegin: Int, localEnd: Int) {
                    require(present()) { "The source result changed; reopen its review" }
                    require(!busy) { "Wait for the current review operation" }
                    require(state.value == "conditional") { "Choose conditional or has exceptions first" }
                    require(editor.spans.size < 16) { "At most 16 qualifiers per claim" }
                    val span = requireNotNull(reviewQuoteSpan(begin, end, quoted, localBegin, localEnd)) {
                        "Select nonempty text without splitting a character"
                    }
                    editor.spans.add(span); showSpans(); forgetSavedAssessment()
                }
                val highlight = document.createElement("button").asDynamic()
                highlight.type = "button"; highlight.textContent = "Use highlighted words"
                highlight.setAttribute("data-condition-add-selection", "")
                row.appendChild(highlight)
                on(highlight, "pointerdown", { event -> event.preventDefault(); event.stopPropagation() })
                on(highlight, "click", { event ->
                    event.stopPropagation()
                    try {
                        val selected = document.asDynamic().getSelection()
                        require(selected != null && selected.rangeCount == 1) { "Highlight words in this quotation first" }
                        val range = selected.getRangeAt(0)
                        require(range.startContainer === quoteNode && range.endContainer === quoteNode) { "Select only words in this quotation" }
                        addSpan(requireNotNull(index(range.startOffset)), requireNotNull(index(range.endOffset)))
                    } catch (error: dynamic) { status("Condition: " + failureText(error)) }
                })
                val full = document.createElement("button").asDynamic()
                full.type = "button"; full.textContent = "Use full quotation"
                full.setAttribute("data-condition-add-full", "")
                row.appendChild(full)
                on(full, "click", { event ->
                    event.stopPropagation()
                    try { addSpan(0, quoted.length) } catch (error: dynamic) { status("Condition: " + failureText(error)) }
                })
                val clear = document.createElement("button").asDynamic()
                clear.type = "button"; clear.textContent = "Clear qualifiers"; row.appendChild(clear)
                on(clear, "click", { event ->
                    if (busy) return@on
                    event.stopPropagation(); editor.spans.clear(); editor.unsupportedSpans = false; showSpans(); forgetSavedAssessment()
                })
            }
            savedAssessmentInspectable = valid && rows.size == selections.size && rows.none {
                it.unsupportedSpans || it.hasParserCondition && it.state.value == "unconditional"
            }
            if (!valid || rows.size != selections.size) fields.disabled = true
            if (mayAssess && review.state in arrayOf("snapshot", "draft", "reject") && valid && rows.size == selections.size) {
                button("Save condition assessment") {
                    val assessments = rows.map { row ->
                        val state = str(row.state.value)
                        require(state in setOf("unresolved", "unconditional", "conditional")) { "Choose an assessment for each claim" }
                        require(state != "conditional" || row.spans.isNotEmpty() && !row.unsupportedSpans) {
                            "Attach a qualifier from each conditional claim's displayed quotation"
                        }
                        require(state == "conditional" || row.spans.isEmpty()) { "Only conditional claims carry qualifiers" }
                        objOf { entry ->
                            entry.selectionId = row.id; entry.state = state
                            entry.spans = row.spans.map { span -> objOf { q ->
                                q.textCid = row.textCid; q.begin = span.begin; q.end = span.end; q.quote = span.quote
                            } }.toTypedArray()
                        }
                    }.toTypedArray()
                    val request = objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision; it.assessments = assessments }
                    require(JSON.stringify(request).length <= 262144) { "Assessment is too large; choose fewer claims or shorter qualifiers" }
                    val requestEpoch = documentReviewCredentialEpoch
                    val updated = reviewRequest("/assess", request, authenticated = true)
                    require(present() && requestEpoch == documentReviewCredentialEpoch) {
                        "Assessment may have been retained, but the source or credential changed; refresh the review"
                    }
                    review = updated; savedAssessment = null
                    try {
                        val loaded = reviewRequest("/assessment", objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision }, authenticated = true)
                        require(present() && requestEpoch == documentReviewCredentialEpoch && assessmentMatches(loaded)) {
                            "Assessment or credential changed while loading; refresh before approval"
                        }
                        savedAssessment = loaded; savedAssessmentEpoch = requestEpoch
                        render()
                        status("Condition assessment retained; inspect it before approval.")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: dynamic) {
                        if (present()) render()
                        status("Assessment retained, but could not be loaded: " + failureText(error))
                    }
                }
            }
            if (review.current == true && typeOf(review.assessmentCid) == "string") {
                button("Load saved assessment") {
                    val requestEpoch = documentReviewCredentialEpoch
                    val loaded = reviewRequest("/assessment", objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision }, authenticated = true)
                    require(present() && requestEpoch == documentReviewCredentialEpoch && assessmentMatches(loaded)) {
                        "Assessment or credential changed while loading; refresh before approval"
                    }
                    savedAssessment = loaded; savedAssessmentEpoch = requestEpoch
                    render()
                }
            }
        }
        for ((label, action) in arrayOf("Save draft" to "draft", "Approve exact selection" to "approve",
            "Reject selection" to "reject", "Correct / withdraw approval" to "correct")) {
            if (legacyWithdraw && action != "draft") continue
            val display = when {
                legacyWithdraw && action == "draft" -> "Withdraw old approval for reassessment"
                book && action == "draft" && review.state in arrayOf("approve", "release") -> "Withdraw current approval for reassessment"
                book && action == "draft" && review.state == "consume" -> "Open a new review draft"
                else -> label
            }
            val control = button(display) {
                require(review.current == true || legacyWithdraw && action == "draft") {
                    "This review is stale; create a snapshot from current evidence"
                }
                if (book && action == "approve") require(approvalReady()) {
                    "Load the exact complete assessment for this draft before approval"
                }
                val request = objOf {
                    it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision
                    it.action = action; it.note = str(note.value)
                    if (book && action == "approve") it.expectedAssessmentCid = savedAssessment.assessmentCid
                }
                val requestEpoch = documentReviewCredentialEpoch
                val decided = reviewRequest("/decision", request, authenticated = true)
                require(present() && requestEpoch == documentReviewCredentialEpoch) {
                    "Decision may have been retained, but the source or credential changed; refresh the review"
                }
                savedAssessment = null
                review = decided
                render()
                status(if (action == "correct") "Approval withdrawn. Correct the source and curate a new snapshot before approval."
                    else "Review " + action + " retained at revision " + str(review.revision))
            }
            if (book && action == "approve") {
                approvalButton = control
                control.disabled = !approvalReady()
            }
        }
        if (book && review.state == "approve" && review.conditionReviewState == "conditional-nonexecutable") {
            val warning = document.createElement("p")
            warning.textContent = "Conditional assessment cannot be exported as an executable relationship."
            box.appendChild(warning)
        } else if (book && review.state == "approve" && review.current == true && !exportReady()) {
            val warning = document.createElement("p")
            warning.textContent = "Load the saved assessment to inspect whether this approval can be exported."
            box.appendChild(warning)
        }
        if (!kif && review.state == "approve" && review.current == true &&
            (!book || review.conditionReviewState != "conditional-nonexecutable" && exportReady())) {
            val export = document.createElement("section").asDynamic()
            if (book) export.setAttribute("data-condition-export", "")
            box.appendChild(export)
            val reference = objOf {
                it.snapshotCid = review.snapshotCid; it.approvalCid = review.decisionCid; it.expectedRevision = review.revision
            }
            val receipt = document.createElement("pre")
            receipt.textContent = JSON.stringify(reference, null, 2)
            receipt.asDynamic().style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
            export.appendChild(receipt)
            val instruction = document.createElement("p")
            instruction.textContent = "Put this reference in the workflow's approvals array, then run export. Export issues and consumes its own one-use permit."
            export.appendChild(instruction)
            for ((label, value) in arrayOf("Copy approval reference" to JSON.stringify(reference),
                "Copy approvals array" to JSON.stringify(arrayOf(reference)))) {
                val copy = document.createElement("button").asDynamic(); copy.textContent = label; export.appendChild(copy)
                on(copy, "click", { event ->
                    event.stopPropagation()
                    if (present() && (!book || exportReady())) copyText(value)
                    else status("Review or credential changed; reload before copying approval references.")
                })
            }
        }
        if (review.current == true && (book && review.state in arrayOf("approve", "release", "consume") ||
                kif && review.state == "approve")) {
            val taxonomy = document.createElement("details").asDynamic()
            taxonomy.setAttribute("data-taxonomy-plan", "")
            val heading = document.createElement("summary")
            heading.textContent = "Inspect taxonomy eligibility"
            taxonomy.appendChild(heading); box.appendChild(taxonomy)
            val help = document.createElement("p")
            help.textContent = "Check this approved selection against a positive ground taxonomy goal. The read does not publish assertions or consume an approval. Book modalities are not asserted taxonomy; KIF assertions are source-scoped. Reduce the page size if the response is too large."
            taxonomy.appendChild(help)
            val predicate = document.createElement("select").asDynamic()
            predicate.setAttribute("aria-label", "Taxonomy predicate")
            for (name in arrayOf("subclass", "instance")) {
                val option = document.createElement("option").asDynamic()
                option.value = name; option.textContent = name; predicate.appendChild(option)
            }
            taxonomy.appendChild(predicate)
            fun term(label: String): dynamic {
                val field = document.createElement("input").asDynamic()
                field.placeholder = label; field.setAttribute("aria-label", label)
                taxonomy.appendChild(field)
                return field
            }
            val subject = term("Taxonomy subject")
            val obj = term("Taxonomy object")
            val pageSize = document.createElement("select").asDynamic()
            pageSize.setAttribute("aria-label", "Taxonomy selections per page")
            for (size in arrayOf(1, 4, 16, 64)) {
                val option = document.createElement("option").asDynamic()
                option.value = size.toString(); option.textContent = "$size per page"; pageSize.appendChild(option)
            }
            pageSize.value = "16"; taxonomy.appendChild(pageSize)
            fun clearTaxonomy() { nativeQueryEpoch++; taxonomyResult?.remove(); taxonomyResult = null }
            for (field in arrayOf(predicate, subject, obj, pageSize)) on(field, "input", { _ -> clearTaxonomy() })
            val inspect = document.createElement("button").asDynamic()
            inspect.textContent = "Inspect reviewed selection"; inspect.setAttribute("data-taxonomy-inspect", ""); taxonomy.appendChild(inspect)
            fun inspectPage(offset: Int) {
                if (busy || nativeCurrencyUnconfirmed) return
                clearTaxonomy()
                busy = true; inspect.disabled = true
                val requestEpoch = documentReviewCredentialEpoch
                val limit = str(pageSize.value).toInt()
                val goal = objOf { it.predicate = str(predicate.value); it.subject = str(subject.value).trim(); it.`object` = str(obj.value).trim() }
                val request = objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision; it.goal = goal; it.offset = offset; it.limit = limit }
                launchJs {
                    try {
                        require(present()) { "The source result or review changed; reopen its current review" }
                        val report = reviewRequest("/taxonomy-plan", request, authenticated = true)
                        require(present() && requestEpoch == documentReviewCredentialEpoch &&
                            report.snapshotCid == review.snapshotCid && report.revision == review.revision &&
                            report.page?.offset == offset && report.page?.limit == limit && str(pageSize.value) == limit.toString() &&
                            report.goal?.predicate == goal.predicate && report.goal?.subject == goal.subject && report.goal?.`object` == goal.`object` &&
                            str(predicate.value) == goal.predicate && str(subject.value).trim() == goal.subject && str(obj.value).trim() == goal.`object`) {
                            "The review, credential or goal changed while reading; inspect again"
                        }
                        val result = document.createElement("section").asDynamic()
                        result.setAttribute("data-taxonomy-result", "")
                        taxonomyResult = result; taxonomy.appendChild(result)
                        val summary = document.createElement("p")
                        val returned = num(report.page.returned).toInt()
                        val range = if (returned == 0) "No selections" else "Selections ${offset + 1}–${offset + returned}"
                        val statusLabel = if (report.coverage == "positive-reviewed-selected-snapshot") " · proof status: " else " · page status: "
                        summary.textContent = range + " of " + str(report.page.total) + statusLabel + str(report.status) +
                            " · mapping: " + str(report.mappingStatus) +
                            " · eligible: " + str(report.counters?.eligible) +
                            " · excluded: " + str(report.counters?.excluded) + " · proofs: " + str(report.counters?.proofs)
                        result.appendChild(summary)
                        if (returned > 0 && typeOf(report.counters?.eligible) == "number") {
                            val meter = document.createElement("meter").asDynamic()
                            meter.min = 0; meter.max = returned; meter.value = report.counters.eligible
                            meter.setAttribute("aria-label", "Eligible selections in this page")
                            result.appendChild(meter)
                        }
                        val scope = document.createElement("p")
                        scope.textContent = if (report.coverage == "positive-reviewed-selected-snapshot")
                            "The proof covers the approved selection; quotations shown here cover this page. It does not cover the whole corpus."
                        else "Results cover this page only, not the whole snapshot or corpus."
                        result.appendChild(scope)
                        for (selection in arr(report.selected)) {
                            val row = document.createElement("details")
                            val label = document.createElement("summary")
                            label.textContent = "Selection " + str(selection.selectionId) +
                                if (arr(selection.exclusionReasons).isEmpty()) " · eligible" else " · excluded"
                            row.appendChild(label)
                            val evidence = document.createElement("pre")
                            evidence.textContent = JSON.stringify(objOf { it.value = selection.value; it.quotation = selection.quotation; it.exclusionReasons = selection.exclusionReasons; it.occurrenceCid = selection.occurrenceCid }, null, 2)
                            evidence.asDynamic().style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
                            row.appendChild(evidence); result.appendChild(row)
                        }
                        if (offset > 0) {
                            val previous = document.createElement("button").asDynamic()
                            previous.textContent = "Previous selections"; result.appendChild(previous)
                            on(previous, "click", { e -> e.stopPropagation(); inspectPage(maxOf(0, offset - limit)) })
                        }
                        if (typeOf(report.page.nextOffset) == "number") {
                            val next = document.createElement("button").asDynamic()
                            next.textContent = "Next selections"; result.appendChild(next)
                            on(next, "click", { e -> e.stopPropagation(); inspectPage(num(report.page.nextOffset).toInt()) })
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: dynamic) { status("Taxonomy: " + failureText(error)) }
                    finally { busy = false; inspect.disabled = false }
                }
            }
            on(inspect, "click", { event -> event.stopPropagation(); inspectPage(0) })
            if (kif) {
                val native = document.createElement("button").asDynamic()
                native.textContent = "Run native SUMO-Nix query"; taxonomy.appendChild(native)
                val nativeHelp = document.createElement("p")
                nativeHelp.textContent = "Runs only on request against the current approved KIF selection, with at most 123 assertions. A timeout or solver failure is not an UNKNOWN result."
                taxonomy.appendChild(nativeHelp)
                native.setAttribute("data-native-run", "")
                fun markNativeUnconfirmed(message: String) {
                    nativeCurrencyUnconfirmed = true; clearTaxonomy()
                    native.disabled = true; inspect.disabled = true
                    nativeHelp.textContent = "Native review currency is unconfirmed. Refresh the review before inspecting again. $message"
                    nativeHelp.setAttribute("data-native-currentness-unconfirmed", "")
                }
                fun inspectNativeResult(result: dynamic, report: Map<*, *>, projection: NativeHornProofGraph.Projection,
                                        approved: Map<*, *>, originalRequest: dynamic, requestEpoch: Int) {
                    val checked = nativeMap(report["checkedProof"])
                    val goalFields = nativeMap(reifyStrict(JSON.stringify(originalRequest.goal)))
                    val derived = nativeRows(checked["steps"], 4096).filter { it["kind"] == "derived" }
                    if (derived.isEmpty()) {
                        nativeElement(result, "p", "This proof is asserted directly; it has no derived substitution step.")
                        return
                    }
                    fun approvedCurrent() = present() && !nativeCurrencyUnconfirmed && requestEpoch == documentReviewCredentialEpoch &&
                        review.state == "approve" && review.current == true && review.snapshotCid == originalRequest.snapshotCid &&
                        review.revision == originalRequest.expectedRevision && review.decisionCid == approved["decisionCid"]
                    fun owned() = approvedCurrent() && taxonomyResult === result && truthy(result.isConnected) &&
                        str(predicate.value) == originalRequest.goal.predicate &&
                        str(subject.value).trim() == originalRequest.goal.subject && str(obj.value).trim() == originalRequest.goal.`object`
                    val controls = nativeElement(result, "section", "", "data-native-inspection")
                    controls.style.cssText = "min-width:0;overflow-wrap:anywhere"
                    nativeElement(controls, "h4", "Inspect this native proof")
                    nativeElement(controls, "p", "Choose a derived step, then inspect its substitutions or premise sources. These rows inspect the first proof; source alternatives are not additional required premises or independent evidence.")
                    nativeElement(controls, "p", "Projection ${projection.projectionCid.value} · graph ${projection.graph}", "data-native-projection")
                    val selector = nativeElement(controls, "select", "", "data-native-step")
                    selector.setAttribute("aria-label", "Native derived proof step")
                    val paths = derived.map { step -> (step["path"] as List<*>).map { nativeWhole(it).toInt() } }
                    for ((index, step) in derived.withIndex()) {
                        val path = paths[index]; val label = if (path.isEmpty()) "root" else path.joinToString(".")
                        val option = nativeElement(selector, "option", "$label · ${nativeText(step["normalForm"])}")
                        option.value = index.toString()
                    }
                    selector.value = "0"; selector.style.cssText = "max-width:100%;display:block;margin:10px 0"
                    val substitutions = nativeElement(controls, "button", "Inspect substitutions", "data-native-bindings")
                    val premises = nativeElement(controls, "button", "Inspect premise sources", "data-native-premises")
                    substitutions.type = "button"; premises.type = "button"
                    val notice = nativeElement(controls, "p", "", "data-native-inspection-status")
                    notice.setAttribute("role", "status")
                    val output = nativeElement(controls, "div", "", "data-native-inspection-output")
                    fun clearInspection() { nativeQueryEpoch++; output.textContent = ""; output.removeAttribute("data-native-query-profile"); notice.textContent = "" }
                    on(selector, "change", { event -> event.stopPropagation(); if (owned()) clearInspection() })
                    fun uncertain(message: String) {
                        if (!owned()) return
                        markNativeUnconfirmed(message)
                    }
                    fun show(answer: NativeHornQueryView.Answer, starter: NativeHornQueryView.Starter) {
                        val value = answer.value; val provenance = nativeMap(value["provenance"])
                        output.setAttribute("data-native-query-profile", if (starter.bindings) "bindings" else "premises")
                        nativeElement(output, "p", "Current approved selection · first proof only · step " +
                            (if (starter.path.isEmpty()) "root" else starter.path.joinToString(".")), "data-native-query-step")
                        nativeElement(output, "p", "Read ${provenance["readCid"]} · approval ${provenance["approvalCid"]} · snapshot ${provenance["snapshotCid"]} · revision ${provenance["revision"]} · checked as of ${provenance["checkedAsOf"]}", "data-native-query-provenance")
                        nativeElement(output, "p", (if (provenance["synthetic"] == true) "Synthetic approval reviewer: " else "Approval reviewer: ") + nativeText(provenance["reviewer"]), "data-native-query-reviewer")
                        nativeElement(output, "p", "Request-local graph ${nativeText(value["graph"])}. The host binds it to the checked review; the physical quad source reports raw matched data without review authority.")
                        val program = nativeElement(output, "details", "", "data-native-query-program")
                        nativeElement(program, "summary", "Inspection-only LCNC program and SPARQL")
                        nativeElement(program, "pre", LcncProgramConfix.toJson(starter.program) + "\n\n" + starter.query).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto"
                        val catalog = nativeMap(checked["sources"]); val evidence = nativeMap(report["sourceEvidence"])
                        val cards = mutableMapOf<String, dynamic>()
                        val sourceCards = document.createElement("section").asDynamic()
                        sourceCards.setAttribute("data-native-source-cards", "")
                        nativeElement(sourceCards, "h5", "Proof source references")
                        for ((key, raw) in catalog) {
                            val id = nativeText(key); val source = nativeMap(raw)
                            val sourceIri = NativeHornProofGraph.sourceIri(projection.graph, id)
                            val card = nativeElement(sourceCards, "article", "", "data-native-source-card")
                            card.setAttribute("data-native-source-node", id); card.setAttribute("data-native-source-iri", sourceIri)
                            card.setAttribute("tabindex", "-1"); cards[sourceIri] = card
                            if (nativeText(source["id"]).startsWith("host-rule:")) {
                                card.setAttribute("data-native-reference-mode", "host-rule-normalized-form")
                                nativeElement(card, "h6", "Normalized host rule · ${nativeText(source["id"])}")
                                nativeElement(card, "pre", nativeText(source["text"])).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
                                nativeElement(card, "p", "Rule content ${source["sourceCid"]} · policy ${source["sourceRevision"]}. This normalized rule has no document quotation offsets.")
                            } else {
                                card.setAttribute("data-native-reference-mode", "reviewed-kif-exact-quotation")
                                val quote = nativeMap(evidence[source["id"]])
                                nativeElement(card, "h6", "Exact reviewed KIF quotation · ${nativeText(quote["selectionId"])}")
                                nativeElement(card, "blockquote", nativeText(quote["quote"]), "data-native-source-quote").style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"
                                nativeElement(card, "p", "Original ${quote["originalCid"]} · extracted text ${quote["textCid"]} · UTF-16 [${quote["begin"]}, ${quote["end"]}) · revision ${source["sourceRevision"]}")
                            }
                            nativeElement(card, "p", "Source node $id · $sourceIri")
                        }
                        val scroll = nativeElement(output, "div", "")
                        scroll.style.cssText = "overflow:auto;max-width:100%"
                        val table = nativeElement(scroll, "table", "", "data-native-query-table")
                        table.style.cssText = "border-collapse:collapse;min-width:1680px;width:100%;table-layout:fixed;font-size:13px"
                        val head = nativeElement(nativeElement(table, "thead", ""), "tr", "")
                        for (name in starter.variables) {
                            val th = nativeElement(head, "th", name)
                            val width = when (name) { "form", "childForm" -> 360; "binding", "use", "child", "source", "sourceCid" -> 240; "value" -> 180; else -> 130 }
                            th.style.cssText = "padding:8px;text-align:left;border-bottom:1px solid var(--rule);width:${width}px"
                        }
                        val body = nativeElement(table, "tbody", "")
                        for (row in answer.rows) {
                            val tr = nativeElement(body, "tr", "", "data-native-query-row")
                            for (name in starter.variables) {
                                val term = nativeMap(row[name]); val cell = nativeElement(tr, "td", "")
                                cell.setAttribute("data-native-query-variable", name); cell.setAttribute("data-rdf-kind", nativeText(term["type"]))
                                term["datatype"]?.let { cell.setAttribute("data-rdf-datatype", nativeText(it)) }
                                cell.style.cssText = "padding:8px;vertical-align:top;border-bottom:1px solid var(--rule);overflow-wrap:anywhere"
                                nativeElement(cell, "p", nativeText(term["type"]) + (term["datatype"]?.let { " · ${nativeText(it)}" } ?: ""))
                                nativeElement(cell, "pre", nativeText(term["value"])).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;font:inherit"
                                if (name == "source") {
                                    val uri = nativeText(term["value"]); val card = cards[uri] ?: error("Native source focus target is unavailable")
                                    val focus = nativeElement(cell, "button", "Inspect this source reference", "data-native-source-focus")
                                    focus.type = "button"; focus.setAttribute("data-native-source-target", uri)
                                    on(focus, "click", { event -> event.stopPropagation(); if (owned() && truthy(card.isConnected)) { card.scrollIntoView(); card.focus() } })
                                }
                            }
                        }
                        output.appendChild(sourceCards)
                        val raw = nativeElement(output, "details", "")
                        nativeElement(raw, "summary", "Typed results, physical source and host provenance")
                        nativeElement(raw, "pre", jsonOf(value)).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto"
                    }
                    fun query(bindings: Boolean) {
                        if (busy || !owned()) return
                        clearInspection(); val ticket = nativeQueryEpoch
                        val index = str(selector.value).toIntOrNull() ?: return
                        if (index !in paths.indices) return
                        val starter = NativeHornQueryView.starter(report, paths[index], bindings)
                        val request = objOf {
                            it.snapshotCid = originalRequest.snapshotCid; it.expectedRevision = originalRequest.expectedRevision; it.goal = originalRequest.goal
                            it.program = JSON.parse<dynamic>(LcncProgramConfix.toJson(starter.program))
                        }
                        busy = true; substitutions.disabled = true; premises.disabled = true; native.disabled = true; inspect.disabled = true
                        notice.textContent = "Checking the current approved proof and its source rows…"
                        launchJs {
                            try {
                                val response = reviewRequest("/taxonomy-query", request, authenticated = true)
                                if (!owned() || ticket != nativeQueryEpoch) return@launchJs
                                val actual = reifyStrict(JSON.stringify(response)) as? Map<*, *> ?: error("Native query response required")
                                nativeProjection(actual, approved, goalFields)
                                val answer = NativeHornQueryView.answer(report, actual, starter)
                                show(answer, starter); notice.textContent = "${answer.rows.size} typed source rows. Row count does not change native support."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (refused: DocumentReviewRequestRefused) {
                                if (owned() && ticket == nativeQueryEpoch) {
                                    val error = refused.value
                                    if (refused.status in _s[400, 413] && error != null && typeOf(error) == "object" &&
                                        error.error == "reviewed_taxonomy_query_refused" && typeOf(error.queryStatus) == "number" &&
                                        num(error.queryStatus) == refused.status.toDouble() && typeOf(error.code) == "string" && typeOf(error.reason) == "string")
                                        notice.textContent = "Inspection refused (${refused.status}, ${str(error.code)}): ${str(error.reason)}. Native proof status is unchanged."
                                    else uncertain(failureText(refused))
                                }
                            } catch (error: dynamic) { if (owned() && ticket == nativeQueryEpoch) uncertain(failureText(error)) }
                            finally {
                                busy = false
                                if (approvedCurrent()) {
                                    native.disabled = false; inspect.disabled = false
                                    if (taxonomyResult === result && truthy(result.isConnected)) { substitutions.disabled = false; premises.disabled = false }
                                }
                            }
                        }
                    }
                    on(substitutions, "click", { event -> event.stopPropagation(); query(true) })
                    on(premises, "click", { event -> event.stopPropagation(); query(false) })
                }
                on(native, "click", { event ->
                    event.stopPropagation()
                    if (!busy && !nativeCurrencyUnconfirmed) {
                        clearTaxonomy()
                        val ticket = nativeQueryEpoch
                        busy = true; native.disabled = true; inspect.disabled = true
                        taxonomyResult = nativeElement(taxonomy, "section", "Checking the current approved native proof…", "data-taxonomy-native-result")
                        val requestEpoch = documentReviewCredentialEpoch
                        val approved = nativeMap(reifyStrict(JSON.stringify(review)))
                        val goal = objOf { it.predicate = str(predicate.value); it.subject = str(subject.value).trim(); it.`object` = str(obj.value).trim() }
                        val goalFields = nativeMap(reifyStrict(JSON.stringify(goal)))
                        val request = objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision; it.goal = goal }
                        launchJs {
                            try {
                                require(present()) { "The source result or review changed; reopen its current review" }
                                val report = reviewRequest("/taxonomy-query", request, authenticated = true)
                                if (!present() || ticket != nativeQueryEpoch || requestEpoch != documentReviewCredentialEpoch) return@launchJs
                                require(str(predicate.value) == goal.predicate && str(subject.value).trim() == goal.subject &&
                                    str(obj.value).trim() == goal.`object`) { "The review or goal changed while running; inspect again" }
                                val unavailable = report.status == "UNAVAILABLE"
                                var profileFailure: String? = null
                                val fields = if (unavailable) null else nativeMap(reifyStrict(JSON.stringify(report)))
                                val projection = if (fields == null) null else try { nativeProjection(fields, approved, goalFields) }
                                    catch (_: NativeHornProofGraph.ProfileRefused) {
                                        profileFailure = "The raw native response remains visible below; optional visual validation exceeds this profile."
                                        null
                                    }
                                taxonomyResult?.remove()
                                val result = nativeElement(taxonomy, "section", "", "data-taxonomy-native-result")
                                taxonomyResult = result
                                nativeElement(result, "p", if (unavailable) "Native query unavailable: " + str(report.reason)
                                    else if (profileFailure != null) "Native report received; proof status is unvalidated in this view."
                                    else "Native proof status: " + str(report.status) + " · approved assertions: " +
                                        str(report.selectionCount) + " / " + str(report.selectionLimit))
                                if (!unavailable) {
                                    nativeElement(result, "p", if (profileFailure == null)
                                        "Positive support over this approved selection only; UNKNOWN is not a refutation. The solver returns its first proof and does not publish facts or consume a review permit."
                                        else "This raw response is shown for inspection; its proof did not pass the optional bounded projection in this view.")
                                    nativeElement(result, "p", "Read ${str(report.readCid)} · approval ${str(report.approvalCid)} · revision ${str(report.revision)} · checked as of ${str(report.asOf)}")
                                    nativeElement(result, "p", (if (report.synthetic == true) "Synthetic approval reviewer: " else "Approval reviewer: ") + str(report.reviewer))
                                    if (projection != null && report.status == "SUPPORTED")
                                        inspectNativeResult(result, requireNotNull(fields), projection, approved, request, requestEpoch)
                                    profileFailure?.let { nativeElement(result, "p", it, "data-native-inspection-limit") }
                                    val evidence = nativeElement(result, "details", "")
                                    nativeElement(evidence, "summary", "Native result, proof and selected evidence")
                                    nativeElement(evidence, "pre", JSON.stringify(report, null, 2)).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: dynamic) {
                                if (present() && ticket == nativeQueryEpoch && requestEpoch == documentReviewCredentialEpoch) {
                                    markNativeUnconfirmed(failureText(error))
                                    status("Native taxonomy query failed: " + failureText(error))
                                }
                            } finally {
                                busy = false
                                if (present() && !nativeCurrencyUnconfirmed) { native.disabled = false; inspect.disabled = false }
                            }
                        }
                    }
                })
                val union = document.createElement("details").asDynamic()
                val unionTitle = document.createElement("summary"); unionTitle.textContent = "Query independently approved snapshots"
                union.appendChild(unionTitle); taxonomy.appendChild(union)
                val unionHelp = document.createElement("p")
                unionHelp.textContent = "Combine up to 16 current approved snapshot references. If more than 123 assertions are reviewed, a bounded candidate slice is sent to the native query. Each approval remains separate; overlapping source occurrences are refused. This does not cover the whole corpus."
                union.appendChild(unionHelp)
                val reference = objOf { it.snapshotCid = review.snapshotCid; it.expectedRevision = review.revision }
                val copy = document.createElement("button").asDynamic(); copy.textContent = "Copy this review reference"; union.appendChild(copy)
                on(copy, "click", { event -> event.stopPropagation(); if (present()) copyText(JSON.stringify(reference)) })
                val references = document.createElement("textarea").asDynamic()
                references.setAttribute("aria-label", "Approved snapshot references as JSON array")
                references.value = JSON.stringify(arrayOf(reference), null, 2)
                union.appendChild(references)
                val chainLabel = document.createElement("label")
                val chain = document.createElement("input").asDynamic(); chain.type = "checkbox"
                chainLabel.appendChild(chain); chainLabel.appendChild(document.createTextNode(" Compose native-verified segments for paths up to 256 edges"))
                union.appendChild(chainLabel)
                val run = document.createElement("button").asDynamic(); run.textContent = "Run combined native query"; union.appendChild(run)
                on(run, "click", { event ->
                    event.stopPropagation()
                    if (!busy) {
                        clearTaxonomy(); busy = true; run.disabled = true
                        val epoch = documentReviewCredentialEpoch
                        val text = str(references.value)
                        val compose = chain.checked == true
                        val goal = objOf { it.predicate = str(predicate.value); it.subject = str(subject.value).trim(); it.`object` = str(obj.value).trim() }
                        launchJs {
                            try {
                                require(present()) { "The source result or review changed; reopen its current review" }
                                val refs = JSON.parse<dynamic>(text)
                                require(isArray(refs) && arr(refs).size in 1..16 && arr(refs).any {
                                    it?.snapshotCid == review.snapshotCid && it?.expectedRevision == review.revision
                                }) { "Enter a JSON array of up to 16 references including this review" }
                                val result = reviewRequest(if (compose) "/taxonomy-chain" else "/taxonomy-union", objOf { it.references = refs; it.goal = goal }, authenticated = true)
                                require(present() && epoch == documentReviewCredentialEpoch && str(references.value) == text &&
                                    (chain.checked == true) == compose &&
                                    str(predicate.value) == goal.predicate && str(subject.value).trim() == goal.subject &&
                                    str(obj.value).trim() == goal.`object`) { "The review, credential, references or goal changed while running" }
                                if (result.status != "UNAVAILABLE") {
                                    val query = arr(result.query); val reads = arr(result.reads); val submitted = arr(refs)
                                    require(result.kind == (if (compose) "reviewed-kif-composite-result/1" else "reviewed-kif-union-result/1") &&
                                        (if (compose) result.status == "SUPPORTED" else result.status in arrayOf("SUPPORTED", "UNKNOWN")) &&
                                        query.size == 3 && query[0] == goal.predicate && query[1] == goal.subject && query[2] == goal.`object` &&
                                        reads.size == submitted.size && reads.indices.all {
                                            reads[it].snapshotCid == submitted[it].snapshotCid && reads[it].revision == submitted[it].expectedRevision
                                        }) { "Native result does not match the submitted reviews and goal" }
                                }
                                val section = document.createElement("section").asDynamic(); taxonomyResult = section; union.appendChild(section)
                                val summary = document.createElement("p")
                                summary.textContent = if (result.status == "UNAVAILABLE") "Native query unavailable: " + str(result.reason)
                                    else if (compose) "Native-verified segments: " + str(result.segmentCount) + " · reviewed assertions: " + str(result.reviewedSelectionCount)
                                    else "Native proof status: " + str(result.status) + " · queried assertions: " + str(result.selectionCount) + " / " + str(result.selectionLimit) +
                                        " · reviewed: " + str(result.reviewedSelectionCount) + " · selection: " + str(result.projection)
                                section.appendChild(summary)
                                if (result.status != "UNAVAILABLE") {
                                    val scope = document.createElement("p")
                                    scope.textContent = if (compose) "The host composes individually verified native proofs. Derived intermediates are not reviewed source assertions."
                                        else "Proofs apply to the submitted reviewed assertions. UNKNOWN is not a refutation or a complete corpus result."
                                    section.appendChild(scope)
                                    val evidence = document.createElement("pre")
                                    evidence.textContent = JSON.stringify(objOf { it.reads = result.reads; it.proof = result.proof; it.sourceEvidence = result.sourceEvidence
                                        it.hostRules = result.hostRules; it.inputCid = result.inputCid; it.frameId = result.frameId; it.asOf = result.asOf }, null, 2)
                                    evidence.asDynamic().style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere"; section.appendChild(evidence)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: dynamic) { status("Combined taxonomy query failed: " + failureText(error)) }
                            finally { busy = false; run.disabled = false }
                        }
                    }
                })
            }
        }
        if (review.current != true) {
            val warning = document.createElement("p")
            warning.textContent = when {
                legacyWithdraw -> "This prior approval lacks a condition assessment. Its source is current; withdraw the old approval to reassess it."
                recoverAssessment -> "The saved assessment is unavailable or invalid. The source is current; record a new assessment before approval."
                else -> "This snapshot or source is no longer current. Decisions and release are refused."
            }
            box.appendChild(warning)
        }
    }
    render()
    reviewFocus.register(box, current)
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
    selections: Array<dynamic>, current: () -> Boolean,
    basket: MutableSet<String>? = null, basketCurrent: (() -> Boolean)? = null,
    basketChanged: (() -> Unit)? = null, basketBusy: ((Boolean) -> Unit)? = null,
    reviewTarget: dynamic = null, reviewCurrent: (() -> Boolean)? = null,
    frozen: ((dynamic) -> Unit)? = null) {
    if (selections.isEmpty() && basket.isNullOrEmpty()) return
    documentReviewPane(target)
    val box = document.createElement("section").asDynamic()
    target.appendChild(box)
    val title = document.createElement("h4")
    val kif = kind == "taxonomy" || kind == "taxonomy-partition"
    title.textContent = if (kif) "Choose exact KIF assertions for analyst review" else "Choose material for analyst review"
    box.appendChild(title)
    if (kif) {
        val note = document.createElement("p")
        note.textContent = "Only top-level, positive, ground subclass and instance forms are offered. Approval covers the selected source assertions, not the truth of the full corpus."
        box.appendChild(note)
    }
    val entries = document.createElement("div").asDynamic()
    entries.className = "document-review-choices"
    entries.style.cssText = "max-height:320px;overflow:auto"
    box.appendChild(entries)
    val choices = selections.map { selection ->
        val label = document.createElement("label").asDynamic()
        label.style.cssText = "display:flex;align-items:flex-start;gap:8px;margin:6px 0"
        val input = document.createElement("input").asDynamic(); input.type = "checkbox"
        val id = str(selection.id)
        if (basket != null) {
            input.checked = id in basket
            on(input, "change", { event ->
                event.stopPropagation()
                if (!current() || basketCurrent?.invoke() == false) {
                    input.checked = false
                    status("The source or reviewer credential changed; load candidates again.")
                } else if (input.checked == true) {
                    if (basket.size >= 256 && id !in basket) {
                        input.checked = false
                        status("A review snapshot can contain at most 256 assertions.")
                    } else basket.add(id)
                } else basket.remove(id)
                basketChanged?.invoke()
            })
        }
        label.appendChild(input)
        val caption = document.createElement("span")
        val value = selection.value ?: selection
        val relation = arrayOf(value.subject, value.predicate, value.`object`).filter { it != null }.joinToString(" ") { str(it) }
        val mapping = value.ontologyMapping?.status
        val classes = if (isArray(value.ontologyMapping?.classes)) arr(value.ontologyMapping.classes).joinToString(", ") { str(it) } else ""
        caption.textContent = (if (typeOf(selection.partition) == "number") "Partition " + str(selection.partition) + " · " else "") +
            str(selection.label ?: relation.takeIf { it.isNotBlank() } ?: selection.id) +
            (if (mapping == null) "" else " [" + str(mapping) + (if (classes.isBlank()) "" else ": " + classes) + "]") +
            " · " + str(selection.quotation?.quote ?: value.quotation?.quote ?: value.sourceEvidence?.quote ?: selection.quote ?: "")
        label.appendChild(caption); entries.appendChild(label)
        documentSourceActions(entries, selection, current)
        id to input
    }
    val partition = if (kind == "taxonomy") {
        val label = document.createElement("label").asDynamic()
        val toggle = document.createElement("input").asDynamic(); toggle.type = "checkbox"
        label.appendChild(toggle)
        val text = document.createElement("span")
        text.textContent = " Review one numbered partition separately (at most 32 assertions; selections must share its number)."
        label.appendChild(text); box.appendChild(label); toggle
    } else null
    val button = document.createElement("button").asDynamic()
    button.textContent = "Freeze selected evidence for review"; box.appendChild(button)
    on(button, "click", { event ->
        event.stopPropagation(); button.disabled = true
        choices.forEach { it.second.disabled = true }
        basketBusy?.invoke(true)
        launchJs {
            try {
                require(current() && basketCurrent?.invoke() != false) { "The source result or reviewer credential changed; load candidates again" }
                val selected = basket?.toList() ?: choices.filter { it.second.checked == true }.map { it.first }
                require(selected.isNotEmpty()) { "Select at least one relationship or rule" }
                val selectedKind = if (partition?.checked == true) "taxonomy-partition" else kind
                require(selected.size <= (if (selectedKind == "taxonomy-partition") 32 else 256)) { "Review selection exceeds its limit" }
                val review = reviewRequest("/snapshot", objOf {
                    it.kind = selectedKind; it.receiptCid = receiptCid; it.selection = selected.toTypedArray()
                })
                require(current() && basketCurrent?.invoke() != false) { "The source result or reviewer credential changed while retaining the snapshot; reopen its review" }
                if (frozen != null) frozen(review) else {
                    if (reviewTarget != null) reviewTarget.textContent = ""
                    documentReviewActions(reviewTarget ?: target, review, reviewCurrent ?: current)
                }
                status("Exact evidence selection retained for analyst review.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: dynamic) {
                if (basket != null) {
                    basket.clear(); choices.forEach { it.second.checked = false }; basketChanged?.invoke()
                }
                status("Review snapshot: " + failureText(error) + if (basket != null) "; selection cleared" else "")
            }
            finally {
                button.disabled = false
                choices.forEach { it.second.disabled = false }
                basketBusy?.invoke(false)
            }
        }
    })
}

private fun Panels.documentTaxonomyPages(target: dynamic, receiptCid: String, initial: Array<dynamic>, current: () -> Boolean) {
    val section = document.createElement("section").asDynamic(); target.appendChild(section)
    val note = document.createElement("p")
    note.textContent = "Choose assertions across pages for one snapshot: at most 256 per review, or 123 for a native query. Changing credentials clears the selection. Regular snapshots replace this source's previous regular review; separately approved numbered partitions can be combined for a bounded native query."
    section.appendChild(note)
    val basket = linkedSetOf<String>()
    val summary = document.createElement("p"); section.appendChild(summary)
    fun updateSummary() { summary.textContent = "Selected " + basket.size + " of 256 assertions; native queries support at most 123." }
    updateSummary()
    val credential = document.createElement("input").asDynamic()
    credential.type = "password"; credential.placeholder = "Reviewer credential (if not already authenticated)"
    section.appendChild(credential)
    val offset = document.createElement("input").asDynamic()
    offset.type = "number"; offset.min = "0"; offset.value = "256"; offset.setAttribute("aria-label", "Candidate offset")
    section.appendChild(offset)
    val limit = document.createElement("input").asDynamic()
    limit.type = "number"; limit.min = "1"; limit.max = "64"; limit.value = "8"; limit.setAttribute("aria-label", "Candidate page size")
    section.appendChild(limit)
    val load = document.createElement("button").asDynamic(); load.textContent = "Load KIF candidates"; section.appendChild(load)
    val clear = document.createElement("button").asDynamic(); clear.textContent = "Clear selection"; section.appendChild(clear)
    val pageResult = document.createElement("div").asDynamic(); section.appendChild(pageResult)
    val reviewResult = document.createElement("div").asDynamic(); section.appendChild(reviewResult)
    var pageEpoch = 0
    var credentialEpoch = documentReviewCredentialEpoch
    var shown = initial
    var caption = "First " + initial.size + " candidates"
    fun present() = current() && truthy(section.isConnected) && credentialEpoch == documentReviewCredentialEpoch
    fun render() {
        pageEpoch++
        val shownEpoch = pageEpoch
        pageResult.textContent = ""
        val count = document.createElement("p"); count.textContent = caption; pageResult.appendChild(count)
        documentSnapshotAction(pageResult, "taxonomy", receiptCid, shown,
            { present() && pageEpoch == shownEpoch && truthy(pageResult.isConnected) },
            basket, ::present, ::updateSummary, { busy -> load.disabled = busy; clear.disabled = busy },
            reviewResult, { current() && truthy(section.isConnected) && truthy(reviewResult.isConnected) })
    }
    render()
    val listener: () -> Unit = {
        if (truthy(section.isConnected)) {
            basket.clear(); updateSummary(); pageEpoch++
            credentialEpoch = documentReviewCredentialEpoch
            shown = emptyArray()
            caption = "Load candidates again."
            pageResult.textContent = "Reviewer credential changed. Load candidates again; the selection was cleared."
        }
    }
    documentReviewCredentialListeners.add(listener)
    page.own { documentReviewCredentialListeners.remove(listener) }
    on(clear, "click", { event ->
        event.stopPropagation()
        if (present()) { basket.clear(); updateSummary(); render() }
    })
    on(load, "click", { event ->
        event.stopPropagation(); load.disabled = true; clear.disabled = true
        val supplied = str(credential.value).trim()
        if (supplied.isNotEmpty()) updateDocumentReviewCredential(supplied)
        credential.value = ""
        val epoch = documentReviewCredentialEpoch
        pageEpoch++
        pageResult.textContent = "Loading candidates…"
        launchJs {
            try {
                require(current()) { "The source result changed; use its current receipt" }
                val start = num(offset.value); val size = num(limit.value)
                require(start.isFinite() && start >= 0 && start % 1.0 == 0.0 && start <= 65536 &&
                    size.isFinite() && size >= 1 && size <= 64 && size % 1.0 == 0.0) { "Enter a valid offset and page size" }
                val result = reviewRequest("/taxonomy-candidates", objOf {
                    it.receiptCid = receiptCid; it.offset = start; it.limit = size
                }, authenticated = true)
                require(current() && epoch == documentReviewCredentialEpoch && result.receiptCid == receiptCid) {
                    "The source result or credential changed while reading candidates"
                }
                shown = arr(result.selections)
                caption = "Candidates " + str(start) + "–" + str(start + num(result.selections.length)) + " of " + str(result.total) + " (end exclusive)."
                render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: dynamic) {
                basket.clear(); updateSummary()
                if (epoch == documentReviewCredentialEpoch) pageResult.textContent = "Could not load candidates. Selection cleared; check the source and reviewer credential, then retry."
                status("KIF candidates: " + failureText(error))
            }
            finally { load.disabled = false; clear.disabled = false }
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
    if (n.type == "document.curate" && typeOf(out.receiptCid) == "string" && isArray(out.taxonomySelections)) {
        if (out.taxonomyStatus == "truncated-first-256") {
            val note = document.createElement("p")
            note.textContent = "The first 256 KIF assertions are shown below; later assertions can be browsed by offset."
            target.appendChild(note)
            documentTaxonomyPages(target, str(out.receiptCid), arr(out.taxonomySelections), current)
        } else if (out.taxonomyStatus == "invalid-or-over-limit") {
            val note = document.createElement("p")
            note.textContent = "This KIF source could not be inspected within the supported syntax and size limits."
            target.appendChild(note)
        } else if (out.taxonomyStatus == "original-mismatch") {
            val note = document.createElement("p")
            note.textContent = "The extracted text differs from the original KIF bytes, so it cannot be offered as an exact KIF source."
            target.appendChild(note)
        }
        if (out.taxonomyStatus != "truncated-first-256")
            documentSnapshotAction(target, "taxonomy", str(out.receiptCid), arr(out.taxonomySelections), current)
    }
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
