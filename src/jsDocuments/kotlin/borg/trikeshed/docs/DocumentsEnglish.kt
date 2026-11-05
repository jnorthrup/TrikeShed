package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.EnglishGuardQueryView
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.web.EnglishQueryNavigation
import borg.trikeshed.web.PatchRead
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/** Explicit local inspection of an identified text revision; no model or inference admission. */
internal object DocumentsEnglish {
    private var owner: Controller? = null
    private const val RANGE_LIMIT = 16_384
    fun clear() { owner?.close(); owner = null }
    private fun cid(value: Any?) = value is String && Regex("^sha256:[0-9a-f]{64}$").matches(value)
    private fun revision(value: DocRevision?) = value != null && value.project.isNotBlank() && value.id.isNotBlank() &&
        value.rev.isNotBlank() && value.seq >= 0 && cid(value.cid)

    fun selection(view: DocView): DocRevision? = view.sourceRevision?.takeIf {
        revision(it) && it.project == view.project && it.id == view.id && revision(view.displayTextRevision) &&
            view.displayTextRevision?.project == view.project
    }

    fun mount(host: HTMLElement, view: DocView, generation: Int, current: () -> Boolean) {
        val source = requireNotNull(selection(view))
        val displayed = requireNotNull(view.displayTextRevision)
        val previous = owner
        val controller = if (previous != null && previous.source == source && previous.displayed == displayed && previous.generation == generation) previous
            else { clear(); Controller(source, displayed, view.text, generation).also { owner = it } }
        controller.host = host; controller.current = current; controller.render()
    }

