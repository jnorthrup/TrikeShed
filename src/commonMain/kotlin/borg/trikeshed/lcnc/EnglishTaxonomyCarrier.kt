package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.rdf.*

/** A declared translation of a small English fragment, never an English entailment or a sink. */
object EnglishTaxonomyCarrier {
    const val POLICY = "english-present-copula-taxonomy/1"
    const val MARKER = "englishTaxonomyCarrier"
    const val DECLARATION = "englishTaxonomyDeclarationCid"
    const val MODEL = "english-taxonomy-carrier/1"
    const val SUFFIX = ".english-taxonomy.kif"
    const val MAX_TEXT = 16_384
    const val MAX_FORMS = 32
    const val MAX_BYTES = 65_536
    const val MAX_RECORD = 262_144

    class Form(val sentence: Map<String, Any?>, val subject: Map<String, Any?>,
        val predicate: Map<String, Any?>, val copula: Map<String, Any?>,
        val bindings: List<Map<String, Any?>>) {
        val id: String get() = "sentence:${sentence["index"]}"
    }
    class Inspection(val snapshot: EnglishObservationGraph.Snapshot, val forms: List<Form>,
        val rows: List<Map<String, Any?>>, val exclusions: List<String>)
    class Built(val declaration: Map<String, Any?>, val kif: String, val contextKey: ContentId,
        val forms: List<Map<String, Any?>>, val snapshot: EnglishObservationGraph.Snapshot)
    class Verified(val receiptCid: ContentId, val record: DocumentCurationRecord,
        val declarationCid: ContentId, val built: Built)

    private fun map(value: Any?) = EnglishObservationGraph.map(value)
    private fun list(value: Any?) = EnglishObservationGraph.list(value)
    private fun int(value: Any?) = EnglishObservationGraph.integer(value)
    private fun string(value: Any?) = EnglishObservationGraph.string(value)
    private fun keys(value: Map<String, Any?>, expected: Set<String>) {
        require(value.keys == expected) { "Unexpected or missing translation declaration fields" }
    }
    private fun read(cas: CasStore, cid: ContentId, cap: Int): ByteArray {
        val reader = cas as? BoundedCasReader ?: error("Bounded translation reads required")
        return requireNotNull(reader.getBounded(cid, cap)) { "Retained translation evidence unavailable" }.also {
            require(it.size <= cap && ContentId.of(it) == cid) { "Translation evidence identity differs" }
        }
    }

