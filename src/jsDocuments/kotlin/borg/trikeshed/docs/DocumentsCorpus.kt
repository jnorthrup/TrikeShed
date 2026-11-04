package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.wiki.WikiSurface
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit
import kotlin.math.ln

/** Project-scoped receipt selection and explicit local statistics; storage contains IDs only. */
internal object DocumentsCorpus {
    private val digest = Regex("^sha256:[0-9a-f]{64}$")
    private class Shelf(val ids: MutableList<String>, var notice: String? = null)
    private val shelves = mutableMapOf<String, Shelf>()
    private var owner: Controller? = null
    private fun cid(value: Any?) = value is String && digest.matches(value)
    private fun key(project: String) = "forge.documents.englishCorpus.v1:" + WikiSurface.encode(project)
    private fun shelf(project: String): Shelf = shelves.getOrPut(project) {
        try {
            val stored = window.localStorage.getItem(key(project))
            if (stored == null) Shelf(mutableListOf()) else {
                require(stored.length <= 8192)
                val values = reifyStrict(stored) as? List<*> ?: error("Receipt IDs must be an array")
                require(values.size <= 32 && values.all(::cid) && values.toSet().size == values.size)
                Shelf(values.map { it as String }.toMutableList())
            }
        } catch (_: Throwable) { Shelf(mutableListOf(), "Saved receipt IDs could not be loaded. Add an inspection to choose inputs.") }
    }
    private fun save(project: String, selected: Shelf) {
        try { window.localStorage.setItem(key(project), jsonOf(selected.ids)) }
        catch (_: Throwable) { selected.notice = "Receipt IDs are kept for this session; browser storage is unavailable." }
    }
    fun clear() { owner?.close(); owner = null }
    fun add(project: String, receipt: String): String {
        require(cid(receipt)) { "Inspection receipt identity is unavailable" }
        val controller = owner?.takeIf { it.project == project && it.present() }
        if (controller?.pending == true) return "Wait for the current corpus build before changing its inputs."
        val selected = shelf(project)
        if (receipt in selected.ids) { controller?.reveal(); return "This inspection is already selected in the corpus shelf." }
        if (selected.ids.size >= 32) return "The corpus shelf already has 32 inspections. Remove one before adding another."
        selected.ids.add(receipt); save(project, selected)
        controller?.changed(); controller?.reveal()
        return "Inspection added to this project's corpus shelf. Build its view explicitly."
    }
    fun mount(host: HTMLElement, project: String, source: DocRevision?, generation: Int, current: () -> Boolean) {
        val previous = owner
        val controller = if (previous != null && previous.project == project && previous.source == source && previous.generation == generation) previous
            else { clear(); Controller(project, source, generation).also { owner = it } }
        controller.host = host; controller.current = current; controller.render()
    }

