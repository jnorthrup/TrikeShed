<<<<<<< HEAD
package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
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
        private var failure: String? = null
        private var result: Map<*, *>? = null
        private var corpusNotice: String? = null
        fun close() { abort?.abort(); job.cancel() }
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
        private fun inspect() {
            if (!validRange()) { failure = "Choose a nonempty range of at most 16,384 characters without splitting a character."; render(); return }
            val requestedBegin = begin; val requestedEnd = end
            pending = true; failure = null; render()
            scope.launch {
                try {
                    abort = js("new AbortController()")
                    val init = RequestInit(method = "POST", body = jsonOf(selection() + _m["begin" j requestedBegin, "end" j requestedEnd]),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = abort.signal
                    val response = window.fetch("/api/documents/english", init).await()
                    val value = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Unreadable English inspection response")
                    require(response.ok && value["error"] == null) { (value["detail"] ?: value["error"] ?: "Local English inspection failed (HTTP ${response.status})").toString() }
                    validate(value, requestedBegin, requestedEnd)
                    if (present()) { result = value; corpusNotice = null }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (present()) failure = error.message ?: "Local English inspection is unavailable" }
                finally { if (present()) { pending = false; render() } }
            }
        }
        private fun rangeInput(parent: HTMLElement, label: String, attribute: String, value: Int, assign: (Int) -> Unit) {
            val holder = text(parent, "label", label, "ds-curate-label")
            holder.style.cssText = "display:flex;gap:8px;align-items:center;margin:0"
            val input = document.createElement("input") as HTMLInputElement
            input.type = "number"; input.min = "0"; input.step = "1"; input.value = value.toString()
            input.className = "ds-curate-name"; input.style.cssText = "width:110px;padding:7px 9px"
            input.disabled = pending; input.setAttribute(attribute, "1"); holder.appendChild(input)
            input.addEventListener("input", { if (present() && !pending) assign(input.value.toIntOrNull() ?: -1) })
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
            button.setAttribute("type", "button"); button.asDynamic().disabled = pending || preview.isEmpty()
            button.style.marginTop = "0"
            button.addEventListener("click", { if (present() && !pending) inspect() })
            text(section, "p", "Range uses text character offsets, ending before End. Each inspection covers at most 16,384 characters; the result reports whether it covers the full stored text.", "ds-curate-label")
            if (pending) text(section, "p", "Inspecting English locally…").setAttribute("role", "status")
            failure?.let { text(section, "p", it, "ds-curate-error").setAttribute("role", "status") }
            val value = result ?: return
            val range = value["range"] as Map<*, *>
            text(section, "h3", "Unreviewed inspection", "ds-curate-h3")
            text(section, "p", "Text range ${range["begin"]}–${range["end"]} of ${range["totalLength"]} characters · " +
                if (range["complete"] == true) "full text covered" else "partial coverage")
            text(section, "p", "Evidence receipt: ${value["receiptCid"]}", "ds-cid")
            val parser = value["parser"] as? Map<*, *>
            text(section, "p", "Reader: ${parser?.get("processor") ?: "not reported"}. Markers are literal word and phrase candidates; their scope and legal effect require review.", "ds-curate-label")
            val use = text(section, "button", "Use this inspection in corpus", "ds-curate-submit")
            use.setAttribute("type", "button"); use.setAttribute("data-corpus-use-inspection", "1"); use.asDynamic().disabled = pending
            use.style.cssText = "margin:10px 0;padding:8px 12px"
            use.addEventListener("click", {
                if (present() && !pending) {
                    corpusNotice = runCatching { DocumentsCorpus.add(source.project, value["receiptCid"] as String) }
                        .getOrElse { it.message ?: "Inspection could not be added to the corpus shelf" }
                    render()
                }
            })
            corpusNotice?.let { text(section, "p", it, "ds-curate-label").setAttribute("role", "status") }
            for (raw in value["sentences"] as List<*>) sentence(section, raw as Map<*, *>)
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
||||||| 62eb34b08
=======
package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
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
        private var failure: String? = null
        private var result: Map<*, *>? = null
        fun close() { abort?.abort(); job.cancel() }
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
            var tokenCount = 0; var dependencyCount = 0; var markerCount = 0
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
                for (item in dependencies) {
                    val edge = item as? Map<*, *> ?: error("Invalid syntax observation")
                    val governor = number(edge["governor"]); val dependent = number(edge["dependent"])
                    require((governor == 0 || governor in indices) && dependent in indices && edge["relation"] is String) { "Syntax observation has an unknown token" }
                }
            }
        }
        private fun inspect() {
            if (!validRange()) { failure = "Choose a nonempty range of at most 16,384 characters without splitting a character."; render(); return }
            val requestedBegin = begin; val requestedEnd = end
            pending = true; failure = null; render()
            scope.launch {
                try {
                    abort = js("new AbortController()")
                    val init = RequestInit(method = "POST", body = jsonOf(selection() + _m["begin" j requestedBegin, "end" j requestedEnd]),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = abort.signal
                    val response = window.fetch("/api/documents/english", init).await()
                    val value = reifyStrict(response.text().await()) as? Map<*, *> ?: error("Unreadable English inspection response")
                    require(response.ok && value["error"] == null) { (value["detail"] ?: value["error"] ?: "Local English inspection failed (HTTP ${response.status})").toString() }
                    validate(value, requestedBegin, requestedEnd)
                    if (present()) result = value
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (present()) failure = error.message ?: "Local English inspection is unavailable" }
                finally { if (present()) { pending = false; render() } }
            }
        }
        private fun rangeInput(parent: HTMLElement, label: String, attribute: String, value: Int, assign: (Int) -> Unit) {
            val holder = text(parent, "label", label, "ds-curate-label")
            holder.style.cssText = "display:flex;gap:8px;align-items:center;margin:0"
            val input = document.createElement("input") as HTMLInputElement
            input.type = "number"; input.min = "0"; input.step = "1"; input.value = value.toString()
            input.className = "ds-curate-name"; input.style.cssText = "width:110px;padding:7px 9px"
            input.disabled = pending; input.setAttribute(attribute, "1"); holder.appendChild(input)
            input.addEventListener("input", { if (present() && !pending) assign(input.value.toIntOrNull() ?: -1) })
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
            button.setAttribute("type", "button"); button.asDynamic().disabled = pending || preview.isEmpty()
            button.style.marginTop = "0"
            button.addEventListener("click", { if (present() && !pending) inspect() })
            text(section, "p", "Range uses text character offsets, ending before End. Each inspection covers at most 16,384 characters; the result reports whether it covers the full stored text.", "ds-curate-label")
            if (pending) text(section, "p", "Inspecting English locally…").setAttribute("role", "status")
            failure?.let { text(section, "p", it, "ds-curate-error").setAttribute("role", "status") }
            val value = result ?: return
            val range = value["range"] as Map<*, *>
            text(section, "h3", "Unreviewed inspection", "ds-curate-h3")
            text(section, "p", "Text range ${range["begin"]}–${range["end"]} of ${range["totalLength"]} characters · " +
                if (range["complete"] == true) "full text covered" else "partial coverage")
            text(section, "p", "Evidence receipt: ${value["receiptCid"]}", "ds-cid")
            val parser = value["parser"] as? Map<*, *>
            text(section, "p", "Reader: ${parser?.get("processor") ?: "not reported"}. Markers are literal word and phrase candidates; their scope and legal effect require review.", "ds-curate-label")
            for (raw in value["sentences"] as List<*>) sentence(section, raw as Map<*, *>)
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
            DocumentsSyntaxGraph.mount(parent, sentence)
            val tokens = (sentence["tokens"] as List<*>).map { it as Map<*, *> }
            val tokenTable = table(parent, s_["#", "Source word", "Lemma", "POS", "Entity tag", "Text range"])
            for (token in tokens) cells(tokenTable, s_[token["index"].toString(), token["word"].toString(), token["lemma"].toString(),
                token["pos"].toString(), token["ner"].toString(), "${token["begin"]}–${token["end"]}"])
            text(parent, "h4", "Parser dependencies")
            text(parent, "p", "These edges describe the parser's syntax reading, not established legal relationships.", "ds-curate-label")
            val byIndex = tokens.associateBy { number(it["index"]) }
            fun word(index: Int) = if (index == 0) "ROOT" else byIndex[index]?.get("word")?.toString() ?: "?"
            val dependencyTable = table(parent, s_["Governor", "Relation", "Dependent"])
            for (raw in sentence["dependencies"] as List<*>) {
                val edge = raw as Map<*, *>
                val governor = number(edge["governor"]); val dependent = number(edge["dependent"])
                cells(dependencyTable, s_["$governor · ${word(governor)}", edge["relation"].toString(), "$dependent · ${word(dependent)}"])
            }
        }
    }
}
>>>>>>> 6bc57dbcbd743793fb3096ae68afebf3a2330e1d
