package borg.trikeshed.narsese

import borg.trikeshed.parse.reifyStrict

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib._l
import borg.trikeshed.lib._m
import borg.trikeshed.lib._s
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpMetadata
import borg.trikeshed.nlp.NlpMention
import borg.trikeshed.nlp.NlpRelation
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.reduction.j

/** Uses the existing canonical map/CBOR path, not the lossy ConfixDoc-to-map projection. */
internal object DocumentCuratorCodec {
    fun source(source: DocumentSource): Map<String, Any?> = mapOf(
        "originalCid" to source.originalCid.value, "extractedTextCid" to source.extractedTextCid.value,
        "text" to source.text, "name" to source.name, "mediaType" to source.mediaType,
        "correlation" to source.correlation, "metadata" to source.metadata,
        "category" to source.category, "provenance" to source.provenance,
        "project" to source.project?.toMap(), "extraction" to source.extraction?.toMap(),
        "anchors" to source.anchors.map(DocumentAnchor::toMap), "unresolved" to source.unresolved,
    )

    fun decodeSource(s: Map<*, *>): DocumentSource {
        val original = ContentId(s.str("originalCid")); val extracted = ContentId(s.str("extractedTextCid"))
        return DocumentSource(original, extracted, s.str("text"), s.str("name"), s.str("mediaType"), s.str("correlation"),
            (s["metadata"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k as String to (v as List<*>).map { it as String } },
            s["category"] as? String ?: if (original == extracted) "text" else "document",
            (s["provenance"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k as String to v as String },
            DocumentRevision.fromMap(s["project"]), DocumentRevision.fromMap(s["extraction"]),
            (s["anchors"] as? List<*>).orEmpty().map(DocumentAnchor::fromMap),
            (s["unresolved"] as? List<*>)?.map { it as String } ?: listOf("page", "folio", "section", "table", "diagram", "coordinates"))
    }

    /** Full stored form: reader metadata rides along; the prompt form below omits it. */
    fun nlp(doc: NlpDocument): Map<String, Any?> = mapOf("text" to doc.text,
        "sentences" to doc.sentences.values { s -> mapOf(
            "index" to s.index, "begin" to s.begin, "end" to s.end,
            "tokens" to s.tokens.values { t -> mapOf("index" to t.index, "begin" to t.begin,
                "end" to t.end, "word" to t.word, "lemma" to t.lemma, "tag" to t.tag, "ner" to t.ner) },
            "dependencies" to s.dependencies.values { d -> mapOf("governor" to d.governor,
                "dependent" to d.dependent, "relation" to d.relation,
                "governorCopy" to d.governorCopy, "dependentCopy" to d.dependentCopy) },
            "relations" to s.relations.map(::nlpRelation), "kbp" to s.kbp.map(::nlpRelation),
        ) },
        "metadata" to doc.metadata?.let { m -> mapOf("processor" to m.processor, "implementation" to m.implementation,
            "runtime" to m.runtime, "configuration" to m.configuration) },
        "mentions" to doc.mentions.map { mention -> _m["sentence" j mention.sentence,
            "span" j _l[mention.span.first, mention.span.last], "text" j mention.text,
            "chain" j mention.chain, "representative" j mention.representative] })

    /** Ranges are inclusive token endpoints, not quotation offsets; empty ranges and glosses stay unchanged. */
    private fun nlpRelation(relation: NlpRelation): Map<String, Any?> {
        require(relation.confidence.isFinite()) { "NLP relation confidence must be finite" }
        return _m["subject" j _l[relation.subject.first, relation.subject.last],
            "relation" j _l[relation.relation.first, relation.relation.last],
            "object" j _l[relation.`object`.first, relation.`object`.last],
            "subjectText" j relation.subjectText, "relationText" j relation.relationText,
            "objectText" j relation.objectText, "confidence" j relation.confidence]
    }

    private fun relationRow(relation: NlpRelation): List<Any?> {
        require(relation.confidence.isFinite()) { "NLP relation confidence must be finite" }
        return _l[relation.subject.first, relation.subject.last, relation.relation.first, relation.relation.last,
            relation.`object`.first, relation.`object`.last, relation.subjectText, relation.relationText,
            relation.objectText, relation.confidence]
    }

    /** Prompt rows declare columns once; text is supplied by [source], storage uses [nlp]. */
    fun nlpPrompt(doc: NlpDocument): Map<String, Any?> = mapOf(
        "columns" to mapOf(
            "sentence" to _l["index", "begin", "end", "tokens", "dependencies", "relations", "kbp"],
            "token" to listOf("index", "begin", "end", "word", "lemma", "tag", "ner"),
            "dependency" to listOf("governor", "dependent", "relation", "governorCopy", "dependentCopy"),
            "relation" to _l["subjectFirstToken", "subjectLastToken", "relationFirstToken", "relationLastToken",
                "objectFirstToken", "objectLastToken", "subjectText", "relationText", "objectText", "confidence"],
            "mention" to _l["sentence", "firstToken", "lastToken", "text", "chain", "representative"],
        ),
        "sentences" to doc.sentences.values { s -> listOf(s.index, s.begin, s.end,
            s.tokens.values { t -> listOf(t.index, t.begin, t.end, t.word, t.lemma, t.tag, t.ner) },
            s.dependencies.values { d -> listOf(d.governor, d.dependent, d.relation, d.governorCopy, d.dependentCopy) },
            s.relations.map(::relationRow), s.kbp.map(::relationRow),
        ) },
        "mentions" to doc.mentions.map { _l[it.sentence, it.span.first, it.span.last, it.text, it.chain, it.representative] },
    )

    /**
     * Raw stored-field presence only, not producer execution, valid semantics or a claim of zero outputs.
     * Absent historical fields decode to empty lists but cannot be recovered. No observed metadata or
     * historical reasons are changed. As with [decode], callers must bound and authorize retrieval.
     * For an empty sentence array the per-sentence field checks are vacuous; inspect `nlp` and `mentions` too.
     */
    fun nlpRetentionCoverage(bytes: ByteArray): Map<String, Boolean> {
        val document = CanonicalCbor.decodeMap(bytes)["nlp"] as? Map<*, *>
        val sentences = document?.get("sentences") as? List<*>
        fun everySentence(key: String) = sentences?.all { it is Map<*, *> && it.containsKey(key) } == true
        return _m["nlp" j (document != null), "relations" j everySentence("relations"),
            "kbp" j everySentence("kbp"), "mentions" j (document?.containsKey("mentions") == true)]
    }

    fun proposal(p: DocumentProposal): Map<String, Any?> = mapOf(
        "raw" to p.raw, "subject" to p.subject, "predicate" to p.predicate, "object" to p.obj,
        "confidence" to p.confidence, "quote" to p.quote, "begin" to p.begin, "end" to p.end,
        "polarity" to p.polarity, "modality" to p.modality,
        "reasons" to p.reasons.values(), "receiptCid" to p.receiptCid?.value,
        "quotationReceiptCid" to p.quotationReceiptCid?.value,
        "quotationBegin" to p.quotationBegin, "quotationEnd" to p.quotationEnd,
    )

    fun model(response: ModelResponse): Map<String, Any?> = mapOf(
        "content" to response.content, "providerId" to response.providerId, "modelId" to response.modelId,
        "promptTokens" to response.usage.promptTokens, "completionTokens" to response.usage.completionTokens,
        "totalTokens" to response.usage.totalTokens,
    )

    /** The exact receipt ModelMux minted; every field retained, none derived. */
    fun modelReceipt(r: ModelResponseReceipt): Map<String, Any?> = mapOf(
        "receiptId" to r.receiptId, "modelId" to r.modelId, "providerId" to r.providerId,
        "requestHash" to r.requestHash, "assessmentId" to r.assessmentId, "sessionId" to r.sessionId,
        "action" to r.action, "httpStatus" to r.httpStatus, "latencyMs" to r.latencyMs,
        "inputTokens" to r.inputTokens, "outputTokens" to r.outputTokens, "cachedHit" to r.cachedHit,
        "cacheReadTokens" to r.cacheReadTokens, "cacheWriteTokens" to r.cacheWriteTokens,
        "errorClass" to r.errorClass, "errorMessage" to r.errorMessage, "capturedAt" to r.capturedAt,
    )

    /** Stage receipt row; key names are the read-side contract. */
    fun toolReceipt(r: DocumentToolReceipt): Map<String, Any?> = mapOf(
        "stage" to r.stage.name, "tool" to r.tool, "implementation" to r.implementation,
        "configuration" to r.configuration,
        "inputCids" to r.inputCids.values { it.value }, "outputCids" to r.outputCids.values { it.value },
        "startedAt" to r.startedAt, "completedAt" to r.completedAt,
        "status" to r.status.name, "error" to r.error, "receiptCid" to r.receiptCid?.value,
    )

    fun toolReceipt(m: Map<*, *>): DocumentToolReceipt = DocumentToolReceipt(
        stage = DocumentToolStage.valueOf(m.str("stage")), tool = m.str("tool"), implementation = m.str("implementation"),
        status = DocumentToolStatus.valueOf(m.str("status")),
        configuration = m.obj("configuration").mapValues { (_, v) -> v as String },
        inputCids = m.cids("inputCids"), outputCids = m.cids("outputCids"),
        startedAt = (m["startedAt"] as? Number)?.toLong(), completedAt = (m["completedAt"] as? Number)?.toLong(),
        error = m["error"] as? String, receiptCid = (m["receiptCid"] as? String)?.let(::ContentId),
    )

    /** Full rule-bearing candidate: the [EternalRule] fields, its evidence, provenance and identity, never a projection. */
    fun axiom(a: NlpcoreAxiom): Map<String, Any?> = mapOf(
        "sentenceIndex" to a.sentenceIndex, "begin" to a.begin, "end" to a.end,
        "antecedent" to a.antecedent, "predicate" to a.predicate, "consequent" to a.consequent,
        "confidence" to a.confidence.toDouble(),
        "rule" to mapOf(
            "antecedent" to a.rule.antecedent, "consequent" to a.rule.consequent, "copula" to a.rule.copula.name,
            "positiveEvidence" to a.rule.evidence.positive, "negativeEvidence" to a.rule.evidence.negative,
            "provenanceCid" to a.rule.provenanceCid, "ruleCid" to a.rule.ruleCid.value,
        ),
    )

    fun axiom(m: Map<*, *>): NlpcoreAxiom {
        val r = m.obj("rule")
        val rule = EternalRule(r.str("antecedent"), r.str("consequent"), NalCopula.valueOf(r.str("copula")),
            EvidenceCoord((r["positiveEvidence"] as Number).toLong(), (r["negativeEvidence"] as Number).toLong()),
            r["provenanceCid"] as? String)
        check(rule.ruleCid.value == r.str("ruleCid")) { "stored axiom rule identity mismatch" }
        return NlpcoreAxiom(m.int("sentenceIndex"), m.int("begin"), m.int("end"), m.str("antecedent"),
            m.str("predicate"), m.str("consequent"), (m["confidence"] as Number).toFloat(), rule)
    }

    fun identity(source: DocumentSource, p: DocumentProposal): ByteArray = CanonicalCbor.encodeMap(mapOf(
        "originalCid" to source.originalCid.value, "extractedTextCid" to source.extractedTextCid.value,
        "subject" to p.subject, "predicate" to p.predicate, "object" to p.obj,
        "quote" to p.quote, "begin" to p.begin, "end" to p.end,
        "polarity" to p.polarity, "modality" to p.modality,
    ))

    fun encode(record: DocumentCurationRecord): ByteArray = CanonicalCbor.encodeMap(record(record))

    fun record(record: DocumentCurationRecord): Map<String, Any?> = mapOf(
        "version" to 1, "source" to source(record.source), "modelId" to record.modelId,
        "instructions" to record.instructions,
        "model" to record.model?.let(::model),
        "nlp" to record.nlp?.let(::nlp),
        "proposals" to record.proposals.values { proposal(it) }, "reasons" to record.reasons.values(),
        "reserved" to record.reservedReceiptCids.values { it.value },
        "submitted" to record.submittedReceiptCids.values { it.value },
        "duplicates" to record.duplicateReceiptCids.values { it.value },
        "quotationReserved" to record.quotationReservedReceiptCids.values { it.value },
        "quotationSubmitted" to record.quotationSubmittedReceiptCids.values { it.value },
        "quotationDuplicates" to record.quotationDuplicateReceiptCids.values { it.value },
        "toolOntology" to record.toolOntology.values(),
        "observerFailures" to record.observerFailures.values(),
        "receipt" to record.receipt?.let(::modelReceipt),
        "toolReceipts" to record.toolReceipts.values { toolReceipt(it) },
        "axioms" to record.axioms?.values { axiom(it) },
    )

    fun decode(bytes: ByteArray): DocumentCurationRecord = decodeMap(CanonicalCbor.decodeMap(bytes))

    /** Bounded structural decode only; numeric field coercions are not strict range checks.
     * Callers still need authorized, bounded retrieval and typed validation before trusting offsets. */
    fun decodeBounded(bytes: ByteArray, maxBytes: Int, maxDepth: Int = 64, maxItems: Int = 1_000_000): DocumentCurationRecord =
        decodeMap(CanonicalCbor.decodeMapBounded(bytes, maxBytes, maxDepth, maxItems))

    private fun decodeMap(m: Map<String, Any?>): DocumentCurationRecord {
        require(m.int("version") == 1) { "unsupported document curation record" }
        val s = m.obj("source")
        val source = decodeSource(s)
        val nlp = (m["nlp"] as? Map<*, *>)?.let { d -> NlpDocument(d.str("text"), d.array("sentences").map { v ->
            val sentence = v as Map<*, *>
            NlpSentence(sentence.int("index"), sentence.int("begin"), sentence.int("end"),
                sentence.array("tokens").map { token -> (token as Map<*, *>).let { t ->
                    NlpToken(t.int("index"), t.int("begin"), t.int("end"), t.str("word"),
                        t.str("lemma"), t.str("tag"), t.str("ner"))
                } }.toSeries(), sentence.array("dependencies").map { edge -> (edge as Map<*, *>).let { e ->
                    NlpDependency(e.int("governor"), e.int("dependent"), e.str("relation"),
                        e.copyCount("governorCopy"), e.copyCount("dependentCopy"))
                } }.toSeries(), sentence.nlpRelations("relations"), sentence.nlpRelations("kbp"))
        }.toSeries(), (d["metadata"] as? Map<*, *>)?.let { meta -> NlpMetadata(meta.str("processor"), meta.str("implementation"),
            meta.obj("runtime").mapValues { (_, v) -> v as String },
            meta.obj("configuration").mapValues { (_, v) -> v as String }) }, d.nlpMentions()) }
        val model = (m["model"] as? Map<*, *>)?.let { r -> ModelResponse(r.str("content"),
            ModelUsage(r.int("promptTokens"), r.int("completionTokens"), r.int("totalTokens")), r.str("providerId"), r["modelId"] as? String) }
        val proposals = m.array("proposals").map { value -> (value as Map<*, *>).let { p -> DocumentProposal(
            raw = p.str("raw"), subject = p["subject"] as? String, predicate = p["predicate"] as? String,
            obj = p["object"] as? String, confidence = (p["confidence"] as? Number)?.toDouble(),
            quote = p["quote"] as? String, begin = (p["begin"] as? Number)?.toInt(),
            end = (p["end"] as? Number)?.toInt(), polarity = p["polarity"] as? Boolean,
            modality = p["modality"] as? String, reasons = p.array("reasons").let { arr -> arr.size j { i: Int -> arr[i] as String } } ,
            receiptCid = (p["receiptCid"] as? String)?.let(::ContentId),
            quotationReceiptCid = (p["quotationReceiptCid"] as? String)?.let(::ContentId),
            quotationBegin = (p["quotationBegin"] as? Number)?.toInt(),
            quotationEnd = (p["quotationEnd"] as? Number)?.toInt(),
        ) } }.toSeries()
        val receipt = (m["receipt"] as? Map<*, *>)?.let { r -> ModelResponseReceipt(
            receiptId = r.str("receiptId"), modelId = r.str("modelId"), providerId = r.str("providerId"),
            requestHash = r.str("requestHash"), assessmentId = r["assessmentId"] as? String, sessionId = r["sessionId"] as? String,
            action = r.str("action"), httpStatus = r.int("httpStatus"), latencyMs = (r["latencyMs"] as Number).toLong(),
            inputTokens = r.int("inputTokens"), outputTokens = r.int("outputTokens"), cachedHit = r["cachedHit"] as Boolean,
            cacheReadTokens = (r["cacheReadTokens"] as? Number)?.toInt() ?: 0, cacheWriteTokens = (r["cacheWriteTokens"] as? Number)?.toInt() ?: 0,
            errorClass = r["errorClass"] as? String, errorMessage = r["errorMessage"] as? String,
            capturedAt = (r["capturedAt"] as Number).toLong(),
        ) }
        return DocumentCurationRecord(source, nlp, model, m.str("modelId"), proposals,
            m.array("reasons").let { arr -> arr.size j { i: Int -> arr[i] as String } },
            m.cids("reserved"), m.cids("submitted"), m.cids("duplicates"),
            m.array("observerFailures").let { arr -> arr.size j { i: Int -> arr[i] as String } },
            if (m.containsKey("instructions")) m.str("instructions") else DocumentCuratorGrounding.previousInstructions,
            m.optionalCids("quotationReserved"), m.optionalCids("quotationSubmitted"), m.optionalCids("quotationDuplicates"),
            m.optionalStrings("toolOntology").let { arr -> arr.size j { i -> arr[i] } }, receipt = receipt,
            toolReceipts = ((m["toolReceipts"] as? List<*>) ?: emptyList<Any?>()).let { arr -> arr.size j { i -> toolReceipt(arr[i] as Map<*, *>) } },
            axioms = (m["axioms"] as? List<*>)?.let { arr -> arr.size j { i -> axiom(arr[i] as Map<*, *>) } })
    }

    /** Validate syntax strictly before crossing the existing JSON-shaped value boundary. */
    fun strictJson(text: String): Any? = reifyStrict(text)

    private fun Map<*, *>.str(key: String) = this[key] as String
    private fun Map<*, *>.int(key: String) = (this[key] as Number).toInt()
    private fun observationInt(value: Any?): Int {
        val number = (value as? Number)?.toDouble() ?: error("NLP observation integer required")
        require(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == number.toInt().toDouble()) {
            "NLP observation integer outside range"
        }
        return number.toInt()
    }
    private fun observationRange(value: Any?): IntRange {
        val endpoints = value as? List<*> ?: error("NLP observation range must be a pair")
        require(endpoints.size == 2) { "NLP observation range must have two endpoints" }
        // Keep empty and implicit spans verbatim. They do not create source tokens or quotations.
        return observationInt(endpoints[0])..observationInt(endpoints[1])
    }
    private fun Map<*, *>.observationArray(key: String): List<*> =
        if (!containsKey(key)) _l.get<Any?>() else this[key] as? List<*> ?: error("NLP $key must be an array")
    private fun Map<*, *>.nlpRelations(key: String): List<NlpRelation> = observationArray(key).map { value ->
        val relation = value as? Map<*, *> ?: error("NLP relation must be an object")
        require(relation.keys == _s["subject", "relation", "object", "subjectText", "relationText", "objectText", "confidence"]) {
            "Missing or unsupported NLP relation fields"
        }
        val confidence = (relation["confidence"] as? Number)?.toDouble() ?: error("NLP relation confidence must be numeric")
        require(confidence.isFinite()) { "NLP relation confidence must be finite" }
        NlpRelation(observationRange(relation["subject"]), observationRange(relation["relation"]),
            observationRange(relation["object"]), relation.str("subjectText"), relation.str("relationText"),
            relation.str("objectText"), confidence)
    }
    private fun Map<*, *>.nlpMentions(): List<NlpMention> = observationArray("mentions").map { value ->
        val mention = value as? Map<*, *> ?: error("NLP mention must be an object")
        require(mention.keys == _s["sentence", "span", "text", "chain", "representative"]) {
            "Missing or unsupported NLP mention fields"
        }
        NlpMention(observationInt(mention["sentence"]), observationRange(mention["span"]), mention.str("text"),
            observationInt(mention["chain"]), mention["representative"] as? Boolean ?: error("NLP mention representative must be boolean"))
    }
    private fun Map<*, *>.copyCount(key: String): Int {
        if (!containsKey(key)) return 0 // Historical records did not retain copied-node identity.
        val number = (this[key] as? Number)?.toDouble() ?: error("Dependency copy count must be an integer")
        require(number.isFinite() && number >= 0 && number <= Int.MAX_VALUE && number == number.toInt().toDouble()) {
            "Dependency copy count must be a nonnegative integer"
        }
        return number.toInt()
    }
    private fun Map<*, *>.array(key: String) = this[key] as List<*>
    private fun Map<*, *>.obj(key: String) = (this[key] as Map<*, *>).entries.associate { it.key as String to it.value }
    private fun Map<*, *>.cids(key: String) = array(key).let { arr -> arr.size j { i -> ContentId(arr[i] as String) } }
    private fun Map<*, *>.optionalCids(key: String) = ((this[key] as? List<*>) ?: emptyList<Any?>())
        .let { arr -> arr.size j { i -> ContentId(arr[i] as String) } }
    private fun Map<*, *>.optionalStrings(key: String) = ((this[key] as? List<*>) ?: emptyList<Any?>())
        .map { it as String }
}

internal fun <T, R> Series<T>.values(f: (T) -> R): List<R> = List(size) { f(this[it]) }
internal fun <T> Series<T>.values(): List<T> = values { it }