    private class Controller(val project: String, val source: DocRevision?, val generation: Int) {
        lateinit var host: HTMLElement
        var current: () -> Boolean = { false }
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Main)
        private var abort: dynamic = null
        var pending = false
        private var open = false
        private var mode = "snapshot"
        private var topicsEnabled = false
        private var topicCount = 2
        private var iterations = 30
        private var seed = 1
        private var failure: String? = null
        private var result: Map<*, *>? = null
        private var selectedTerm: String? = null
        fun close() { abort?.abort(); job.cancel() }
        fun present() = owner === this && job.isActive && current() && host.asDynamic().isConnected == true
        fun changed() { result = null; failure = null; selectedTerm = null; render() }
        fun reveal() {
            if (!present()) return
            open = true; render(preserveOpen = false)
            host.querySelector("[data-english-corpus]")?.asDynamic()?.scrollIntoView(js("({block:'nearest'})"))
        }
        private fun text(parent: HTMLElement, tag: String, value: String, className: String? = null): HTMLElement =
            (document.createElement(tag) as HTMLElement).also { it.textContent = value; if (className != null) it.className = className; parent.appendChild(it) }
        private fun button(parent: HTMLElement, label: String, disabled: Boolean = pending, action: () -> Unit): HTMLElement =
            text(parent, "button", label, "ds-curate-submit").also { button ->
                button.setAttribute("type", "button"); button.asDynamic().disabled = disabled
                button.style.cssText = "margin:0;padding:7px 11px"
                button.addEventListener("click", { if (present() && !pending) action() })
            }
        private fun integer(value: Any?): Int {
            val n = (value as? Number)?.toDouble() ?: error("Corpus integer is unavailable")
            require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n == n.toInt().toDouble()) { "Invalid corpus integer" }
            return n.toInt()
        }
        private fun rows(value: Any?, limit: Int): List<Map<*, *>> {
            val rows = value as? List<*> ?: error("Corpus rows are unavailable")
            require(rows.size <= limit) { "Corpus exceeds its display budget" }
            return rows.map { it as? Map<*, *> ?: error("Invalid corpus row") }
        }
        private fun sourceId(value: Any?): String = (value as? String)?.takeIf { cid(it) } ?: error("Corpus content identity is unavailable")
        private fun span(text: String, begin: Int, end: Int): String {
            require(begin >= 0 && end > begin && end <= text.length)
            require(!(begin > 0 && text[begin - 1].isHighSurrogate() && text[begin].isLowSurrogate()))
            require(!(end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()))
            return text.substring(begin, end)
        }
        private fun validate(value: Map<*, *>, requested: List<String>, requestedMode: String, topicConfig: Map<String, Any?>?) {
            require(value["schema"] == "trikeshed.english-corpus.v1" && value["reviewStatus"] == "unreviewed" &&
                value["authority"] == "statistics-of-reported-parser-observations" && value["legalMeaning"] == "not-established" && value["countsAreEvidenceConfidence"] == false) { "Unexpected corpus authority or schema" }
            sourceId(value["receiptCid"]); sourceId(value["corpusCid"]); sourceId(value["matrixCid"])
            require(value["receiptCids"] == requested.sorted()) { "Corpus inputs differ from the selected receipt IDs" }
            val descriptor = value["source"] as? Map<*, *> ?: error("Corpus source descriptor is unavailable")
            require(descriptor["selected"] == "english-receipt-corpus" && descriptor["mode"] == requestedMode &&
                descriptor["historical"] == (requestedMode == "snapshot") && descriptor["reviewStatus"] == "unreviewed" &&
                descriptor["parserExecution"] == "not-reverified" && descriptor["producerAuthority"] == "not-established") { "Corpus source status is ambiguous" }
            require(descriptor["currentness"] == if (requestedMode == "current") "checked-before-and-after-build" else "not-checked") { "Corpus currentness differs from the requested mode" }
            val matrix = value["matrix"] as? Map<*, *> ?: error("Corpus matrix is unavailable")
            require(matrix["schema"] == "trikeshed.english-matrix.v1")
            val docs = rows(matrix["documents"], 256)
            val terms = rows(value["terms"], 4096)
            val sentences = rows(value["sentences"], 20_000)
            val occurrences = rows(value["occurrences"], 20_000)
            val vocabulary = matrix["vocabulary"] as? List<*> ?: error("Corpus vocabulary is unavailable")
            require(vocabulary.size == terms.size && integer(matrix["tokenCount"]) <= 20_000)
            val ranges = mutableMapOf<String, Map<*, *>>()
            var total = 0
            for ((index, doc) in docs.withIndex()) {
                require(integer(doc["index"]) == index); sourceId(doc["originalCid"]); sourceId(doc["extractedTextCid"])
                val length = integer(doc["totalLength"])
                for (range in rows(doc["ranges"], 32)) {
                    val receipt = sourceId(range["receiptCid"])
                    require(receipt in requested && ranges.put(receipt, range) == null)
                    require(integer(range["begin"]) < integer(range["end"]) && integer(range["end"]) <= length)
                    val selection = range["selection"] as? Map<*, *> ?: error("Corpus source selection is unavailable")
                    require(selection["project"] == project && selection["cid"] == doc["originalCid"] && selection["id"] is String) { "A selected receipt belongs to another project or source" }
                }
                val counts = doc["counts"] as? List<*> ?: error("Corpus counts are unavailable")
                require(counts.size == terms.size)
                val lengthCount = counts.sumOf { count -> integer(count).also { require(it <= 20_000) } }
                require(lengthCount == integer(doc["includedTokens"]) && lengthCount <= integer(doc["rawTokens"]))
                total += lengthCount; require(total <= 20_000)
            }
            require(ranges.keys == requested.toSet() && total == integer(matrix["tokenCount"]))
            var frequency = 0
            for ((index, term) in terms.withIndex()) {
                require(integer(term["index"]) == index && term["lemma"] is String && vocabulary[index] == term["lemma"])
                val count = integer(term["frequency"]); require(count > 0 && count <= 20_000 && integer(term["documentFrequency"]) in 1..docs.size)
                frequency += count
            }
            require(frequency == total)
            val bySentence = mutableMapOf<String, Map<*, *>>()
            for (sentence in sentences) {
                val id = sourceId(sentence["id"]); require(bySentence.put(id, sentence) == null)
                val receipt = sourceId(sentence["receiptCid"]); val range = ranges[receipt] ?: error("Sentence input was not selected")
                val document = integer(sentence["document"]); require(document in docs.indices)
                val lo = integer(sentence["begin"]); val hi = integer(sentence["end"])
                val quote = sentence["quote"] as? String ?: error("Corpus quotation is unavailable")
                require(lo >= integer(range["begin"]) && hi <= integer(range["end"]) && hi > lo && quote.length == hi - lo)
                val selection = sentence["selection"] as? Map<*, *> ?: error("Concordance source selection is unavailable")
                require(selection == range["selection"] && selection["project"] == project && selection["cid"] == docs[document]["originalCid"])
            }
            val occurrenceIds = mutableSetOf<String>()
            var included = 0
            for (occurrence in occurrences) {
                val sentenceId = sourceId(occurrence["sentenceId"])
                val sentence = bySentence[sentenceId] ?: error("Occurrence has no source sentence")
                require(occurrence["receiptCid"] == sentence["receiptCid"] && occurrence["document"] == sentence["document"])
                val index = integer(occurrence["index"]); require(index > 0 && occurrenceIds.add("$sentenceId:$index"))
                val quote = sentence["quote"] as String; val base = integer(sentence["begin"])
                require(span(quote, integer(occurrence["begin"]) - base, integer(occurrence["end"]) - base) == occurrence["word"]) { "Concordance word differs from its exact source quotation" }
                require(occurrence["lemma"] is String && occurrence["term"] is String && occurrence["pos"] is String && occurrence["included"] is Boolean)
                if (occurrence["included"] == true) included++
            }
            require(included == total && integer(value["rawTokenCount"]) == occurrences.size)
            val topics = value["topics"]
            if (topicConfig == null) require(topics == null && value["topicCid"] == null) { "Topics were not requested" }
            else {
                sourceId(value["topicCid"])
                val model = topics as? Map<*, *> ?: error("Requested topic model is unavailable")
                require(model["schema"] == "trikeshed.english-lda.v1" && model["matrixCid"] == value["matrixCid"] && model["corpusCid"] == value["corpusCid"] && model["authority"] == "descriptive-statistics-only" && model["countsAreEvidenceConfidence"] == false)
                val config = model["config"] as? Map<*, *> ?: error("Topic settings are unavailable")
                require(topicConfig.all { (key, expected) -> (config[key] as? Number)?.toDouble() == (expected as? Number)?.toDouble() }) { "Topic settings differ from the requested experiment" }
                require(model["vocabulary"] == vocabulary && integer(model["tokenCount"]) == total)
                val topicRows = rows(model["topics"], 16)
                val count = integer(config["topics"])
                require(topicRows.size == count)
                for ((index, topic) in topicRows.withIndex()) {
                    require(integer(topic["topic"]) == index && integer(topic["tokens"]) <= total)
                    for (term in rows(topic["terms"], 128)) {
                        val word = integer(term["index"]); require(word in vocabulary.indices && term["lemma"] == vocabulary[word])
                        val probability = (term["probability"] as? Number)?.toDouble() ?: error("Topic probability is unavailable")
                        require(probability.isFinite() && probability in 0.0..1.0 && integer(term["count"]) <= total)
                    }
                }
                val mixtures = rows(model["documentTopics"], 256)
                require(mixtures.size == docs.size)
                for ((index, mixture) in mixtures.withIndex()) {
                    require(integer(mixture["document"]) == index && mixture["priorOnly"] is Boolean)
                    val probabilities = mixture["probabilities"] as? List<*> ?: error("Topic mixture is unavailable")
                    require(probabilities.size == count)
                    var sum = 0.0
                    for (raw in probabilities) {
                        val probability = (raw as? Number)?.toDouble() ?: error("Topic mixture is unavailable")
                        require(probability.isFinite() && probability in 0.0..1.0); sum += probability
                    }
                    require(kotlin.math.abs(sum - 1.0) < 1e-8)
                }
            }
        }
        private fun build() {
            val requested = shelf(project).ids.toList()
            if (requested.isEmpty()) return
            val requestedMode = mode
            val topics = if (topicsEnabled) {
                if (topicCount !in 1..16 || iterations !in 1..100 || seed < 0) { failure = "Choose 1–16 topics, 1–100 iterations and a nonnegative integer seed."; render(); return }
                _m["topics" j topicCount, "iterations" j iterations, "seed" j seed, "alpha" j .1, "beta" j .01]
            } else null
            pending = true; failure = null; result = null; selectedTerm = null; render()
            scope.launch {
                try {
                    abort = js("new AbortController()")
                    val init = RequestInit(method = "POST", body = jsonOf(_m["receiptCids" j requested, "mode" j requestedMode, "topics" j topics]),
                        headers = Headers().apply { append("Content-Type", "application/json") })
                    init.asDynamic().signal = abort.signal
                    val response = window.fetch("/api/documents/english/corpus", init).await()
                    val body = response.text().await(); require(body.length <= 4 * 1024 * 1024) { "Corpus response exceeds 4MiB" }
                    val value = reifyStrict(body) as? Map<*, *> ?: error("Unreadable corpus response")
                    require(response.ok && value["error"] == null) { (value["detail"] ?: value["error"] ?: "Corpus build failed (HTTP ${response.status})").toString() }
                    validate(value, requested, requestedMode, topics)
                    if (present()) result = value
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (present()) failure = error.message ?: "Corpus view is unavailable" }
                finally { if (present()) { pending = false; render() } }
            }
        }
        fun render(preserveOpen: Boolean = true) {
            if (!present()) return
            // A native details toggle may still be queued when an input change repaints the panel.
            if (preserveOpen) host.querySelector("[data-english-corpus]")?.let { shown -> open = shown.asDynamic().open == true }
            host.textContent = ""
            val details = text(host, "details", ""); details.setAttribute("data-english-corpus", "1"); details.asDynamic().open = open
            details.style.cssText = "max-width:1080px;margin:12px auto 20px;padding:14px 24px;border:1px solid var(--rule);border-radius:8px;overflow-wrap:anywhere"
            val selected = shelf(project)
            text(details, "summary", "English corpus · ${selected.ids.size} selected inspections").style.cssText = "cursor:pointer;color:var(--accent);font-weight:600"
            details.addEventListener("toggle", { if (present() && details.asDynamic().isConnected == true) open = details.asDynamic().open == true })
            text(details, "p", "Choose retained inspections, build an explicit dataset, and follow word counts back to their source sentences. No generative model or reasoning admission is used.")
            text(details, "p", "This shelf stores receipt IDs only in this browser, scoped to $project. The host rechecks source bytes, structure and inputs on every build.", "ds-curate-label")
            selected.notice?.let { text(details, "p", it, "ds-curate-label") }
            if (selected.ids.isEmpty()) text(details, "p", "Inspect a document, then choose Use this inspection in corpus. No corpus is selected automatically.")
            else {
                val list = text(details, "ol", "")
                for (receipt in selected.ids) {
                    val row = text(list, "li", ""); row.style.cssText = "margin:8px 0"
                    text(row, "code", receipt).setAttribute("data-corpus-selected-id", receipt)
                    button(row, "Remove", pending) { selected.ids.remove(receipt); save(project, selected); changed() }.style.marginLeft = "10px"
                }
            }
            val controls = text(details, "div", ""); controls.style.cssText = "display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin:16px 0"
            val modeLabel = text(controls, "label", "Source mode ")
            val input = document.createElement("select") as HTMLSelectElement; input.setAttribute("data-corpus-mode", "1"); input.disabled = pending
            for ((value, label) in _l["snapshot" j "Retained snapshots", "current" j "Require current sources"]) {
                val option = document.createElement("option"); option.setAttribute("value", value); option.textContent = label; input.appendChild(option)
            }
            input.value = mode; input.style.cssText = "background:var(--side);color:var(--ink);border:1px solid var(--rule);padding:7px;border-radius:5px"; modeLabel.appendChild(input)
            input.addEventListener("change", { if (present() && !pending) { mode = input.value; changed() } })
            val topicLabel = text(controls, "label", "")
            val enable = document.createElement("input") as HTMLInputElement; enable.type = "checkbox"; enable.checked = topicsEnabled; enable.disabled = pending; enable.setAttribute("data-corpus-topics-enable", "1"); topicLabel.appendChild(enable)
            text(topicLabel, "span", " Fit local statistical topics (LDA)")
            enable.addEventListener("change", { if (present() && !pending) { topicsEnabled = enable.checked; changed() } })
            if (topicsEnabled) {
                integerInput(controls, "Topics", "data-corpus-topic-count", topicCount, 1, 16) { topicCount = it }
                integerInput(controls, "Iterations", "data-corpus-iterations", iterations, 1, 100) { iterations = it }
                integerInput(controls, "Seed", "data-corpus-seed", seed, 0, Int.MAX_VALUE) { seed = it }
                text(details, "p", "LDA is an unsupervised grouping experiment, not English comprehension, legal meaning or a confidence score. Fixed defaults: 2 topics, 30 iterations, seed 1, alpha 0.1 and beta 0.01.", "ds-curate-label")
            }
            button(controls, "Build corpus view", pending || selected.ids.isEmpty(), ::build).setAttribute("data-corpus-build", "1")
            if (pending) text(details, "p", "Building the selected corpus locally…").setAttribute("role", "status")
            failure?.let { text(details, "p", it, "ds-curate-error").setAttribute("role", "status") }
            result?.let { statistics(details, it) }
        }
        private fun integerInput(parent: HTMLElement, label: String, attribute: String, value: Int, min: Int, max: Int, set: (Int) -> Unit) {
            val holder = text(parent, "label", "$label ")
            val input = document.createElement("input") as HTMLInputElement; input.type = "number"; input.min = min.toString(); input.max = max.toString(); input.step = "1"; input.value = value.toString(); input.disabled = pending
            input.setAttribute(attribute, "1"); input.className = "ds-curate-name"; input.style.cssText = "width:85px;padding:7px"; holder.appendChild(input)
            input.addEventListener("input", { if (present() && !pending) set(input.value.toIntOrNull() ?: -1) })
            input.addEventListener("change", { if (present() && !pending && input.asDynamic().isConnected == true) changed() })
        }
        private fun statistics(parent: HTMLElement, value: Map<*, *>) {
            val matrix = value["matrix"] as Map<*, *>; val docs = rows(matrix["documents"], 256)
            val descriptor = value["source"] as Map<*, *>
            text(parent, "h3", "Unreviewed corpus view", "ds-curate-h3").setAttribute("data-corpus-result", "1")
            text(parent, "p", "${docs.size} source contents · ${matrix["tokenCount"]} included original tokens · ${value["rawTokenCount"]} observed tokens")
            text(parent, "p", if (descriptor["historical"] == true) "Retained snapshot mode: current document revisions were not checked." else "Current-source mode: revisions were checked before and after this build.")
            text(parent, "p", "Source bytes and spans are structurally checked. Parser execution and document authority are not authenticated. Counts and topic mixtures do not become legal facts or NARS confidence.", "ds-curate-label")
            text(parent, "p", "Corpus receipt: ${value["receiptCid"]} · matrix: ${value["matrixCid"]}", "ds-cid")
            val coverage = text(parent, "details", ""); text(coverage, "summary", "Input coverage and policy").style.cursor = "pointer"
            text(coverage, "p", "Only the selected source ranges are counted. Reported lemmas are kept as recorded; the default removes no stopwords and protects legal guard markers. Parser copy nodes are never extra words.")
            for (doc in docs) {
                val range = rows(doc["ranges"], 32).firstOrNull(); val selection = range?.get("selection") as? Map<*, *>
                text(coverage, "p", "${selection?.get("id") ?: doc["originalCid"]}: ${doc["coveredUnits"]}/${doc["totalLength"]} text units · " + if (doc["complete"] == true) "full text covered" else "partial coverage")
                text(coverage, "p", "Original ${doc["originalCid"]} · text ${doc["extractedTextCid"]}", "ds-cid")
            }
            text(coverage, "pre", jsonOf(matrix["policy"])).style.cssText = "white-space:pre-wrap;font-size:12px"
            val terms = rows(value["terms"], 4096).sortedWith(compareByDescending<Map<*, *>> { integer(it["frequency"]) }.thenBy { it["lemma"].toString() })
            text(parent, "h3", "Words in the selected ranges", "ds-curate-h3")
            text(parent, "p", "Size represents term occurrence count (TF), not importance or legal force. Showing ${minOf(128, terms.size)}/${terms.size} terms; click one for exact concordance.", "ds-curate-label")
            val cloud = text(parent, "div", ""); cloud.setAttribute("data-corpus-wordcloud", "1"); cloud.style.cssText = "display:flex;align-items:baseline;justify-content:center;flex-wrap:wrap;gap:10px;padding:20px;border:1px solid var(--rule);border-radius:8px;background:var(--side)"
            val maximum = terms.firstOrNull()?.let { integer(it["frequency"]) } ?: 1
            for (term in terms.take(128)) {
                val lemma = term["lemma"] as String; val frequency = integer(term["frequency"])
                val word = button(cloud, "$lemma · $frequency", false) { selectedTerm = lemma; render() }
                word.setAttribute("data-corpus-term", lemma); word.setAttribute("title", "TF $frequency; present in ${term["documentFrequency"]} selected source contents")
                word.style.cssText = "font-size:${14 + 26 * ln(1.0 + frequency) / ln(1.0 + maximum)}px;padding:5px 8px;background:transparent;color:var(--accent);border:1px solid ${if (selectedTerm == lemma) "var(--accent)" else "transparent"};border-radius:5px;cursor:pointer"
            }
            if (terms.isEmpty()) text(cloud, "p", "No lexical terms remain under the selected policy.")
            val precise = text(parent, "details", ""); text(precise, "summary", "Term counts").style.cursor = "pointer"
            val table = text(precise, "table", ""); table.style.cssText = "width:100%;border-collapse:collapse;text-align:left;font-size:13px"
            val header = text(text(table, "thead", ""), "tr", "")
            for (label in _l["Lemma", "Occurrences", "Source contents"]) text(header, "th", label).style.padding = "8px"
            val body = text(table, "tbody", "")
            for (term in terms.take(128)) {
                val row = text(body, "tr", ""); val lemma = term["lemma"] as String
                button(text(row, "td", ""), lemma, false) { selectedTerm = lemma; render() }
                text(row, "td", term["frequency"].toString()); text(row, "td", term["documentFrequency"].toString())
            }
            selectedTerm?.let { concordance(parent, value, it) }
            (value["topics"] as? Map<*, *>)?.let { topics(parent, it, docs) }
        }
        private fun concordance(parent: HTMLElement, value: Map<*, *>, term: String) {
            val sentences = rows(value["sentences"], 20_000).associateBy { it["id"] as String }
            val occurrences = rows(value["occurrences"], 20_000).filter { it["included"] == true && it["term"] == term }
            val grouped = occurrences.groupBy { it["sentenceId"] as String }
            val section = text(parent, "section", ""); section.setAttribute("data-corpus-concordance", term)
            text(section, "h3", "Concordance · $term", "ds-curate-h3")
            text(section, "p", "${occurrences.size} original token occurrences in ${grouped.size} retained sentences. Showing ${minOf(32, grouped.size)} sentences with all their original wording.")
            for ((id, matches) in grouped.entries.take(32)) {
                val sentence = sentences.getValue(id); val selection = sentence["selection"] as Map<*, *>
                val card = text(section, "article", "", "ds-run"); card.setAttribute("data-corpus-concordance-sentence", id)
                text(card, "h4", "${selection["id"]} · text ${sentence["begin"]}–${sentence["end"]}")
                text(card, "blockquote", sentence["quote"] as String).style.cssText = "white-space:pre-wrap;margin:10px 0;line-height:1.6"
                text(card, "p", "Matched source words: " + matches.joinToString("; ") { "${it["word"]} [${it["begin"]},${it["end"]})" }, "ds-curate-label")
                text(card, "p", "Inspection ${sentence["receiptCid"]} · original ${selection["cid"]}", "ds-cid")
                val link = text(card, "a", "Open current document")
                link.setAttribute("href", "/documents#${WikiSurface.encode(project)}/${WikiSurface.encode(selection["id"] as String)}")
                link.style.color = "var(--accent)"
                text(card, "p", "The link opens the current document. This quotation remains evidence from the retained inspection snapshot.", "ds-curate-label")
            }
        }
        private fun topics(parent: HTMLElement, model: Map<*, *>, docs: List<Map<*, *>>) {
            val section = text(parent, "section", ""); section.setAttribute("data-corpus-topics", "1")
            text(section, "h3", "Statistical topics · LDA", "ds-curate-h3")
            text(section, "p", "Descriptive word groups from this exact matrix, not legal conclusions or a measure of comprehension. ${jsonOf(model["config"])}", "ds-curate-label")
            for (topic in rows(model["topics"], 16)) {
                val card = text(section, "article", "", "ds-run")
                text(card, "h4", "Topic ${integer(topic["topic"]) + 1} · ${topic["tokens"]} assigned tokens")
                val terms = rows(topic["terms"], 128).take(8)
                text(card, "p", terms.joinToString(" · ") { "${it["lemma"]} (${((it["probability"] as Number).toDouble() * 1000).toInt() / 10.0}%)" })
            }
            text(section, "h4", "Source topic mixtures")
            for (mixture in rows(model["documentTopics"], 256)) {
                val index = integer(mixture["document"]); require(index in docs.indices)
                val range = rows(docs[index]["ranges"], 32).firstOrNull(); val selection = range?.get("selection") as? Map<*, *>
                val probabilities = mixture["probabilities"] as? List<*> ?: error("Topic mixture is unavailable")
                text(section, "p", "${selection?.get("id") ?: "Source $index"}: " + probabilities.mapIndexed { topic, weight -> "T${topic + 1} ${((weight as Number).toDouble() * 1000).toInt() / 10.0}%" }.joinToString(" · ") +
                    if (mixture["priorOnly"] == true) " · prior only, no included words" else "")
            }
        }
    }
}