    /** Raw observations remain readable when this deliberately small projection is unsupported. */
    fun inspect(snapshot: EnglishObservationGraph.Snapshot): Inspection {
        val inventory = snapshot.inventory
        val range = map(inventory["range"])
        val reasons = mutableListOf<String>()
        if (range["complete"] != true || int(range["begin"]) != 0 || int(range["end"]) != snapshot.source.text.length)
            reasons.add("complete-current-English-inspection-required")
        if (snapshot.source.text.length > MAX_TEXT) reasons.add("English-source-exceeds-16KiB-character-profile")
        if (snapshot.legacyIdentity || map(inventory["coverage"])["parserNodeIdentityReported"] != true ||
            map(inventory["coverage"])["parserDependencyNodeIdentity"] != "token-index+copy-count/1")
            reasons.add("reported-full-dependency-node-identity-required")
        val sentences = list(inventory["sentences"]).map(::map)
        if (sentences.isEmpty() || sentences.size > MAX_FORMS) reasons.add("choose-a-source-of-1-to-32-simple-sentences")
        var end = 0
        val forms = mutableListOf<Form>()
        val rows = mutableListOf<Map<String, Any?>>()
        for (sentence in sentences) {
            val begin = int(sentence["begin"])
            if (begin < end || !snapshot.source.text.substring(end, begin).all(Char::isWhitespace))
                reasons.add("source-content-outside-inspected-sentences")
            end = int(sentence["end"])
            val excluded = mutableListOf<String>()
            if (list(sentence["markers"]).isNotEmpty()) excluded.add("qualified-or-referenced-English-is-outside-profile")
            val nodes = list(sentence["nodes"]).map(::map)
            val edges = list(sentence["dependencies"]).map(::map)
            if (nodes.any { int(it["copy"]) != 0 } || edges.any { int(it["governorCopy"]) != 0 || int(it["dependentCopy"]) != 0 })
                excluded.add("copied-parser-nodes-are-observations-only")
            val tokens = list(sentence["tokens"]).map(::map)
            var cursor = begin
            for (token in tokens) {
                val at = int(token["begin"])
                if (at < cursor || !snapshot.source.text.substring(cursor, at).all(Char::isWhitespace))
                    excluded.add("source-content-outside-token-bindings")
                cursor = int(token["end"])
            }
            if (cursor != end || tokens.firstOrNull()?.get("begin") != begin)
                excluded.add("complete-sentence-token-coverage-required")
            fun article(token: Map<String, Any?>) = token["pos"] == "DT" && string(token["word"]).lowercase() in _s["a", "an", "the"]
            fun noun(token: Map<String, Any?>) = string(token["pos"]).startsWith("NN") && string(token["word"]).all(Char::isLetter)
            var at = 0
            val subjectArticle = tokens.getOrNull(at)?.takeIf(::article)?.also { at++ }
            val subject = tokens.getOrNull(at++)
            val copula = tokens.getOrNull(at++)
            val predicateArticle = tokens.getOrNull(at)?.takeIf(::article)?.also { at++ }
            val predicate = tokens.getOrNull(at++)
            val period = tokens.getOrNull(at++)
            val simple = subject != null && noun(subject) && predicate != null && noun(predicate) && copula != null &&
                string(copula["word"]).lowercase() in _s["is", "are"] && string(copula["lemma"]).lowercase() == "be" &&
                copula["pos"] in _s["VBZ", "VBP"] && period?.get("word") == "." && at == tokens.size
            if (!simple) excluded.add("only-simple-positive-present-nominal-copulas-supported")
            var form: Form? = null
            if (simple && excluded.isEmpty()) {
                fun node(token: Map<String, Any?>) = nodes.single { it["id"] == "${token["index"]}:0" }
                val s = node(requireNotNull(subject)); val p = node(requireNotNull(predicate)); val c = node(requireNotNull(copula))
                fun edge(g: Any?, d: Any?, relation: String) = _m["governorNode" j g, "dependentNode" j d, "relation" j relation]
                val expected = mutableListOf(edge("0:0", p["id"], "root"), edge(p["id"], s["id"], "nsubj"),
                    edge(p["id"], c["id"], "cop"), edge(p["id"], node(requireNotNull(period))["id"], "punct"))
                subjectArticle?.let { expected.add(edge(s["id"], node(it)["id"], "det")) }
                predicateArticle?.let { expected.add(edge(p["id"], node(it)["id"], "det")) }
                val actual = edges.map { edge(it["governorNode"], it["dependentNode"], string(it["relation"])) }
                if (actual.size != expected.size || actual.toSet() != expected.toSet())
                    excluded.add("ambiguous-or-unsupported-dependency-roles")
                else form = Form(sentence, s, p, c, actual)
            }
            if (form != null) forms.add(form)
            rows.add(_m["id" j "sentence:${sentence["index"]}", "sentenceIndex" j sentence["index"],
                "begin" j begin, "end" j end, "quote" j sentence["quote"], "quotation" j sentence["quotation"],
                "eligible" j (form != null), "exclusionReasons" j excluded.distinct(),
                "subjectNode" j form?.subject?.get("id"), "subjectWord" j form?.subject?.get("word"),
                "predicateNode" j form?.predicate?.get("id"), "predicateWord" j form?.predicate?.get("word"),
                "copulaNode" j form?.copula?.get("id"), "copulaWord" j form?.copula?.get("word"),
                "bindings" j (form?.bindings ?: emptyList<Map<String, Any?>>())])
            if (excluded.isNotEmpty()) reasons.add("source-has-unsupported-sentence")
        }
        if (!snapshot.source.text.substring(end).all(Char::isWhitespace)) reasons.add("source-content-outside-inspected-sentences")
        return Inspection(snapshot, forms, rows, reasons.distinct())
    }

    fun candidates(snapshot: EnglishObservationGraph.Snapshot): Map<String, Any?> = inspect(snapshot).let {
        _m["inspectionCid" j snapshot.receiptCid.value, "profile" j POLICY,
            "eligible" j it.exclusions.isEmpty(), "exclusionReasons" j it.exclusions,
            "source" j snapshot.descriptor("current"), "sentences" j it.rows,
            "authority" j "unreviewed-translation-candidates"]
    }