    private class Controller(val source: DocRevision, val displayed: DocRevision, private val preview: String, val generation: Int) {
        lateinit var host: HTMLElement
        var current: () -> Boolean = { false }
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Main)
        private var abort: dynamic = null
        private var begin = 0
        private var end = minOf(RANGE_LIMIT, preview.length).let { at -> if (splitScalar(at)) at - 1 else at }
        private var pending = false
        private var inspectionEpoch = 0
        private var retainedCid = ""
        private var openingRetained = false
        private var failure: String? = null
        private var result: Map<*, *>? = null
        private var corpusNotice: String? = null
        private var legalAbort: dynamic = null
        private var legalEpoch = 0
        private var legalMode = "current"
        private var legalPending = false
        private var legalFailure: String? = null
        private var legalResult: Map<*, *>? = null
        private var guardQueryPending = false
        private var guardQueryFailure: String? = null
        private var guardQueryStarter: EnglishGuardQueryView.Starter? = null
        private var guardQueryAnswer: EnglishGuardQueryView.Answer? = null
        private var taxonomy: DocumentsEnglishTaxonomy? = null
        private fun resetTaxonomy() { taxonomy?.close(); taxonomy = null }
        fun close() { inspectionEpoch++; abort?.abort(); resetLegal(); resetTaxonomy(); job.cancel() }
        private fun present() = owner === this && job.isActive && current() && host.asDynamic().isConnected == true
        private fun splitScalar(at: Int) = at in 1 until preview.length && preview[at - 1].isHighSurrogate() && preview[at].isLowSurrogate()
        private fun text(parent: HTMLElement, tag: String, value: String, className: String? = null): HTMLElement =
            (document.createElement(tag) as HTMLElement).also { it.textContent = value; if (className != null) it.className = className; parent.appendChild(it) }
        private fun number(value: Any?): Int {
            val n = (value as? Number)?.toDouble() ?: error("Inspection offset is unavailable")
            require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n == n.toInt().toDouble()) { "Invalid inspection offset" }
            return n.toInt()
        }
        private fun selection() = _m["project" j source.project, "id" j source.id, "cid" j source.cid, "rev" j source.rev, "seq" j source.seq]
        private fun matches(value: Any?): Boolean {
            val row = value as? Map<*, *> ?: return false
            return row["project"] == source.project && row["id"] == source.id && row["cid"] == source.cid && row["rev"] == source.rev &&
                (row["seq"] as? Number)?.toDouble() == source.seq.toDouble()
        }
        private fun validRange() = begin >= 0 && end > begin && end - begin <= RANGE_LIMIT && !splitScalar(begin) && !splitScalar(end)
        private fun validate(value: Map<*, *>, requestedBegin: Int, requestedEnd: Int) {
            require(value["schema"] == "trikeshed.english-inspection.v1" && value["reviewStatus"] == "unreviewed" &&
                value["authority"] == "lexical-and-parser-observations-only") { "Unexpected English inspection response" }
            require(matches(value["selection"]) && value["originalCid"] == source.cid && value["extractedTextCid"] == displayed.cid && cid(value["receiptCid"])) {
                "The inspected source or text revision differs from this page. Reopen the document before inspecting it."
            }
            val range = value["range"] as? Map<*, *> ?: error("Inspection coverage is unavailable")
            val total = number(range["totalLength"])
            require(number(range["begin"]) == requestedBegin && number(range["end"]) == requestedEnd && total >= requestedEnd &&
                range["complete"] == (requestedBegin == 0 && requestedEnd == total)) { "Inspection coverage differs from the requested range" }
            val sentences = value["sentences"] as? List<*> ?: error("Inspection sentences are unavailable")
            require(sentences.size <= 512) { "Inspection exceeds the sentence display bound" }
            var tokenCount = 0; var nodeCount = 0; var dependencyCount = 0; var markerCount = 0
            for (raw in sentences) {
                val sentence = raw as? Map<*, *> ?: error("Invalid inspected sentence")
                val lo = number(sentence["begin"]); val hi = number(sentence["end"])
                val quote = sentence["quote"] as? String ?: error("Sentence quotation is unavailable")
                require(lo >= requestedBegin && hi in lo..requestedEnd && quote.length == hi - lo) { "Sentence quotation is outside the inspected range" }
                if (hi <= preview.length) require(preview.substring(lo, hi) == quote) { "Sentence quotation differs from the displayed text revision" }
                val tokens = sentence["tokens"] as? List<*> ?: error("Sentence tokens are unavailable")
                val dependencies = sentence["dependencies"] as? List<*> ?: error("Sentence syntax is unavailable")
                val markers = sentence["markers"] as? List<*> ?: error("Sentence markers are unavailable")
                tokenCount += tokens.size; dependencyCount += dependencies.size; markerCount += markers.size
                require(tokenCount <= 8192 && dependencyCount <= 16_384 && markerCount <= 4096) { "Inspection exceeds the supported display bound" }
                for (item in tokens + markers) {
                    val span = item as? Map<*, *> ?: error("Invalid inspected span")
                    val a = number(span["begin"]); val b = number(span["end"])
                    val word = (span["word"] ?: span["quote"]) as? String ?: error("Inspected span text is unavailable")
                    require(a >= lo && b in a..hi && quote.substring(a - lo, b - lo) == word) { "Inspected span differs from its source quotation" }
                }
                val indices = tokens.map { number((it as Map<*, *>)["index"]) }.toSet()
                require(indices.size == tokens.size && 0 !in indices) { "Ambiguous token identity" }
                val originals = tokens.associateBy { number((it as Map<*, *>)["index"]) }
                val nodes = DocumentsSyntaxGraph.nodes(sentence)
                nodeCount += nodes.size
                require(nodeCount <= 16_384) { "Inspection exceeds the parser node display bound" }
                val nodeIds = mutableSetOf<String>()
                val originalNodes = mutableSetOf<Int>()
                for (node in nodes) {
                    val index = number(node["index"]); val copy = number(node["copy"])
                    val original = originals[index] as? Map<*, *> ?: error("Parser node has no source token")
                    val id = node["id"] as? String ?: error("Parser node identity is unavailable")
                    require(copy <= 1024 && id == "$index:$copy" && nodeIds.add(id) && node["kind"] == if (copy == 0) "token" else "copy") {
                        "Ambiguous parser node identity"
                    }
                    require(s_["begin", "end", "word", "lemma", "pos", "ner"].view.all { node[it] == original[it] }) {
                        "Parser copy differs from its original source token"
                    }
                    if (copy == 0) originalNodes.add(index)
                }
                require(originalNodes == indices) { "Parser node inventory omits an original source token" }
                for (item in dependencies) {
                    val edge = item as? Map<*, *> ?: error("Invalid syntax observation")
                    val governor = number(edge["governor"]); val dependent = number(edge["dependent"])
                    val governorCopy = edge["governorCopy"]?.let(::number) ?: 0
                    val dependentCopy = edge["dependentCopy"]?.let(::number) ?: 0
                    val governorNode = DocumentsSyntaxGraph.nodeId(edge, "governor")
                    val dependentNode = DocumentsSyntaxGraph.nodeId(edge, "dependent")
                    require(governorCopy <= 1024 && dependentCopy <= 1024 &&
                        governorNode == "$governor:$governorCopy" && dependentNode == "$dependent:$dependentCopy" &&
                        (governorNode == "0:0" || governorNode in nodeIds) && dependentNode in nodeIds &&
                        governorNode != dependentNode && edge["relation"] is String) { "Syntax observation has an unknown parser node" }
                }
            }
        }
        private fun invalidateInspection() {
            inspectionEpoch++; abort?.abort(); abort = null; pending = false
            result = null; failure = null; corpusNotice = null
            resetTaxonomy(); resetLegal()
            host.querySelector("[data-english-result]")?.remove()
            host.querySelector("[data-english-pending]")?.remove()
            host.querySelector("[data-english-failure]")?.remove()
            host.querySelector("[data-english-inspect]")?.let { it.asDynamic().disabled = preview.isEmpty() }
            host.querySelector("[data-english-open-retained]")?.let { it.asDynamic().disabled = !cid(retainedCid) || !validRange() }
            host.querySelector("[data-english-begin]")?.let { it.asDynamic().disabled = false }
            host.querySelector("[data-english-end]")?.let { it.asDynamic().disabled = false }
        }
        private fun inspect() = load(false)
        private fun load(retained: Boolean) {
            if (!validRange()) { failure = "Choose a nonempty range of at most 16,384 characters without splitting a character."; render(); return }
            if (retained && !cid(retainedCid)) { failure = "Enter an exact retained inspection CID."; render(); return }
            val requestedBegin = begin; val requestedEnd = end
            val requestedCid = retainedCid
            invalidateInspection()
            val ticket = inspectionEpoch
            fun selected() = present() && ticket == inspectionEpoch && begin == requestedBegin && end == requestedEnd &&
                (!retained || retainedCid == requestedCid)
            pending = true; openingRetained = retained; render()
            scope.launch {
                try {
                    if (!selected()) return@launch
                    val controller: dynamic = js("new AbortController()")
                    abort = controller
                    val body = selection() + _m["begin" j requestedBegin, "end" j requestedEnd] +
                        if (retained) _m["receiptCid" j requestedCid] else emptyMap()
                    val init = RequestInit(method = "POST", body = jsonOf(body),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = controller.signal
                    val response = window.fetch(if (retained) "/api/documents/english/retained" else "/api/documents/english", init).await()
                    val text = response.text().await()
                    if (!selected()) return@launch
                    require(text.length <= 8 * 1024 * 1024 && text.encodeToByteArray().size <= 8 * 1024 * 1024) { "English inspection response exceeds the display bound" }
                    val value = reifyStrict(text) as? Map<*, *> ?: error("Unreadable English inspection response")
                    require(response.ok && value["error"] == null) { (value["detail"] ?: value["error"] ?: "Local English inspection failed (HTTP ${response.status})").toString() }
                    validate(value, requestedBegin, requestedEnd)
                    if (retained) {
                        val retrieval = value["retrieval"] as? Map<*, *> ?: error("Missing retained inspection provenance")
                        require(value["receiptCid"] == requestedCid && retrieval["kind"] == "retained-current-inspection" &&
                            retrieval["parserExecuted"] == false && retrieval["modelCalled"] == false &&
                            retrieval["currentness"] == "checked-before-and-after-open") { "Retained inspection identity or currentness differs" }
                    }
                    if (selected()) { result = value; retainedCid = value["receiptCid"] as String; corpusNotice = null }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (selected()) failure = error.message ?: "English inspection is unavailable" }
                finally { if (selected()) { abort = null; pending = false; render() } }
            }
        }
        private fun resetLegal() {
            legalEpoch++; legalAbort?.abort(); legalAbort = null
            legalPending = false; legalFailure = null; legalResult = null
            clearGuardQuery()
        }
        private fun clearGuardQuery() {
            guardQueryPending = false; guardQueryFailure = null; guardQueryStarter = null; guardQueryAnswer = null
        }
        private fun legalPatterns(inspection: Map<*, *>) {
            if (!present() || pending || legalPending || guardQueryPending) return
            clearGuardQuery()
            val receipt = inspection["receiptCid"] as String
            val mode = legalMode; val ticket = ++legalEpoch
            fun selected() = present() && legalEpoch == ticket && result?.get("receiptCid") == receipt
            legalPending = true; legalFailure = null; legalResult = null; render()
            scope.launch {
                try {
                    if (!selected()) return@launch
                    val controller: dynamic = js("new AbortController()")
                    legalAbort = controller
                    val init = RequestInit(method = "POST", body = jsonOf(_m["receiptCid" j receipt, "mode" j mode]),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = controller.signal
                    val response = window.fetch("/api/documents/english/legal-patterns", init).await()
                    val body = response.text().await()
                    require(body.length <= 4 * 1024 * 1024 && body.encodeToByteArray().size <= 4 * 1024 * 1024) { "Legal language response exceeds the display bound" }
                    val value = reifyStrict(body) as? Map<*, *> ?: error("Unreadable legal language candidate response")
                    require(response.ok && value["error"] == null) { (value["error"] ?: "Legal language inspection failed (HTTP ${response.status})").toString() }
                    validateLegal(value, inspection, mode)
                    if (selected()) legalResult = value
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (selected()) legalFailure = error.message ?: "Legal language candidates are unavailable" }
                finally { if (selected()) { legalAbort = null; legalPending = false; render() } }
            }
        }
        private fun legalRows(value: Any?, limit: Int = 32_768): List<Map<*, *>> {
            val rows = value as? List<*> ?: error("Legal language candidate rows are unavailable")
            require(rows.size <= limit) { "Legal language candidate display bound exceeded" }
            return rows.map { it as? Map<*, *> ?: error("Invalid legal language candidate row") }
        }
        private fun unresolved(value: Any?): String {
            val rows = value as? List<*> ?: error("Unresolved candidate scope is unavailable")
            require(rows.size <= 64 && rows.all { it is String }) { "Invalid unresolved candidate scope" }
            return rows.joinToString(" · ").ifBlank { "No additional scope reported" }
        }
        private fun validateLegal(value: Map<*, *>, inspection: Map<*, *>, mode: String) {
            require(value["schema"] == "trikeshed.english-legal-patterns.v1" && value["reviewStatus"] == "unreviewed" &&
                value["authority"] == "parser-role-and-quantity-candidates-only" && value["producerAuthority"] == "not-established" &&
                value["parserExecution"] == "not-reverified" && value["countsAreEvidenceConfidence"] == false) { "Unexpected legal language candidate authority or schema" }
            require(value["inspectionCid"] == inspection["receiptCid"] && cid(value["receiptCid"]) &&
                value["originalCid"] == source.cid && value["extractedTextCid"] == displayed.cid && matches(value["selection"]) &&
                value["range"] == inspection["range"]) { "Legal language candidates differ from the selected inspection" }
            val descriptor = value["source"] as? Map<*, *> ?: error("Legal language source descriptor is unavailable")
            require(descriptor["receiptCid"] == inspection["receiptCid"] && descriptor["originalCid"] == source.cid &&
                descriptor["extractedTextCid"] == displayed.cid && descriptor["mode"] == mode && descriptor["historical"] == (mode == "snapshot") &&
                descriptor["reviewStatus"] == "unreviewed" && descriptor["semanticAuthority"] == "not-established" &&
                descriptor["currentness"] == if (mode == "current") "checked-before-and-after-inspection" else "not-checked") { "Legal language source mode or identity differs from the request" }
            unresolved(value["unresolved"])
            val retained = legalRows(inspection["sentences"], 512); val sentences = legalRows(value["sentences"], 512)
            require(sentences.size == retained.size) { "Legal language sentence coverage differs from the inspection" }
            var actions = 0; var durations = 0
            for ((ordinal, sentence) in sentences.withIndex()) {
                val original = retained[ordinal]
                require(s_["index", "begin", "end", "quote", "quotation", "markers"].view.all { sentence[it] == original[it] }) { "Legal language quotation differs from the retained sentence" }
                val nodes = DocumentsSyntaxGraph.nodes(original).associateBy { it["id"] }
                require(legalRows(sentence["nodes"]) == nodes.values.toList()) { "Legal language parser nodes differ from the retained inspection" }
                fun node(raw: Any?) { val item = raw as? Map<*, *> ?: error("Parser role node is unavailable"); require(nodes[item["id"]] == item) { "Parser role node differs from its retained full identity" } }
                fun key(edge: Map<*, *>) = Triple(DocumentsSyntaxGraph.nodeId(edge, "governor"), edge["relation"], DocumentsSyntaxGraph.nodeId(edge, "dependent"))
                val edges = legalRows(original["dependencies"]).map(::key).toSet()
                fun bindings(raw: Any?) { for (edge in legalRows(raw)) require(key(edge) in edges) { "Candidate dependency differs from the retained parser edge" } }
                bindings(sentence["dependencies"]); unresolved(sentence["unresolved"])
                if ("guardAttachments" in value) require(legalRows(sentence["dependencies"]).map(::key) == legalRows(original["dependencies"]).map(::key)) {
                    "Guard attachment source dependencies differ from the retained inspection"
                }
                for (action in legalRows(sentence["modalActions"], 1024)) {
                    actions++; require(actions <= 1024 && action["status"] == "parser-role-candidate")
                    node(action["modal"]); node(action["action"]); bindings(_l[action["dependency"]]); bindings(action["relatedDependencies"])
                    val modal = action["modal"] as Map<*, *>; val verb = action["action"] as Map<*, *>
                    val auxiliary = action["dependency"] as Map<*, *>
                    require(auxiliary["governorNode"] == verb["id"] && auxiliary["dependentNode"] == modal["id"] && auxiliary["relation"] == "aux")
                    for (role in s_["subjects", "objects"].view) for (row in legalRows(action[role])) {
                        node(row["node"]); bindings(_l[row["dependency"]])
                        val subject = row["node"] as Map<*, *>; val edge = row["dependency"] as Map<*, *>
                        require(edge["governorNode"] == verb["id"] && edge["dependentNode"] == subject["id"] && edge["relation"] == row["relation"] &&
                            row["relation"] in if (role == "subjects") _s["nsubj", "nsubj:pass"] else _s["obj", "dobj", "iobj"])
                    }
                    unresolved(action["unresolved"])
                }
                val quote = original["quote"] as String; val base = number(original["begin"])
                fun span(row: Map<*, *>, lo: String, hi: String, field: String) {
                    val a = number(row[lo]) - base; val b = number(row[hi]) - base
                    require(a >= 0 && b in a..quote.length && quote.substring(a, b) == row[field]) { "Quantity language differs from its retained quotation" }
                }
                for (duration in legalRows(sentence["durations"], 1024)) {
                    durations++; require(durations <= 1024 && duration["status"] in _s["lexical-integer-candidate", "unresolved-quantity-candidate"])
                    span(duration, "begin", "end", "quote"); span(duration, "quantityBegin", "quantityEnd", "quantityLiteral")
                    duration["integerValue"]?.let { require(number(it) <= 9999) }
                    if (duration["integerValue"] == null) require(duration["status"] == "unresolved-quantity-candidate")
                    require(duration["quantityAuthority"] == "bounded-lexical-reading-only" && duration["unitLiteral"] is String && duration["unitLemma"] is String)
                    for (reading in legalRows(duration["numberReadings"], 24)) { span(reading, "begin", "end", "quote"); reading["integerValue"]?.let { require(number(it) <= 9999) } }
                    for (field in s_["unitNodes", "modifiers"].view) for (item in legalRows(duration[field])) node(item)
                    duration["temporalMarker"]?.let(::node)
                    for (field in s_["numberDependencies", "markerDependencies", "attachments"].view) bindings(duration[field])
                    unresolved(duration["unresolved"])
                }
            }
            val counts = value["counts"] as? Map<*, *> ?: error("Candidate counts are unavailable")
            require(number(counts["modalActions"]) == actions && number(counts["durations"]) == durations) { "Candidate counts differ from the displayed observations" }
            EnglishGuardQueryView.section(value) // Absent is legacy; present null/unknown/malformed must refuse.
        }
        private fun rangeInput(parent: HTMLElement, label: String, attribute: String, value: Int, assign: (Int) -> Unit) {
            val holder = text(parent, "label", label, "ds-curate-label")
            holder.style.cssText = "display:flex;gap:8px;align-items:center;margin:0"
            val input = document.createElement("input") as HTMLInputElement
            input.type = "number"; input.min = "0"; input.step = "1"; input.value = value.toString()
            input.className = "ds-curate-name"; input.style.cssText = "width:110px;padding:7px 9px"
            input.disabled = pending; input.setAttribute(attribute, "1"); holder.appendChild(input)
            input.addEventListener("input", {
                if (present() && !pending) { assign(input.value.toIntOrNull() ?: -1); invalidateInspection() }
            })
        }
        private fun visualQuery(parent: HTMLElement, inspection: Map<*, *>) {
            val range = inspection["range"] as? Map<*, *> ?: return
            val requestedBegin = number(range["begin"]); val requestedEnd = number(range["end"])
            val receipt = inspection["receiptCid"] as? String ?: return
            val ticket = inspectionEpoch
            fun selected() = present() && parent.asDynamic().isConnected == true && !pending && result === inspection && ticket == inspectionEpoch &&
                retainedCid == receipt && begin == requestedBegin && end == requestedEnd && validRange()
            if (!selected()) return
            val href = runCatching {
                validate(inspection, requestedBegin, requestedEnd)
                EnglishQueryNavigation.href(inspection)
            }.getOrNull() ?: return
            val link = text(parent, "a", "Open visual query (does not run)", "ds-curate-submit")
            link.setAttribute("href", href); link.setAttribute("target", "_blank"); link.setAttribute("rel", "noopener noreferrer")
            link.setAttribute("data-english-open-query", "1")
            link.style.cssText = "display:inline-block;margin:10px 12px 10px 0;padding:8px 12px;text-decoration:none"
            for (event in s_["click", "auxclick", "contextmenu"].view) link.addEventListener(event, { e ->
                if (!selected() || runCatching { validate(inspection, requestedBegin, requestedEnd) }.isFailure) e.preventDefault()
            })
        }
        fun render() {
            if (!present()) return
            host.textContent = ""
            val section = text(host, "section", "")
            section.setAttribute("data-english-inspection", "1")
            section.style.cssText = "max-width:1080px;margin:0 auto 48px;padding:24px;border-top:1px solid var(--rule);overflow-wrap:anywhere"
            text(section, "h2", "English inspection", "ds-curate-h3")
            text(section, "p", "Inspect this text locally: sentence structure, word forms and legal-language markers. These are unreviewed observations, not legal conclusions. No generative model is used.")
            val controls = text(section, "div", "")
            controls.style.cssText = "display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:14px 0"
            rangeInput(controls, "Start", "data-english-begin", begin) { begin = it }
            rangeInput(controls, "End", "data-english-end", end) { end = it }
            val button = text(controls, "button", "Inspect English locally", "ds-curate-submit")
            button.setAttribute("data-english-inspect", "1")
            button.setAttribute("type", "button"); button.asDynamic().disabled = pending || preview.isEmpty()
            button.style.marginTop = "0"
            button.addEventListener("click", { if (present() && !pending) inspect() })
            text(section, "p", "Range uses text character offsets, ending before End. Each inspection covers at most 16,384 characters; the result reports whether it covers the full stored text.", "ds-curate-label")
            val retainedControls = text(section, "div", "")
            retainedControls.style.cssText = "display:flex;gap:12px;align-items:end;flex-wrap:wrap;margin:18px 0"
            val retainedLabel = text(retainedControls, "label", "Retained inspection CID", "ds-curate-label")
            retainedLabel.style.cssText = "display:flex;flex:1 1 320px;flex-direction:column;gap:6px;min-width:0"
            val retainedField = document.createElement("input") as HTMLInputElement
            retainedField.type = "text"; retainedField.value = retainedCid; retainedField.maxLength = 71
            retainedField.className = "ds-curate-name"; retainedField.style.cssText = "width:100%;box-sizing:border-box"
            retainedField.setAttribute("aria-label", "Retained inspection CID"); retainedField.setAttribute("data-english-retained-cid", "1")
            retainedLabel.appendChild(retainedField)
            retainedField.addEventListener("input", { if (present()) { retainedCid = retainedField.value; invalidateInspection() } })
            val open = text(retainedControls, "button", "Open retained inspection", "ds-curate-submit")
            open.setAttribute("type", "button"); open.setAttribute("data-english-open-retained", "1"); open.style.marginTop = "0"
            open.asDynamic().disabled = pending || !cid(retainedCid) || !validRange()
            open.addEventListener("click", { if (present() && !pending) load(true) })
            text(section, "p", "Open stored annotations for this exact document revision and range. This checks the retained source and current project without rerunning the parser.", "ds-curate-label")
            if (pending) text(section, "p", if (openingRetained) "Opening retained English inspection…" else "Inspecting English locally…").also {
                it.setAttribute("role", "status"); it.setAttribute("data-english-pending", "1")
            }
            failure?.let { text(section, "p", it, "ds-curate-error").also { message ->
                message.setAttribute("role", "status"); message.setAttribute("data-english-failure", "1")
            } }
            val value = result ?: return
            val resultSection = text(section, "div", ""); resultSection.setAttribute("data-english-result", "1")
            val range = value["range"] as Map<*, *>
            text(resultSection, "h3", "Unreviewed inspection", "ds-curate-h3")
            if (value["retrieval"] != null) text(resultSection, "p", "Opened from retained evidence; no parser was run. Recorded parser execution is not reverified.", "ds-curate-label")
            text(resultSection, "p", "Text range ${range["begin"]}–${range["end"]} of ${range["totalLength"]} characters · " +
                if (range["complete"] == true) "full text covered" else "partial coverage")
            text(resultSection, "p", "Evidence receipt: ${value["receiptCid"]}", "ds-cid")
            val parser = value["parser"] as? Map<*, *>
            text(resultSection, "p", "Reader: ${parser?.get("processor") ?: "not reported"}. Markers are literal word and phrase candidates; their scope and legal effect require review.", "ds-curate-label")
            val coverage = value["coverage"] as? Map<*, *>
            if (coverage?.get("parserNodeIdentityReported") != true || coverage?.get("parserDependencyNodeIdentity") != "token-index+copy-count/1")
                text(resultSection, "p", "Full parser node identity was not reported. Token indices and displayed syntax do not establish that enhanced copy identities were captured.", "ds-curate-label")
            visualQuery(resultSection, value)
            val use = text(resultSection, "button", "Use this inspection in corpus", "ds-curate-submit")
            use.setAttribute("type", "button"); use.setAttribute("data-corpus-use-inspection", "1"); use.asDynamic().disabled = pending
            use.style.cssText = "margin:10px 0;padding:8px 12px"
            use.addEventListener("click", {
                if (present() && !pending) {
                    corpusNotice = runCatching { DocumentsCorpus.add(source.project, value["receiptCid"] as String) }
                        .getOrElse { it.message ?: "Inspection could not be added to the corpus shelf" }
                    render()
                }
            })
            corpusNotice?.let { text(resultSection, "p", it, "ds-curate-label").setAttribute("role", "status") }
            legalControls(resultSection, value)
            if (!pending && number(range["begin"]) == begin && number(range["end"]) == end) {
                val controller = taxonomy ?: DocumentsEnglishTaxonomy(value, scope).also { taxonomy = it }
                controller.mount(text(resultSection, "div", "")) {
                    present() && !pending && result === value && number(range["begin"]) == begin && number(range["end"]) == end
                }
            }
            for (raw in value["sentences"] as List<*>) sentence(resultSection, raw as Map<*, *>)
        }
        private fun legalControls(parent: HTMLElement, inspection: Map<*, *>) {
            val section = text(parent, "section", "")
            section.setAttribute("data-english-legal-patterns", "1")
            text(section, "p", "Inspect modal words, parser roles and number/unit language from this retained inspection. The parser is not rerun; legal force, deadlines and guard scope remain unresolved.", "ds-curate-label")
            val controls = text(section, "div", ""); controls.style.cssText = "display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:10px 0"
            val label = text(controls, "label", "Source mode ", "ds-curate-label")
            val mode = document.createElement("select") as HTMLSelectElement
            mode.setAttribute("data-english-legal-mode", "1"); mode.setAttribute("aria-label", "Legal language source mode")
            for ((key, title) in _l["current" j "Check current source", "snapshot" j "Retained historical snapshot"]) {
                text(mode, "option", title).setAttribute("value", key)
            }
            mode.value = legalMode; mode.disabled = pending || legalPending || guardQueryPending; label.appendChild(mode)
            mode.addEventListener("change", { if (present() && !pending && !legalPending && !guardQueryPending) { resetLegal(); legalMode = mode.value; render() } })
            val button = text(controls, "button", "Inspect legal roles and quantities", "ds-curate-submit")
            button.setAttribute("type", "button"); button.setAttribute("data-english-legal-inspect", "1")
            button.style.cssText = "margin:0;padding:8px 12px"; button.asDynamic().disabled = pending || legalPending || guardQueryPending
            button.addEventListener("click", { if (present() && !pending && !legalPending && !guardQueryPending) legalPatterns(inspection) })
            if (legalMode == "snapshot") text(section, "p", "Historical snapshot mode does not check current source revisions.", "ds-curate-label")
            if (legalPending) text(section, "p", "Reading retained legal language candidates…").setAttribute("role", "status")
            legalFailure?.let { text(section, "p", it, "ds-curate-error").setAttribute("role", "status") }
            legalResult?.let { legalResult(section, it) }
        }
        private fun queryGuardAttachments(candidate: Map<*, *>) {
            if (!present() || pending || legalPending || guardQueryPending || legalResult !== candidate) return
            val starter = runCatching { EnglishGuardQueryView.starter(candidate) }.getOrElse {
                guardQueryFailure = it.message ?: "Clause attachment query is unavailable"; render(); return
            }
            val inspectionCid = candidate["inspectionCid"] as String
            val mode = legalMode; val ticket = ++legalEpoch
            fun active() = present() && legalEpoch == ticket && result?.get("receiptCid") == inspectionCid && legalMode == mode
            guardQueryStarter = starter; guardQueryAnswer = null; guardQueryFailure = null; guardQueryPending = true
            render()
            scope.launch {
                var status: Int? = null
                try {
                    if (!active() || legalResult !== candidate) return@launch
                    val controller: dynamic = js("new AbortController()"); legalAbort = controller
                    val init = RequestInit(method = "POST", body = jsonOf(_m["inspectionCid" j inspectionCid,
                        "mode" j mode, "program" j reifyStrict(LcncProgramConfix.toJson(starter.program))]),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = controller.signal
                    val response = window.fetch("/api/documents/english/legal-patterns/query", init).await()
                    status = response.status.toInt()
                    val body = PatchRead.text(response.asDynamic(), 900_000)
                    if (!active() || legalResult !== candidate) return@launch
                    val value = reifyStrict(body) as? Map<*, *> ?: error("Unreadable clause attachment query response")
                    require(response.ok && value["error"] == null) { (value["error"] ?: "Clause query refused (HTTP $status)").toString() }
                    val answer = EnglishGuardQueryView.answer(candidate, value, starter)
                    if (active() && legalResult === candidate) guardQueryAnswer = answer
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) {
                    if (active()) {
                        guardQueryAnswer = null
                        val message = error.message ?: "Clause attachment query unavailable"
                        if (status == 400 || status == 413) guardQueryFailure = "Query refused: $message. No partial rows are shown."
                        else {
                            legalResult = null; guardQueryStarter = null
                            legalFailure = "Clause query or source status was not confirmed: $message. Inspect legal candidates again."
                        }
                    }
                } finally { if (active()) { legalAbort = null; guardQueryPending = false; render() } }
            }
        }
        private fun guardAnchor(candidate: Map<*, *>, sentence: Int, ordinal: Int) =
            "english-guard-" + (candidate["receiptCid"] as String).removePrefix("sha256:") + "-$sentence-$ordinal"
        private fun focusGuard(candidate: Map<*, *>, sentence: Int, ordinal: Int) {
            if (present() && legalResult === candidate) {
                val card = host.querySelector("#" + guardAnchor(candidate, sentence, ordinal)) as? HTMLElement ?: return
                card.scrollIntoView(); card.focus()
            }
        }
        private fun renderGuardQuery(parent: HTMLElement, candidate: Map<*, *>) {
            val section = text(parent, "section", "", "ds-run"); section.setAttribute("data-english-guard-query", "1")
            text(section, "h4", "Observed clause attachments")
            text(section, "p", "Direct parser edges and lexical cues only. No reconstructed guard scope, legal applicability or polarity decision is established. The eight-pattern query includes every observed adverbial target, including unmarked and nested targets.", "ds-curate-label")
            val button = text(section, "button", "Query observed clause attachments", "ds-curate-submit")
            button.setAttribute("type", "button"); button.setAttribute("data-english-guard-query-run", "1")
            button.asDynamic().disabled = pending || legalPending || guardQueryPending
            button.addEventListener("click", { queryGuardAttachments(candidate) })
            if (guardQueryPending) text(section, "p", "Querying this receipt-local candidate graph…").setAttribute("role", "status")
            guardQueryFailure?.let { text(section, "p", it, "ds-curate-error").setAttribute("role", "status") }
            val starter = guardQueryStarter ?: return
            val program = text(section, "details", ""); text(program, "summary", "Inspection-only LCNC query and SPARQL")
            text(program, "pre", LcncProgramConfix.toJson(starter.program) + "\n\n" + starter.query).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:360px;overflow:auto"
            val answer = guardQueryAnswer ?: return
            text(section, "p", "${answer.rows.size} typed attachment rows, displayed in source sentence/attachment order. The query has no ORDER BY. Empty rows mean no matching projected attachment, not absence of guards or legal effects.", "ds-curate-label")
            val table = table(section, EnglishGuardQueryView.variables.toSeries())
            for (row in answer.rows) {
                val tr = text(table, "tr", ""); tr.setAttribute("data-english-guard-query-row", "${row.sentenceIndex}:${row.ordinal}")
                for (name in EnglishGuardQueryView.variables) {
                    val binding = row.bindings[name] as Map<*, *>
                    val cell = text(tr, "td", "${binding["value"]}\nRDF ${binding["type"]}")
                    cell.setAttribute("data-guard-query-variable", name)
                    cell.style.cssText = "padding:7px 8px;border-bottom:1px solid var(--rule);vertical-align:top;white-space:pre-wrap;overflow-wrap:anywhere"
                    if (name == "attachment") {
                        val focus = text(cell, "button", "Inspect attachment source", "ds-curate-submit")
                        focus.setAttribute("type", "button"); focus.setAttribute("data-guard-query-source-target", guardAnchor(candidate, row.sentenceIndex, row.ordinal))
                        focus.addEventListener("click", { focusGuard(candidate, row.sentenceIndex, row.ordinal) })
                    }
                }
            }
            val source = answer.value["source"] as Map<*, *>
            text(section, "p", "Candidate graph: ${source["namedGraph"]}\nSource mode: ${source["mode"]} · currentness: ${source["currentness"]} · parser: ${source["parserExecution"]} · unreviewed", "ds-cid").style.whiteSpace = "pre-wrap"
            val raw = text(section, "details", ""); text(raw, "summary", "Typed bindings and source descriptor")
            text(raw, "pre", jsonOf(answer.value)).style.cssText = "white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto"
        }
        private fun renderGuardSentence(parent: HTMLElement, candidate: Map<*, *>, sentence: Map<*, *>, guard: Map<*, *>) {
            val section = text(parent, "section", "", "ds-run"); section.setAttribute("data-english-guard-sentence", guard["index"].toString())
            text(section, "h4", "Recorded clause attachment observations")
            val diagnostic = guard["normConditionIssue"] as? String
            text(section, "p", "Existing NormClauses.conditionIssue: ${diagnostic ?: "none reported (does not establish a supported condition)"}", "ds-curate-label")
                .setAttribute("data-guard-norm-diagnostic", diagnostic ?: "none-reported")
            text(section, "p", "This diagnostic is separate from the parser links and quotation indicators. Neither a null diagnostic nor a direct attachment establishes legal scope.", "ds-curate-label")
            val nodes = legalRows(sentence["nodes"], 16_384).associateBy { it["id"] as String }
            fun node(id: Any?) = nodes.getValue(id as String)
            fun ids(raw: Any?) = raw as List<*>
            fun edge(raw: Any?): String {
                val value = raw as Map<*, *>
                fun label(id: Any?) = if (id == "0:0") "virtual root 0:0" else legalNode(node(id))
                return "${label(value["governorNode"])} — ${value["relation"]} → ${label(value["dependentNode"])}"
            }
            val quotationIds = ids(guard["quotationIndicatorNodeIds"])
            text(section, "p", if (quotationIds.isEmpty()) "No quotation-indicator nodes reported; quotation scope was not evaluated."
                else "Quotation-indicator source nodes: " + quotationIds.joinToString(" · ") { legalNode(node(it)) }, "ds-curate-label")
                .setAttribute("data-guard-quotation-indicators", quotationIds.size.toString())
            text(section, "p", "Quote punctuation is not paired here and does not establish reported-speech or operative scope.", "ds-curate-label")
            val markers = legalRows(guard["markerBindings"])
            text(section, "h5", "Condition and exception marker occurrences")
            if (markers.isEmpty()) text(section, "p", "No listed condition/exception marker occurrence reported.", "ds-curate-label")
            for (binding in markers) {
                val marker = binding["marker"] as Map<*, *>
                val item = text(section, "div", ""); item.setAttribute("data-guard-marker-ordinal", binding["markerOrdinal"].toString())
                text(item, "p", "Marker ${binding["markerOrdinal"]} · ${marker["kind"]} / ${marker["label"]} · “${marker["quote"]}” [${marker["begin"]}, ${marker["end"]})")
                text(item, "p", "Aligned full nodes: " + ids(binding["nodeIds"]).joinToString(" · ") { legalNode(node(it)) }.ifBlank { "Not reported" }, "ds-curate-label")
                val dependencies = legalRows(binding["dependencies"])
                text(item, "pre", dependencies.joinToString("\n", transform = ::edge).ifBlank { "No direct mark/advmod binding reported." }).style.whiteSpace = "pre-wrap"
                text(item, "p", "Unresolved: ${unresolved(binding["unresolved"])}", "ds-curate-label")
            }
            val attachments = legalRows(guard["attachments"], 1024)
            if (attachments.isEmpty()) text(section, "p", "No direct adverbial dependency edge reported in this section. Lexical marker observations remain separate.", "ds-curate-label")
            for (attachment in attachments) {
                val ordinal = number(attachment["ordinal"]); val index = number(guard["index"])
                val item = text(section, "article", "", "ds-run"); item.id = guardAnchor(candidate, index, ordinal)
                item.setAttribute("tabindex", "-1"); item.setAttribute("data-english-guard-attachment", "$index:$ordinal")
                text(item, "h5", "Observed attachment $ordinal · ${(attachment["dependency"] as Map<*, *>)["relation"]}")
                text(item, "p", "Governor head: ${legalNode(node(attachment["governorNodeId"]))}")
                text(item, "p", "Subordinate head: ${legalNode(node(attachment["subordinateNodeId"]))}")
                text(item, "pre", edge(attachment["dependency"])).style.whiteSpace = "pre-wrap"
                val modalRefs = ids(attachment["modalActionOrdinals"])
                text(item, "p", "Direct modal-action references: ${modalRefs.joinToString().ifBlank { "None reported; no modal is inherited" }}", "ds-curate-label")
                val matched = ids(attachment["markerBindingOrdinals"])
                text(item, "p", "Direct marker ordinals: ${matched.joinToString().ifBlank { "None reported for this target" }}", "ds-curate-label")
                    .setAttribute("data-guard-direct-marker-count", matched.size.toString())
                val cues = legalRows(attachment["directNegationCueBindings"])
                if (cues.isEmpty()) text(item, "p", "No direct lexical negation-cue binding reported. This is not a positive-polarity conclusion.", "ds-curate-label")
                for (cue in cues) {
                    val location = cue["location"] as String
                    val title = when {
                        location == "governor-head" && modalRefs.isNotEmpty() -> "Modal-action / governor-head cue"
                        location == "governor-head" -> "Governor-head cue"
                        location == "subordinate-head" -> "Subordinate-head cue"
                        else -> "Other observed head cue"
                    }
                    text(item, "p", "$title · ${legalNode(node(cue["cueNodeId"]))} · location $location")
                        .setAttribute("data-guard-cue-location", location)
                    text(item, "pre", edge(cue["dependency"])).style.whiteSpace = "pre-wrap"
                }
                val coordination = legalRows(attachment["coordinationDependencies"])
                if (coordination.isNotEmpty()) text(item, "pre", "Observed coordination bindings:\n" + coordination.joinToString("\n", transform = ::edge)).style.whiteSpace = "pre-wrap"
                text(item, "p", "Unresolved: ${unresolved(attachment["unresolved"])}", "ds-curate-label")
            }
            text(section, "p", "Unresolved: ${unresolved(guard["unresolved"])}. Repeated views of one marker/cue do not create independent evidence.", "ds-curate-label")
        }
        private fun legalNode(raw: Any?): String {
            val node = raw as Map<*, *>
            return "${node["word"]} · ${node["id"]} · ${node["kind"]} · lemma ${node["lemma"]} · ${node["begin"]}–${node["end"]}"
        }
        private fun legalResult(parent: HTMLElement, value: Map<*, *>) {
            val section = text(parent, "section", "", "ds-run"); section.setAttribute("data-english-legal-result", "1")
            text(section, "h3", "Unreviewed legal language candidates", "ds-curate-h3")
            val source = value["source"] as Map<*, *>
            text(section, "p", "Mode: ${source["mode"]} · currentness: ${source["currentness"]} · parser execution: ${value["parserExecution"]}", "ds-curate-label")
            text(section, "p", "Parent inspection: ${value["inspectionCid"]}\nCandidate receipt: ${value["receiptCid"]}\nOriginal source: ${value["originalCid"]}\nExtracted text: ${value["extractedTextCid"]}", "ds-cid").style.whiteSpace = "pre-wrap"
            text(section, "p", "Unresolved: ${unresolved(value["unresolved"])}", "ds-curate-label")
            text(section, "p", "Full parser IDs include copy counts. Copies reuse source words and can repeat roles; they are not additional word occurrences. Integer readings do not establish deadlines, and months are not converted to days.", "ds-curate-label")
            val guardSection = value["guardAttachments"] as? Map<*, *>
            val guardRows = guardSection?.let { legalRows(it["sentences"], 512) }
            if (guardRows == null) text(section, "p", "Guard attachment section not recorded. Existing raw observations remain available; no guard coverage is established.", "ds-curate-label")
            else renderGuardQuery(section, value)
            for ((ordinal, sentence) in legalRows(value["sentences"], 512).withIndex()) {
                val row = text(section, "section", "", "ds-run"); row.setAttribute("data-english-legal-sentence", sentence["index"].toString())
                text(row, "p", "Sentence ${sentence["index"]} · text ${sentence["begin"]}–${sentence["end"]}", "ds-curate-label")
                text(row, "blockquote", sentence["quote"] as String).style.cssText = "white-space:pre-wrap;margin:10px 0;font-size:16px;line-height:1.6"
                text(row, "p", "Unresolved: ${unresolved(sentence["unresolved"])}", "ds-curate-label")
                val actions = legalRows(sentence["modalActions"], 1024)
                if (actions.isEmpty()) text(row, "p", "No modal/action parser-role candidate in this sentence.", "ds-curate-label")
                else {
                    val table = table(row, s_["Modal word", "Parser action", "Parser subject", "Parser object"])
                    for (action in actions) {
                        fun roles(key: String) = legalRows(action[key]).joinToString("\n") { "${it["relation"]}: ${legalNode(it["node"])}" }.ifBlank { "Not reported" }
                        cells(table, s_[legalNode(action["modal"]), legalNode(action["action"]), roles("subjects"), roles("objects")])
                        text(row, "p", "Parser-role candidate · unresolved: ${unresolved(action["unresolved"])}", "ds-curate-label")
                    }
                }
                val durations = legalRows(sentence["durations"], 1024)
                if (durations.isEmpty()) text(row, "p", "No supported number/unit language candidate in this sentence.", "ds-curate-label")
                for (duration in durations) {
                    val item = text(row, "section", ""); item.setAttribute("data-english-legal-duration", "1")
                    text(item, "blockquote", duration["quote"] as String).style.whiteSpace = "pre-wrap"
                    text(item, "p", "${duration["status"]} · literal: ${duration["quantityLiteral"]} · integer reading: ${duration["integerValue"]?.let { number(it).toString() } ?: "unresolved"} · unit: ${duration["unitLiteral"]} (${duration["unitLemma"]})")
                    text(item, "p", "Unit nodes: ${legalRows(duration["unitNodes"]).joinToString("; ", transform = ::legalNode)}\nModifiers: ${legalRows(duration["modifiers"]).joinToString("; ", transform = ::legalNode).ifBlank { "None reported" }}\nTemporal marker: ${duration["temporalMarker"]?.let(::legalNode) ?: "None reported"}", "ds-curate-label").style.whiteSpace = "pre-wrap"
                    for (reading in legalRows(duration["numberReadings"], 24)) text(item, "p", "Number form: “${reading["quote"]}” · integer reading: ${reading["integerValue"]?.let { number(it).toString() } ?: "unresolved"} · ${reading["begin"]}–${reading["end"]}", "ds-curate-label")
                    text(item, "p", "Unresolved: ${unresolved(duration["unresolved"])}", "ds-curate-label")
                }
                guardRows?.let { renderGuardSentence(row, value, sentence, it[ordinal]) }
            }
        }
        private fun sentence(parent: HTMLElement, value: Map<*, *>) {
            val section = text(parent, "section", "", "ds-run")
            section.setAttribute("data-english-sentence", value["index"].toString())
            text(section, "p", "Sentence ${value["index"]} · text ${value["begin"]}–${value["end"]}", "ds-curate-label")
            text(section, "blockquote", value["quote"] as String).style.cssText = "white-space:pre-wrap;margin:10px 0;font-size:16px;line-height:1.6"
            val markers = value["markers"] as List<*>
            if (markers.isEmpty()) text(section, "p", "No listed lexical markers found in this sentence.", "ds-curate-label")
            else {
                val list = text(section, "ul", "")
                for (raw in markers) {
                    val marker = raw as Map<*, *>
                    val row = text(list, "li", "${marker["kind"]} · “${marker["quote"]}” · ${marker["begin"]}–${marker["end"]}")
                    row.setAttribute("data-english-marker", marker["kind"].toString())
                }
            }
            val details = text(section, "details", "")
            text(details, "summary", "Tokens and syntax").style.cssText = "cursor:pointer;color:var(--accent);padding:6px 0"
            val body = text(details, "div", "")
            details.addEventListener("toggle", {
                if (present() && details.asDynamic().open == true && body.childNodes.length == 0) syntax(body, value)
            })
        }
        private fun table(parent: HTMLElement, headings: Series<String>): HTMLElement {
            val scroll = text(parent, "div", ""); scroll.style.overflowX = "auto"
            val table = text(scroll, "table", ""); table.style.cssText = "width:100%;border-collapse:collapse;text-align:left;font-size:13px"
            val head = text(table, "thead", ""); val row = text(head, "tr", "")
            for (heading in headings.view) text(row, "th", heading).style.cssText = "padding:7px 8px;border-bottom:1px solid var(--rule);color:var(--dim);font-weight:500"
            return text(table, "tbody", "")
        }
        private fun cells(parent: HTMLElement, values: Series<String>) {
            val row = text(parent, "tr", "")
            for (value in values.view) text(row, "td", value).style.cssText = "padding:6px 8px;border-bottom:1px solid var(--rule);vertical-align:top"
        }
        private fun syntax(parent: HTMLElement, sentence: Map<*, *>) {
            if (sentence["nodes"] == null) text(parent, "p",
                "Legacy token-only format: index:0 labels below are derived for display. Parser copy identities were not recorded, so no copy count is established.", "ds-curate-label")
            DocumentsSyntaxGraph.mount(parent, sentence)
            val tokens = (sentence["tokens"] as List<*>).map { it as Map<*, *> }
            val tokenTable = table(parent, s_["#", "Source word", "Lemma", "POS", "Entity tag", "Text range"])
            for (token in tokens) cells(tokenTable, s_[token["index"].toString(), token["word"].toString(), token["lemma"].toString(),
                token["pos"].toString(), token["ner"].toString(), "${token["begin"]}–${token["end"]}"])
            text(parent, "h4", "Parser dependencies")
            text(parent, "p", "These edges describe the parser's syntax reading, not established legal relationships.", "ds-curate-label")
            val nodes = DocumentsSyntaxGraph.nodes(sentence)
            val byNode = nodes.associateBy { it["id"] as String }
            if (nodes.any { number(it["copy"]) != 0 }) text(parent, "p",
                "Parser copies reuse the original word and source span to represent enhanced syntax. They are not additional words in the document.", "ds-curate-label")
            fun word(id: String): String {
                if (id == "0:0") return "ROOT"
                val node = byNode[id] ?: return "?"
                return node["word"].toString() + if (number(node["copy"]) == 0) "" else " [parser copy]"
            }
            val dependencyTable = table(parent, s_["Governor", "Relation", "Dependent"])
            for (raw in sentence["dependencies"] as List<*>) {
                val edge = raw as Map<*, *>
                val governor = DocumentsSyntaxGraph.nodeId(edge, "governor"); val dependent = DocumentsSyntaxGraph.nodeId(edge, "dependent")
                cells(dependencyTable, s_["$governor · ${word(governor)}", edge["relation"].toString(), "$dependent · ${word(dependent)}"])
            }
        }
    }
}
