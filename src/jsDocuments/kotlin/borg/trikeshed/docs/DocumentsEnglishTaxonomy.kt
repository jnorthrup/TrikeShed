package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.ReviewedTaxonomyProofPaths
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/** Explicit declarations and the existing review ledger; credentials live only in this controller. */
internal class DocumentsEnglishTaxonomy(private val inspection: Map<*, *>, parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val inspectionCid = inspection["receiptCid"] as String
    private lateinit var host: HTMLElement
    private var current: () -> Boolean = { false }
    private var abort: dynamic = null
    private var epoch = 0
    private var pending = false
    private var credential = ""
    private var reviewer: String? = null
    private var syntheticReviewer = false
    private var contextIri = ""
    private var candidates: Map<*, *>? = null
    private val declarations = mutableListOf<Declaration>()
    private var prepared: Map<*, *>? = null
    private val selectedKif = mutableSetOf<String>()
    private var review: Map<*, *>? = null
    private var reviewUncertain = false
    private var report: Map<*, *>? = null
    private var note = ""
    private var goalPredicate = ""
    private var goalSubject = ""
    private var goalObject = ""
    private var message: String? = null
    private var results: HTMLElement? = null

    private class Declaration(val candidate: Map<*, *>) {
        val id = candidate["id"] as String
        var selected = false
        var relation = ""
        var subjectIri = ""
        var subjectSort = ""
        var objectIri = ""
        var objectSort = ""
        fun fields(): Map<String, Any?> = _m["id" j id, "relation" j relation,
            "subjectIri" j subjectIri, "subjectSort" j subjectSort, "objectIri" j objectIri, "objectSort" j objectSort]
    }

    fun mount(target: HTMLElement, selected: () -> Boolean) { host = target; current = selected; render() }
    fun close() { epoch++; abort?.abort(); credential = ""; reviewer = null; job.cancel(); if (::host.isInitialized) host.textContent = "" }
    private fun present() = job.isActive && current() && ::host.isInitialized && host.asDynamic().isConnected == true
    private fun active(ticket: Int) = present() && epoch == ticket
    private fun cid(value: Any?) = value is String && Regex("^sha256:[0-9a-f]{64}$").matches(value)
    private fun rows(value: Any?, maximum: Int = 32): List<Map<*, *>> {
        val list = value as? List<*> ?: error("Missing review rows")
        require(list.size <= maximum)
        return list.map { it as? Map<*, *> ?: error("Invalid review row") }
    }
    private fun integer(value: Any?): Int {
        val n = (value as? Number)?.toDouble() ?: error("Missing review ordinal")
        require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n == n.toInt().toDouble())
        return n.toInt()
    }
    private fun text(parent: HTMLElement, tag: String, value: String, attribute: String? = null): HTMLElement =
        (document.createElement(tag) as HTMLElement).also {
            it.textContent = value; if (attribute != null) it.setAttribute(attribute, "1"); parent.appendChild(it)
        }
    private fun fields(parent: HTMLElement, minimum: String = "220px"): HTMLElement = text(parent, "div", "").also {
        it.style.cssText = "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,$minimum),1fr));gap:12px 16px;margin:12px 0;min-width:0"
    }
    private fun actions(parent: HTMLElement): HTMLElement = text(parent, "div", "").also {
        it.style.cssText = "display:flex;flex-wrap:wrap;align-items:center;gap:10px;margin:14px 0"
    }
    private fun code(parent: HTMLElement, value: String): HTMLElement = text(parent, "pre", value).also {
        it.className = "ds-cid"
        it.style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;margin:10px 0;padding:12px 14px;background:var(--side);border:1px solid var(--rule);border-radius:5px"
    }
    private fun evidence(parent: HTMLElement, title: String, value: Any?) {
        val details = text(parent, "details", ""); details.className = "ds-curate-source"
        text(details, "summary", title)
        text(details, "pre", jsonOf(value)).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto"
    }
    private fun witness(parent: HTMLElement, value: Map<*, *>) {
        val english = value["englishQuotation"] as Map<*, *>
        val kif = value["kifQuotation"] as Map<*, *>
        text(parent, "p", "English source").className = "ds-curate-label"
        text(parent, "blockquote", english["quote"] as String).also {
            it.className = "ds-curate-quote"; it.style.whiteSpace = "pre-wrap"
        }
        text(parent, "p", "Declared KIF translation").className = "ds-curate-label"
        code(parent, kif["quote"] as String)
        evidence(parent, "Exact source spans, declared mappings and full parser bindings", value)
    }
    private fun button(parent: HTMLElement, label: String, disabled: Boolean = false, action: () -> Unit): HTMLElement =
        text(parent, "button", label).also {
            it.className = "ds-curate-submit"
            it.style.cssText = "margin:0;max-width:100%;white-space:normal;text-align:left"
            it.setAttribute("type", "button"); it.asDynamic().disabled = disabled || pending
            it.addEventListener("click", { if (present() && !pending) action() })
        }
    private fun input(parent: HTMLElement, label: String, value: String, password: Boolean = false, change: (String) -> Unit): HTMLInputElement {
        val holder = text(parent, "label", label)
        holder.className = "ds-curate-label"; holder.style.cssText = "display:flex;flex-direction:column;gap:6px;min-width:0;margin:0"
        val field = document.createElement("input") as HTMLInputElement
        field.type = if (password) "password" else "text"; field.value = value
        field.maxLength = if (password) 4096 else 256; field.disabled = pending
        field.setAttribute("aria-label", label); field.autocomplete = "off"
        field.className = "ds-curate-name"; field.style.cssText = "width:100%;min-width:0;box-sizing:border-box"
        holder.appendChild(field); field.addEventListener("input", { if (present()) change(field.value) })
        return field
    }
    private fun choice(parent: HTMLElement, label: String, value: String, options: Series<String>, change: (String) -> Unit) {
        val holder = text(parent, "label", label)
        holder.className = "ds-curate-label"; holder.style.cssText = "display:flex;flex-direction:column;gap:6px;min-width:0;margin:0"
        val field = document.createElement("select") as HTMLSelectElement
        for (option in (s_[""] + options).view) {
            val element = document.createElement("option")
            element.setAttribute("value", option); element.textContent = if (option.isEmpty()) "Choose…" else option
            field.appendChild(element)
        }
        field.className = "ds-curate-name"; field.style.cssText = "width:100%;min-width:0;box-sizing:border-box;min-height:40px"
        field.value = value; field.disabled = pending; field.setAttribute("aria-label", label); holder.appendChild(field)
        field.addEventListener("change", { if (present()) change(field.value) })
    }
    private fun invalidate() {
        epoch++; abort?.abort(); abort = null; pending = false
        prepared = null; selectedKif.clear(); review = null; reviewUncertain = false; report = null
        message = "No approved support · UNKNOWN. Prepare and review the edited declarations."
        results?.let { renderResults(it) }
    }
    private suspend fun request(suffix: String, body: Map<*, *>?, ticket: Int): Map<*, *> {
        check(active(ticket) && credential.isNotBlank())
        abort = js("new AbortController()")
        val init = RequestInit(method = if (body == null) "GET" else "POST", body = body?.let(::jsonOf),
            headers = Headers().apply { append("Authorization", "Bearer " + credential); if (body != null) append("Content-Type", "application/json") })
        init.asDynamic().signal = abort.signal
        val response = window.fetch("/api/documents/review" + suffix, init).await()
        val bodyText = response.text().await()
        require(active(ticket) && bodyText.length <= 4 * 1024 * 1024 && bodyText.encodeToByteArray().size <= 4 * 1024 * 1024)
        val value = reifyStrict(bodyText) as? Map<*, *> ?: error("Invalid review response")
        require(response.ok && value["error"] == null)
        return value
    }
    private fun run(failure: String, operation: suspend (Int) -> Unit) {
        if (!present() || pending) return
        val ticket = ++epoch; pending = true; message = null; render()
        scope.launch {
            try { operation(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { if (active(ticket)) { report = null; reviewUncertain = review != null; message = failure } }
            finally { if (active(ticket)) { abort = null; pending = false; render() } }
        }
    }
    private fun loadCandidates() = run("Reviewer authentication or candidate inspection failed. Check the credential and current source, then try again.") { ticket ->
        val session = request("/session", null, ticket)
        require(session["reviewer"] is String)
        val value = request("/english-taxonomy-candidates", _m["inspectionCid" j inspectionCid], ticket)
        require(value["inspectionCid"] == inspectionCid && value["eligible"] is Boolean && value["profile"] is String)
        val sentences = rows(value["sentences"], 512)
        require(value["eligible"] != true || sentences.size <= 32)
        val originals = rows(inspection["sentences"], 512).associateBy { integer(it["index"]) }
        val ids = mutableSetOf<String>()
        for (row in sentences) {
            val index = integer(row["sentenceIndex"]); val original = requireNotNull(originals[index])
            require(row["id"] == "sentence:$index" && ids.add(row["id"] as String) && row["eligible"] is Boolean)
            require(s_["begin", "end", "quote"].view.all { row[it] == original[it] })
            if (row["eligible"] == true) {
                val nodeIds = DocumentsSyntaxGraph.nodes(original).map { it["id"] }.toSet()
                require(s_["subjectNode", "predicateNode", "copulaNode"].view.all { row[it] is String && row[it] in nodeIds })
                val edges = rows(original["dependencies"], 16_384)
                val bindings = rows(row["bindings"], 16); require(bindings.isNotEmpty())
                require(bindings.all { binding -> edges.any { edge -> s_["governorNode", "dependentNode", "relation"].view.all { edge[it] == binding[it] } } })
            }
        }
        val previous = declarations.associateBy { it.id }
        reviewer = session["reviewer"] as String; syntheticReviewer = session["synthetic"] == true; candidates = value; declarations.clear()
        for (row in sentences.take(32)) declarations.add(Declaration(row).also { declaration ->
            previous[declaration.id]?.takeIf { it.candidate == row }?.let { old ->
                declaration.selected = old.selected; declaration.relation = old.relation
                declaration.subjectIri = old.subjectIri; declaration.subjectSort = old.subjectSort
                declaration.objectIri = old.objectIri; declaration.objectSort = old.objectSort
            }
        })
        prepared = null; selectedKif.clear(); review = null; reviewUncertain = false; report = null
    }
    private fun prepare() = run("Translation could not be prepared. Check every explicit declaration and the current source; nothing was approved.") { ticket ->
        val chosen = declarations.filter { it.selected }
        require(reviewer != null && candidates?.get("eligible") == true && chosen.isNotEmpty() && chosen.size <= 32 && contextIri.isNotBlank() && contextIri == contextIri.trim())
        val sorts = mutableMapOf<String, String>()
        for (row in chosen) {
            require(row.candidate["eligible"] == true && row.relation in _s["instance", "subclass"] && row.objectSort == "class")
            require(row.subjectSort == if (row.relation == "instance") "individual" else "class")
            for ((iri, sort) in s_[row.subjectIri j row.subjectSort, row.objectIri j row.objectSort].view) {
                require(iri.length in 1..256 && iri == iri.trim() && (sorts[iri] == null || sorts[iri] == sort)); sorts[iri] = sort
            }
        }
        val value = request("/english-taxonomy", _m["inspectionCid" j inspectionCid, "contextIri" j contextIri,
            "declarations" j chosen.map { it.fields() }], ticket)
        require(s_["translationCid", "derivedKifCid", "receiptCid"].view.all { cid(value[it]) } && value["reviewStatus"] == "unreviewed" && value["contextIri"] == contextIri)
        require(value["sourceRevision"] is String && value["contextKey"] is String)
        val kif = value["kif"] as? String ?: error("Missing derived KIF"); require(kif.length <= 65_536 && kif.encodeToByteArray().size <= 65_536)
        val selections = rows(value["selections"]); require(selections.size == chosen.size)
        require(selections.map { it["id"] }.distinct().size == selections.size)
        for (selection in selections) {
            val declared = selection["declared"] as? Map<*, *> ?: error("Missing declared translation")
            val row = chosen.single { it.id == declared["id"] }; require(declared == row.fields())
            val english = selection["englishQuotation"] as? Map<*, *> ?: error("Missing English quotation")
            val derived = selection["kifQuotation"] as? Map<*, *> ?: error("Missing KIF quotation")
            require(s_["begin", "end", "quote"].view.all { english[it] == row.candidate[it] } && selection["bindings"] == row.candidate["bindings"])
            require(english["originalCid"] == inspection["originalCid"] && english["extractedTextCid"] == inspection["extractedTextCid"])
            val begin = integer(derived["begin"]); val end = integer(derived["end"])
            require(begin < end && end <= kif.length && derived["quote"] == kif.substring(begin, end) && selection["id"] == "kif:$begin:$end")
            require(derived["originalCid"] == value["derivedKifCid"] && derived["extractedTextCid"] == value["derivedKifCid"])
            require(selection["translationDecisionCid"] == value["translationCid"] && selection["inspectionCid"] == inspectionCid &&
                selection["contextIri"] == value["contextIri"] && selection["contextKey"] == value["contextKey"])
        }
        prepared = value; selectedKif.clear(); review = null; reviewUncertain = false; report = null
    }
    private fun validateReview(value: Map<*, *>, receipt: Any?, expectedSnapshot: Any? = null) {
        require(cid(value["snapshotCid"]) && value["revision"] is Number && value["state"] is String)
        integer(value["revision"])
        val snapshot = value["snapshot"] as? Map<*, *> ?: error("Missing review snapshot")
        require(snapshot["kind"] == "taxonomy" && snapshot["curationReceiptCid"] == receipt && snapshot["sourceRevision"] == prepared?.get("sourceRevision"))
        val expected = rows(requireNotNull(prepared)["selections"]).associateBy { it["id"] }
        val selections = rows(snapshot["selections"]); require(selections.isNotEmpty())
        require(selections.map { it["id"] }.distinct().size == selections.size)
        for (selection in selections) {
            val offered = requireNotNull(expected[selection["id"]])
            val quotation = selection["quotation"] as? Map<*, *> ?: error("Missing frozen quotation")
            val kifQuotation = offered["kifQuotation"] as Map<*, *>
            require(s_["originalCid", "extractedTextCid", "begin", "end", "quote"].view.all { quotation[it] == kifQuotation[it] })
            val fields = selection["value"] as? Map<*, *> ?: error("Missing frozen translation")
            require(originMatches(fields["englishOrigin"], offered))
        }
        if (expectedSnapshot != null) require(value["snapshotCid"] == expectedSnapshot)
    }
    private fun originMatches(raw: Any?, selection: Map<*, *>): Boolean {
        val origin = raw as? Map<*, *> ?: return false
        return s_["selectionId", "translationDecisionCid", "inspectionCid", "contextIri", "contextKey", "sentenceIndex",
            "englishQuotation", "subject", "predicate", "copula", "bindings", "declared", "kifQuotation"].view.all { origin[it] == selection[it] }
    }
    private fun freeze() = run("The exact translation selection could not be frozen. Recheck the source and prepare it again.") { ticket ->
        val translation = requireNotNull(prepared); val ids = selectedKif.toList(); require(ids.isNotEmpty())
        val value = request("/snapshot", _m["kind" j "taxonomy", "receiptCid" j translation["receiptCid"], "selection" j ids], ticket)
        validateReview(value, translation["receiptCid"])
        require(value["current"] == true && rows((value["snapshot"] as Map<*, *>)["selections"]).map { it["id"] } == ids)
        review = value; reviewUncertain = false; report = null
    }
    private fun decide(action: String) = run("The review decision was not confirmed. Refresh the current review before trying again.") { ticket ->
        val frozen = requireNotNull(review); val translation = requireNotNull(prepared)
        val value = request("/decision", _m["snapshotCid" j frozen["snapshotCid"], "expectedRevision" j frozen["revision"], "action" j action, "note" j note], ticket)
        validateReview(value, translation["receiptCid"], frozen["snapshotCid"])
        require(integer(value["revision"]) == integer(frozen["revision"]) + 1 && value["state"] == action)
        review = value; reviewUncertain = false; report = null
    }
    private fun refresh() = run("The review could not be refreshed. The source or approval may have changed.") { ticket ->
        val frozen = requireNotNull(review); val translation = requireNotNull(prepared)
        val value = request("?snapshotCid=" + frozen["snapshotCid"], null, ticket)
        validateReview(value, translation["receiptCid"], frozen["snapshotCid"]); review = value; reviewUncertain = false; report = null
    }
    private fun query() {
        if (goalPredicate !in _s["instance", "subclass"] || goalSubject.isBlank() || goalObject.isBlank()) {
            message = "Choose a goal predicate and enter both exact IRIs."
            results?.let { renderResults(it) }; return
        }
        run("Reviewed support is unavailable or stale. Refresh the review; this failure is not an UNKNOWN result.") { ticket ->
        val frozen = requireNotNull(review); require(!reviewUncertain && frozen["state"] == "approve" && frozen["current"] == true)
        val goal = _m["predicate" j goalPredicate, "subject" j goalSubject, "object" j goalObject]
        val value = request("/taxonomy-plan", _m["snapshotCid" j frozen["snapshotCid"], "expectedRevision" j frozen["revision"], "goal" j goal], ticket)
        require(value["snapshotCid"] == frozen["snapshotCid"] && value["revision"] == frozen["revision"] && value["goal"] == goal)
        require(value["status"] in _s["SUPPORTED", "UNKNOWN", "BOUNDED", "UNAVAILABLE"])
        if (value["status"] != "UNAVAILABLE") {
            require(value["authority"] == "reviewed-read-only-not-a-sink-permit")
            val translation = requireNotNull(prepared)
            require(value["approvalCid"] == frozen["decisionCid"] && value["sourceRevision"] == translation["sourceRevision"] &&
                value["curationReceiptCid"] == translation["receiptCid"])
            val expected = rows(translation["selections"]).associateBy { it["selectionId"] }
            val frozenIds = rows((frozen["snapshot"] as Map<*, *>)["selections"]).map { it["id"] }.toSet()
            fun verifyOrigin(raw: Any?) {
                val origin = raw as? Map<*, *> ?: error("Missing English witness")
                val selection = requireNotNull(expected[origin["selectionId"]])
                require(origin["selectionId"] in frozenIds && originMatches(origin, selection))
            }
            val origin = value["englishOrigin"] as? Map<*, *> ?: error("Missing translation origin")
            require(origin["translationDecisionCid"] == translation["translationCid"] && origin["inspectionCid"] == inspectionCid &&
                origin["contextIri"] == contextIri && origin["contextKey"] == translation["contextKey"])
            val witnesses = rows(value["englishWitnesses"])
            require(witnesses.map { it["selectionId"] }.toSet() == frozenIds && witnesses.size == frozenIds.size)
            for (witness in witnesses) verifyOrigin(witness)
            for (selection in rows(value["selected"], 256)) {
                val selectedValue = selection["value"] as? Map<*, *> ?: error("Missing reviewed selection")
                verifyOrigin(selectedValue["englishOrigin"])
            }
            val plan = value["plan"] as? Map<*, *> ?: error("Missing reviewed proof paths")
            ReviewedTaxonomyProofPaths.validate(plan, rows((frozen["snapshot"] as Map<*, *>)["selections"]))
            require(plan["revision"] == value["revision"] && plan["status"] == value["status"] &&
                plan["semantics"] == value["semantics"] &&
                s_["predicate", "subject", "object"].view.all { plan[it] == goal[it] })
            // Resolve EVERY source against the complete frozen selection, including off-page premises.
            val frozenSelections = rows((frozen["snapshot"] as Map<*, *>)["selections"]).associateBy { it["id"] }
            val sources = rows(plan["sources"])
            require(sources.map { it["selectionId"] }.toSet() == frozenIds && sources.size == frozenIds.size)
            for (source in sources) {
                val selection = requireNotNull(frozenSelections[source["selectionId"]])
                val quotation = selection["quotation"] as? Map<*, *> ?: error("Missing frozen source quotation")
                val actual = source["quotation"] as? Map<*, *> ?: error("Missing proof source quotation")
                require(s_["originalCid", "extractedTextCid", "begin", "end", "quote"].view.all { actual[it] == quotation[it] } &&
                    source["sourceRevision"] == value["sourceRevision"] && source["text"] == quotation["quote"] &&
                    source["sourceCid"] == translation["derivedKifCid"])
                val offered = requireNotNull(expected[source["selectionId"]])
                require(s_["originalCid", "extractedTextCid", "begin", "end", "quote"].view.all {
                    actual[it] == (offered["kifQuotation"] as Map<*, *>)[it]
                })
                verifyOrigin((selection["value"] as Map<*, *>)["englishOrigin"])
            }
        }
        report = value
        }
    }

    private fun proofPaths(parent: HTMLElement, value: Map<*, *>) {
        val plan = value["plan"] as Map<*, *>
        val sources = rows(plan["sources"])
        val byNode = sources.associateBy { it["nodeId"] }
        val origins = rows(value["englishWitnesses"]).associateBy { it["selectionId"] }
        val proofs = rows(plan["proofs"], 128)
        val used = proofs.flatMap { it["premises"] as List<*> }.toSet()
        if (plan["truncated"] == true) text(parent, "p",
            "${value["status"]} · Search bounds were reached. Returned paths are incomplete; other support may exist.",
            "data-taxonomy-proof-incomplete").className = "ds-curate-warn"
        if (proofs.isEmpty()) text(parent, "p", "No positive proof path was returned for this goal.").className = "ds-curate-status"
        fun anchor(source: Map<*, *>) = "taxonomy-source-" + (source["nodeId"] as String).removePrefix("sha256:")
        fun rule(raw: Any?) = when (raw) {
            "kif-taxonomy/v1/instance-assertion" -> "Instance assertion"
            "kif-taxonomy/v1/subclass-assertion" -> "Subclass assertion"
            "kif-taxonomy/v1/instance-subclass" -> "Instance inherits through subclass"
            "kif-taxonomy/v1/subclass-transitivity" -> "Subclass transitivity"
            "kif-taxonomy/v1/class-reflexivity" -> "Class reflexivity with declaration witness"
            else -> error("Unknown taxonomy proof rule")
        }
        for ((index, proof) in proofs.withIndex()) {
            val path = text(parent, "section", "", "data-taxonomy-proof-path")
            path.setAttribute("data-proof-node", proof["nodeId"] as String)
            text(path, "h5", "Support path ${index + 1}").className = "ds-curate-h3"
            val ordered = text(path, "ol", "")
            val premises = proof["premises"] as List<*>
            val rules = proof["rules"] as List<*>
            for ((step, node) in premises.withIndex()) {
                val source = requireNotNull(byNode[node])
                val origin = requireNotNull(origins[source["selectionId"]])
                val item = text(ordered, "li", "", "data-taxonomy-proof-premise")
                item.setAttribute("data-source-node", node as String)
                item.setAttribute("data-selection-id", source["selectionId"] as String)
                text(item, "p", rule(rules[step]), "data-taxonomy-proof-rule").className = "ds-curate-label"
                text(item, "blockquote", (origin["englishQuotation"] as Map<*, *>)["quote"] as String).also {
                    it.className = "ds-curate-quote"; it.style.whiteSpace = "pre-wrap"
                }
                code(item, source["text"] as String)
                button(item, "Inspect this source witness") {
                    (document.getElementById(anchor(source)) as? HTMLElement)?.let { card ->
                        card.scrollIntoView(); card.focus()
                    }
                }.also { control ->
                    // This local scroll action must never replace the Documents route hash.
                    control.setAttribute("data-taxonomy-source-target", anchor(source))
                    control.setAttribute("aria-controls", anchor(source))
                }
            }
        }
        text(parent, "h5", "Reviewed source witnesses").className = "ds-curate-h3"
        for (source in sources) {
            val card = text(parent, "section", "", "data-taxonomy-proof-source")
            card.id = anchor(source)
            card.setAttribute("tabindex", "-1")
            card.setAttribute("data-source-node", source["nodeId"] as String)
            card.setAttribute("data-selection-id", source["selectionId"] as String)
            if (source["nodeId"] !in used) text(card, "p", "Selected evidence not used in returned paths",
                "data-taxonomy-unused-evidence").className = "ds-curate-status"
            else text(card, "p", "Source witness used in returned paths").className = "ds-curate-label"
            witness(card, requireNotNull(origins[source["selectionId"]]))
            val origin = origins.getValue(source["selectionId"])
            val english = origin["englishQuotation"] as Map<*, *>
            text(card, "p", "English source ${english["originalCid"]} · UTF-16 characters ${english["begin"]}–${english["end"]} · inspection ${origin["inspectionCid"]}").className = "ds-cid"
            val q = source["quotation"] as Map<*, *>
            text(card, "p", "KIF source ${source["sourceCid"]} · UTF-16 characters ${q["begin"]}–${q["end"]} · selection ${source["selectionId"]} · source node ${source["nodeId"]}").className = "ds-cid"
        }
    }

    private fun render() {
        if (!present()) return
        host.textContent = ""
        val section = text(host, "section", "", "data-english-taxonomy-review")
        section.className = "ds-curate-proposal"
        section.style.cssText = "margin:24px 0;padding:20px;min-width:0;overflow-wrap:anywhere"
        text(section, "h3", "Review taxonomy translation").also { it.className = "ds-curate-h3"; it.style.marginTop = "0" }
        text(section, "p", "Declare how selected sentences should be represented as classes and individuals. These are your explicit translation choices. No model is called and no world facts are published.").className = "ds-curate-dest"
        text(section, "p", "instance links an individual to a class; subclass links two classes. Use the same exact class IRI only when you intend the same class. Every relation and sort must be chosen explicitly.").className = "ds-curate-dest"
        text(section, "p", "Inspection: $inspectionCid", "data-taxonomy-inspection").className = "ds-cid"
        input(fields(section), "Reviewer credential", "", password = true) {
            credential = it.trim(); reviewer = null; invalidate()
            host.querySelector("[data-taxonomy-reviewer]")?.textContent = "Credential changed; authenticate before preparing a translation."
            val prepare = host.querySelector("[data-taxonomy-prepare]")
            if (prepare != null) prepare.asDynamic().disabled = true
        }
        text(section, "p", reviewer?.let { (if (syntheticReviewer) "Synthetic validation reviewer: " else "Authenticated reviewer: ") + it }
            ?: "Authenticate to inspect eligible sentences and prepare a translation.", "data-taxonomy-reviewer").className = "ds-curate-status"
        val authentication = actions(section)
        button(authentication, "Authenticate reviewer and inspect candidates") { loadCandidates() }
        button(authentication, "Forget credential") { credential = ""; reviewer = null; invalidate(); render() }
        input(fields(section), "Declared context IRI", contextIri) { contextIri = it; invalidate() }
        candidates?.let { value ->
            text(section, "p", "Eligibility: ${value["eligible"]} · profile ${value["profile"]}").className = "ds-curate-status"
            if (rows(value["sentences"], 512).size > 32) text(section, "p", "The source exceeds this profile. Only the first 32 sentence observations are shown; no translation can be prepared.").className = "ds-curate-warn"
            evidence(section, "Source and eligibility reasons", _m["source" j value["source"], "exclusionReasons" j value["exclusionReasons"]])
            for (row in declarations) {
                val card = text(section, "fieldset", ""); card.className = "ds-curate-proposal"
                card.style.cssText = "min-width:0;margin:18px 0;padding:16px"
                text(card, "legend", "Sentence ${row.candidate["sentenceIndex"]}").className = "ds-curate-label"
                text(card, "blockquote", row.candidate["quote"] as String).also { it.className = "ds-curate-quote"; it.style.whiteSpace = "pre-wrap" }
                evidence(card, "Source quotation and full parser bindings", row.candidate)
                val selectLabel = text(card, "label", "")
                selectLabel.style.cssText = "display:flex;align-items:center;gap:10px;margin:14px 0;font-size:13.5px"
                val select = document.createElement("input") as HTMLInputElement
                select.type = "checkbox"; select.checked = row.selected; select.disabled = pending || row.candidate["eligible"] != true
                select.style.cssText = "margin:0;accent-color:var(--accent)"
                select.setAttribute("aria-label", "Select ${row.id}"); selectLabel.appendChild(select)
                text(selectLabel, "span", "Select this translation")
                select.addEventListener("change", { if (present()) { row.selected = select.checked; invalidate() } })
                text(card, "p", "Source subject: ${row.candidate["subjectWord"] ?: "unavailable"} · nominal head: ${row.candidate["predicateWord"] ?: "unavailable"}").className = "ds-curate-dest"
                val roles = fields(card, "160px")
                choice(roles, "Relation for ${row.id}", row.relation, s_["instance", "subclass"]) { row.relation = it; invalidate() }
                choice(roles, "Subject sort for ${row.id}", row.subjectSort, s_["individual", "class"]) { row.subjectSort = it; invalidate() }
                choice(roles, "Object sort for ${row.id}", row.objectSort, s_["class"]) { row.objectSort = it; invalidate() }
                val terms = fields(card)
                input(terms, "Subject IRI for ${row.id}", row.subjectIri) { row.subjectIri = it; invalidate() }
                input(terms, "Object IRI for ${row.id}", row.objectIri) { row.objectIri = it; invalidate() }
            }
            button(actions(section), "Prepare translation preview", reviewer == null || value["eligible"] != true) { prepare() }.setAttribute("data-taxonomy-prepare", "1")
        }
        results = text(section, "div", "").also { it.className = "ds-curate-result"; it.style.maxWidth = "none" }
        renderResults(requireNotNull(results))
    }
    private fun renderResults(parent: HTMLElement) {
        parent.textContent = ""
        if (pending) text(parent, "p", "Checking the exact source and review…").also { it.className = "ds-retain-status"; it.setAttribute("role", "status") }
        message?.let { text(parent, "p", it).also { row -> row.className = "ds-retain-status"; row.setAttribute("role", "status") } }
        val translation = prepared
        if (translation == null) { text(parent, "p", "No approved support · UNKNOWN. Complete explicit declarations and review before querying.").className = "ds-curate-status"; return }
        text(parent, "h4", "Unreviewed translation preview").className = "ds-curate-h3"
        text(parent, "p", "Context: ${translation["contextIri"]}\nDeclaration: ${translation["translationCid"]}\nDerived KIF: ${translation["derivedKifCid"]}").also { it.className = "ds-cid"; it.style.whiteSpace = "pre-wrap" }
        code(parent, translation["kif"] as String)
        for (selection in rows(translation["selections"])) {
            val id = selection["id"] as String
            val card = text(parent, "section", ""); card.className = "ds-curate-proposal"
            witness(card, selection)
            val label = text(card, "label", "")
            label.style.cssText = "display:flex;align-items:center;gap:10px;margin:14px 0;font-size:13.5px"
            val checkbox = document.createElement("input") as HTMLInputElement
            checkbox.type = "checkbox"; checkbox.checked = id in selectedKif; checkbox.disabled = pending
            checkbox.style.cssText = "margin:0;accent-color:var(--accent)"
            checkbox.setAttribute("aria-label", "Freeze $id"); label.appendChild(checkbox)
            text(label, "span", "Freeze this exact translation")
            checkbox.addEventListener("change", {
                if (present() && !pending) {
                    if (checkbox.checked) selectedKif.add(id) else selectedKif.remove(id)
                    review = null; reviewUncertain = false; report = null; renderResults(parent)
                }
            })
        }
        button(actions(parent), "Freeze selected translations for review", selectedKif.isEmpty() || reviewer == null) { freeze() }
        val frozen = review ?: return
        text(parent, "h4", "Analyst review · ${frozen["state"]}").className = "ds-curate-h3"
        if (reviewUncertain) text(parent, "p", "Review or source status was not confirmed. Refresh before any further decision or query.").className = "ds-curate-warn"
        text(parent, "p", "Snapshot: ${frozen["snapshotCid"]} · revision ${frozen["revision"]} · source current: ${frozen["sourceCurrent"]}").className = "ds-cid"
        evidence(parent, "Frozen source, declarations and evidence", frozen["snapshot"])
        val noteLabel = text(parent, "label", "Review decision note"); noteLabel.className = "ds-curate-label"
        noteLabel.style.cssText = "display:flex;flex-direction:column;gap:6px;margin:14px 0"
        val noteField = document.createElement("textarea") as HTMLTextAreaElement
        noteField.value = note; noteField.maxLength = 4096; noteField.disabled = pending; noteField.setAttribute("aria-label", "Review decision note")
        noteField.className = "ds-curate-text"; noteField.rows = 3; noteField.style.cssText = "width:100%;box-sizing:border-box"
        noteLabel.appendChild(noteField); noteField.addEventListener("input", { note = noteField.value })
        val decisions = actions(parent)
        for ((label, action) in s_["Save draft" j "draft", "Approve exact selection" j "approve", "Reject selection" j "reject", "Correct / withdraw approval" j "correct"].view)
            button(decisions, label, reviewer == null || reviewUncertain || frozen["current"] != true) { decide(action) }
        button(decisions, "Refresh review", reviewer == null) { refresh() }
        if (reviewUncertain || frozen["state"] != "approve" || frozen["current"] != true) { text(parent, "p", "No confirmed current approval; no positive support is displayed.").className = "ds-curate-status"; return }
        fun goalChanged() { epoch++; abort?.abort(); pending = false; report = null; parent.querySelector("[data-taxonomy-query-result]")?.remove() }
        text(parent, "h4", "Read reviewed support").className = "ds-curate-h3"
        choice(fields(parent), "Goal predicate", goalPredicate, s_["instance", "subclass"]) { goalPredicate = it; goalChanged() }
        val goalTerms = fields(parent)
        input(goalTerms, "Goal subject IRI", goalSubject) { goalSubject = it; goalChanged() }
        input(goalTerms, "Goal object IRI", goalObject) { goalObject = it; goalChanged() }
        button(actions(parent), "Inspect reviewed support", reviewer == null) { query() }
        report?.let { value ->
            val result = text(parent, "section", "", "data-taxonomy-query-result")
            result.className = "ds-curate-proposal"; result.style.cssText = "margin-top:20px;padding:16px;min-width:0"
            text(result, "h4", "Reviewed taxonomy support: ${value["status"]}").also { it.className = "ds-curate-h3"; it.style.marginTop = "0" }
            val goal = value["goal"] as Map<*, *>
            text(result, "p", "Queried goal: (${goal["predicate"]} ${goal["subject"]} ${goal["object"]})", "data-taxonomy-query-goal").also {
                it.className = "ds-cid"; it.style.cssText = "margin:8px 0 14px;padding:10px 12px;background:var(--side);border-radius:5px;overflow-wrap:anywhere"
            }
            text(result, "p", "Applies to the selected approved translation assumptions in this context. UNKNOWN is not a refutation; bounded or unavailable results do not establish absence. No approval is consumed.").className = "ds-curate-dest"
            if (value["status"] != "UNAVAILABLE") {
                text(result, "p", "Context: $contextIri · approval ${value["approvalCid"]}").className = "ds-cid"
                proofPaths(result, value)
            }
            evidence(result, "Approval, context, English/KIF witnesses and proof", value)
        }
    }
}