    private fun iri(value: Any?): String {
        val text = string(value)
        require(text.length in 1..256) { "Translation IRI must be 1 to 256 characters" }
        text.encodeToByteArray(throwOnInvalidSequence = true)
        val terms = TurtleRdf.parseStrict("<$text> <urn:translation:check> <urn:translation:check> .").triples
        require(terms.size == 1 && terms.single().s == RdfTerm.Iri(text)) { "An exact absolute IRI is required" }
        val formula = KifSemantic.decode("(goal $text)").formula as? KifFormula.Predicate
        require(formula?.arguments?.singleOrNull() == KifTerm.Constant(text)) { "IRI is outside the supported ground KIF constant spelling" }
        return text
    }

    private fun build(snapshot: EnglishObservationGraph.Snapshot, context: String,
        declarations: List<Map<String, Any?>>): Built {
        val inspected = inspect(snapshot)
        require(inspected.exclusions.isEmpty()) { "English translation profile refused: ${inspected.exclusions.joinToString() }" }
        val contextIri = iri(context)
        require(declarations.size in 1..MAX_FORMS && declarations.map { it["id"] }.distinct().size == declarations.size) { "Choose distinct English occurrences" }
        val identity = _m["project" j snapshot.selection.project, "id" j snapshot.selection.id]
        val contextKey = ContentId.of(CanonicalCbor.encodeMap(_m["kind" j "english-taxonomy-context/1", "source" j identity, "iri" j contextIri]))
        val choices = inspected.forms.associateBy { it.id }
        val sorts = mutableMapOf<String, String>()
        val kif = StringBuilder()
        val forms = declarations.map { declaration ->
            keys(declaration, _s["id", "relation", "subjectIri", "subjectSort", "objectIri", "objectSort"])
            val form = requireNotNull(choices[string(declaration["id"])]) { "Unknown or ineligible English occurrence" }
            val subject = iri(declaration["subjectIri"]); val obj = iri(declaration["objectIri"])
            val relation = string(declaration["relation"]); val sort = string(declaration["subjectSort"])
            require(declaration["objectSort"] == "class" &&
                (relation == "instance" && sort == "individual" || relation == "subclass" && sort == "class")) { "Explicit consistent instance/subclass and term sorts required" }
            for ((term, termSort) in s_[subject j sort, obj j "class"].view) {
                require(sorts[term] == null || sorts[term] == termSort) { "One IRI cannot be both class and individual in this context" }
                sorts[term] = termSort
            }
            val frozen = _m["id" j form.id, "relation" j relation, "subjectIri" j subject,
                "subjectSort" j sort, "objectIri" j obj, "objectSort" j "class"]
            val begin = kif.length
            val expression = KifExpr.ListExpr(_l[KifExpr.Atom(relation), KifExpr.Atom(subject), KifExpr.Atom(obj)]).toKifString()
            kif.append(expression)
            val end = kif.length; kif.append('\n')
            _m["id" j form.id, "sentenceIndex" j form.sentence["index"], "englishQuotation" j form.sentence["quotation"],
                "markers" j form.sentence["markers"], "subject" j form.subject, "predicate" j form.predicate,
                "copula" j form.copula, "bindings" j form.bindings, "declared" j frozen,
                "kifBegin" j begin, "kifEnd" j end, "kifQuote" j expression]
        }
        val text = kif.toString()
        require(text.encodeToByteArray().size <= MAX_BYTES) { "Derived KIF exceeds 64KiB" }
        val payload = _m["kind" j "english-taxonomy-declaration/1", "policy" j POLICY, "reviewStatus" j "proposed",
            "inspectionCid" j snapshot.receiptCid.value, "contextIri" j contextIri, "contextKey" j contextKey.value,
            "source" j (DocumentCuratorCodec.source(snapshot.source) - "text"),
            "sourceRevision" j DocumentReviewCandidate.revision(snapshot.source),
            "unionPolicy" j "single-carrier-pure-taxonomy-plan-only/1", "forms" j forms,
            "derivedKifCid" j ContentId.of(text.encodeToByteArray()).value]
        require(CanonicalCbor.encodeMap(payload).size <= MAX_BYTES) { "Translation declaration exceeds 64KiB" }
        return Built(payload, text, contextKey, forms, snapshot)
    }

    private fun record(built: Built, declarationCid: ContentId): DocumentCurationRecord {
        val cid = ContentId(string(built.declaration["derivedKifCid"]))
        val source = DocumentSource(cid, cid, built.kif, built.snapshot.receiptCid.hex.take(16) + SUFFIX,
            "application/kif", "english-taxonomy/${built.contextKey.value}", provenance = _m[
                DECLARATION j declarationCid.value, "englishTaxonomyPolicy" j POLICY,
                "englishInspectionCid" j built.snapshot.receiptCid.value])
        return DocumentCurationRecord(source, null, null, MODEL, emptySeriesOf(),
            s_["Explicit proposed translation; no English entailment, model, parser or sink admission"], instructions = "")
    }

    fun prepare(cas: CasStore, snapshot: EnglishObservationGraph.Snapshot, contextIri: String,
        declarations: List<Map<String, Any?>>): Verified {
        val built = build(snapshot, contextIri, declarations)
        val kifBytes = built.kif.encodeToByteArray(throwOnInvalidSequence = true)
        val declarationBytes = CanonicalCbor.encodeMap(built.declaration)
        val declarationCid = ContentId.of(declarationBytes)
        val record = record(built, declarationCid); val recordBytes = DocumentCuratorCodec.encode(record)
        require(recordBytes.size <= MAX_RECORD) { "Translation carrier exceeds 256KiB" }
        val cid = ContentId.of(recordBytes)
        require(cas is BoundedCasReader) { "Bounded translation readback required" }
        for ((id, bytes) in s_[record.source.originalCid j kifBytes, declarationCid j declarationBytes, cid j recordBytes].view)
            require(cas.put(bytes) == id) { "Translation artifact retention differs" }
        val verified = Verified(cid, record, declarationCid, built)
        val ids = built.forms.map { "kif:${it["kifBegin"]}:${it["kifEnd"]}" }
        val base = DocumentTaxonomyCandidate.candidate(cas, cid, ids)
        cas.sync()
        for ((id, bytes) in s_[record.source.originalCid j kifBytes, declarationCid j declarationBytes, cid j recordBytes].view)
            require(read(cas, id, bytes.size).contentEquals(bytes)) { "Translation artifact readback differs" }
        read(cas, base.ontologyCid, 4096)
        return verified
    }

    fun marked(record: DocumentCurationRecord): Boolean = record.modelId == MODEL || record.source.name.endsWith(SUFFIX, ignoreCase = true) ||
        record.source.provenance.keys.any { it.startsWith("englishTaxonomy") }

    /** The marker is not authority: every artifact, origin binding and generated byte is rechecked. */
    fun verify(cas: CasStore, receiptCid: ContentId): Verified {
        val recordBytes = read(cas, receiptCid, MAX_RECORD)
        val record = DocumentCuratorCodec.decodeBounded(recordBytes, MAX_RECORD, 24, 100_000)
        require(marked(record)) { "Not an English taxonomy carrier" }
        val source = record.source
        require(record.modelId == MODEL && source.name.endsWith(SUFFIX) && source.provenance["englishTaxonomyPolicy"] == POLICY) { "Incomplete English translation carrier markers" }
        val declarationCid = ContentId(requireNotNull(source.provenance[DECLARATION]) { "English translation declaration marker missing" })
        val declarationBytes = read(cas, declarationCid, MAX_BYTES)
        val declaration = CanonicalCbor.decodeMapBounded(declarationBytes, MAX_BYTES, 24, 100_000)
        val inspectionCid = ContentId(string(declaration["inspectionCid"]))
        require(source.provenance["englishInspectionCid"] == inspectionCid.value) { "English origin marker differs" }
        val snapshot = EnglishObservationGraph.read(cas, inspectionCid)
        val choices = list(declaration["forms"]).map { map(map(it)["declared"]) }
        val built = build(snapshot, string(declaration["contextIri"]), choices)
        require(CanonicalCbor.encodeMap(built.declaration).contentEquals(declarationBytes)) { "Translation declaration/source bindings differ" }
        val expected = record(built, declarationCid)
        require(DocumentCuratorCodec.encode(expected).contentEquals(recordBytes)) { "Translation carrier record differs" }
        require(read(cas, expected.source.originalCid, MAX_BYTES).contentEquals(built.kif.encodeToByteArray())) { "Derived KIF differs from the declared translation" }
        return Verified(receiptCid, record, declarationCid, built)
    }

    fun candidate(cas: CasStore, receiptCid: ContentId, ids: List<String>, retainPolicy: Boolean = true,
        kind: String = DocumentTaxonomyCandidate.KIND): CurationReviewCandidate {
        require(kind == DocumentTaxonomyCandidate.KIND) { "English translations use one complete taxonomy carrier, not partitions" }
        val checked = verify(cas, receiptCid)
        val base = DocumentTaxonomyCandidate.candidate(cas, receiptCid, ids, retainPolicy, kind)
        val byId = origins(checked).associateBy { string(it["selectionId"]) }
        return base.copy(family = "taxonomy/english/${checked.built.contextKey.value}", selections = base.selections α { item ->
            // The review WAL reads CBOR integer fields as Long. Store the same canonical shape now,
            // so a frozen source binding survives replay without Int/Long equality differences.
            val value = item.value + _m[MARKER j POLICY, "translationDecisionCid" j checked.declarationCid.value,
                "englishOrigin" j requireNotNull(byId[item.id]) { "Unknown translated KIF occurrence" }]
            item.copy(value = CanonicalCbor.decodeMapBounded(CanonicalCbor.encodeMap(value), MAX_BYTES, 24, 100_000))
        })
    }

    fun isCarrier(candidate: CurationReviewCandidate): Boolean = candidate.selections.view.any {
        MARKER in it.value || "translationDecisionCid" in it.value
    }

    private fun origins(verified: Verified): List<Map<String, Any?>> = verified.built.forms.map { form ->
        val begin = int(form["kifBegin"]); val end = int(form["kifEnd"])
        _m["selectionId" j "kif:$begin:$end", "translationDecisionCid" j verified.declarationCid.value,
            "inspectionCid" j verified.built.snapshot.receiptCid.value,
            "contextIri" j verified.built.declaration["contextIri"], "contextKey" j verified.built.contextKey.value,
            "sentenceIndex" j form["sentenceIndex"], "englishQuotation" j form["englishQuotation"],
            "subject" j form["subject"], "predicate" j form["predicate"], "copula" j form["copula"],
            "bindings" j form["bindings"], "declared" j form["declared"],
            "kifQuotation" j verified.record.source.quotation(begin, end)]
    }

    fun evidence(cas: CasStore, candidate: CurationReviewCandidate): Map<String, Any?>? {
        if (!isCarrier(candidate)) return null
        val verified = verify(cas, candidate.curationReceiptCid)
        val wanted = candidate.selections.view.map { it.id }.toSet()
        val expected = candidate(cas, candidate.curationReceiptCid, wanted.toList(), retainPolicy = false, kind = candidate.kind)
        require(candidate.kind == expected.kind && candidate.family == expected.family &&
            candidate.curationReceiptCid == expected.curationReceiptCid && candidate.ontologyCid == expected.ontologyCid &&
            candidate.sourceRevision == expected.sourceRevision && candidate.runReceiptCid == expected.runReceiptCid &&
            candidate.selections.view.toList() == expected.selections.view.toList()) { "Reviewed English translation candidate differs from retained evidence" }
        return _m["translationDecisionCid" j verified.declarationCid.value,
            "inspectionCid" j verified.built.snapshot.receiptCid.value,
            "contextIri" j verified.built.declaration["contextIri"], "contextKey" j verified.built.contextKey.value,
            "witnesses" j origins(verified).filter { it["selectionId"] in wanted },
            "authority" j "explicit-reviewed-translation-assumptions-not-English-entailment",
            "independentDocumentsOrNarsEvidence" j false]
    }

    fun projection(verified: Verified): Map<String, Any?> = _m[
        "translationCid" j verified.declarationCid.value, "derivedKifCid" j verified.record.source.originalCid.value,
        "receiptCid" j verified.receiptCid.value, "sourceRevision" j DocumentReviewCandidate.revision(verified.record.source),
        "contextIri" j verified.built.declaration["contextIri"], "contextKey" j verified.built.contextKey.value,
        "kif" j verified.built.kif, "reviewStatus" j "unreviewed", "selections" j origins(verified).map { origin ->
            origin + _m["id" j origin["selectionId"], "kind" j "relationship", "label" j map(origin["kifQuotation"])["quote"]]
        }]
}
