package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.parse.json.JsonSupport

/** Uses the existing canonical map/CBOR path, not the lossy ConfixDoc-to-map projection. */
internal object DocumentCuratorCodec {
    fun source(source: DocumentSource): Map<String, Any?> = mapOf(
        "originalCid" to source.originalCid.value, "extractedTextCid" to source.extractedTextCid.value,
        "text" to source.text, "name" to source.name, "mediaType" to source.mediaType,
        "correlation" to source.correlation, "metadata" to source.metadata,
    )

    /** The same typed index feeds model interaction and downstream cursor projection. */
    fun modelInput(index: DocumentCurationIndex): Map<String, Any?> = source(index.facet(DocumentCurationIndexK.Source)) + mapOf(
        "linguistics" to mapOf(
            "status" to index.facet(DocumentCurationIndexK.NlpStatus).name,
            "coordinateSystem" to "UTF-16; end exclusive; sentence-local one-based token indices; governor 0 is root",
            "reasons" to index.facet(DocumentCurationIndexK.Reasons).values(),
            "parseConfidence" to index.facet(DocumentCurationIndexK.ParseConfidence),
            "expressedCertainty" to index.facet(DocumentCurationIndexK.ExpressedCertainty),
            "externalVerification" to index.facet(DocumentCurationIndexK.ExternalVerification).name,
            "sentences" to sentences(index.facet(DocumentCurationIndexK.Sentences)),
        ),
    )

    private fun sentences(sentences: Series<NlpSentence>): List<Map<String, Any?>> = sentences.values { s -> mapOf(
        "index" to s.index, "begin" to s.begin, "end" to s.end,
        "tokens" to s.tokens.values { t -> mapOf("index" to t.index, "begin" to t.begin,
            "end" to t.end, "word" to t.word, "lemma" to t.lemma, "tag" to t.tag, "ner" to t.ner) },
        "dependencies" to s.dependencies.values { d -> mapOf("governor" to d.governor,
            "dependent" to d.dependent, "relation" to d.relation) },
    ) }

    fun proposal(p: DocumentProposal): Map<String, Any?> = mapOf(
        "raw" to p.raw, "subject" to p.subject, "predicate" to p.predicate, "object" to p.obj,
        "confidence" to p.confidence, "quote" to p.quote, "begin" to p.begin, "end" to p.end,
        "polarity" to p.polarity, "modality" to p.modality,
        "reasons" to p.reasons.values(), "receiptCid" to p.receiptCid?.value,
    )

    fun identity(source: DocumentSource, p: DocumentProposal): ByteArray = CanonicalCbor.encodeMap(mapOf(
        "originalCid" to source.originalCid.value, "extractedTextCid" to source.extractedTextCid.value,
        "subject" to p.subject, "predicate" to p.predicate, "object" to p.obj,
        "quote" to p.quote, "begin" to p.begin, "end" to p.end,
        "polarity" to p.polarity, "modality" to p.modality,
    ))

    fun encode(record: DocumentCurationRecord): ByteArray = CanonicalCbor.encodeMap(record(record))

    fun record(record: DocumentCurationRecord): Map<String, Any?> = mapOf(
        "version" to 1, "source" to source(record.source), "modelId" to record.modelId,
        "model" to record.model?.let { mapOf("content" to it.content, "providerId" to it.providerId, "modelId" to it.modelId,
            "promptTokens" to it.usage.promptTokens, "completionTokens" to it.usage.completionTokens,
            "totalTokens" to it.usage.totalTokens) },
        "nlp" to record.nlp?.let { doc -> mapOf("text" to doc.text,
            "sentences" to sentences(doc.sentences)) },
        "proposals" to record.proposals.values { proposal(it) }, "reasons" to record.reasons.values(),
        "reserved" to record.reservedReceiptCids.values { it.value },
        "submitted" to record.submittedReceiptCids.values { it.value },
        "duplicates" to record.duplicateReceiptCids.values { it.value },
        "observerFailures" to record.observerFailures.values(),
    )

    fun decode(bytes: ByteArray): DocumentCurationRecord {
        val m = CanonicalCbor.decodeMap(bytes)
        require(m.int("version") == 1) { "unsupported document curation record" }
        val s = m.obj("source")
        val source = DocumentSource(ContentId(s.str("originalCid")), ContentId(s.str("extractedTextCid")),
            s.str("text"), s.str("name"), s.str("mediaType"), s.str("correlation"),
            s.obj("metadata").mapValues { (_, v) -> (v as List<*>).map { it as String } })
        val nlp = (m["nlp"] as? Map<*, *>)?.let { d -> NlpDocument(d.str("text"), d.array("sentences").map { v ->
            val sentence = v as Map<*, *>
            NlpSentence(sentence.int("index"), sentence.int("begin"), sentence.int("end"),
                sentence.array("tokens").map { token -> (token as Map<*, *>).let { t ->
                    NlpToken(t.int("index"), t.int("begin"), t.int("end"), t.str("word"),
                        t.str("lemma"), t.str("tag"), t.str("ner"))
                } }.toSeries(), sentence.array("dependencies").map { edge -> (edge as Map<*, *>).let { e ->
                    NlpDependency(e.int("governor"), e.int("dependent"), e.str("relation"))
                } }.toSeries())
        }.toSeries()) }
        val model = (m["model"] as? Map<*, *>)?.let { r -> ModelResponse(r.str("content"),
            ModelUsage(r.int("promptTokens"), r.int("completionTokens"), r.int("totalTokens")), r.str("providerId"), r["modelId"] as? String) }
        val proposals = m.array("proposals").map { value -> (value as Map<*, *>).let { p -> DocumentProposal(
            raw = p.str("raw"), subject = p["subject"] as? String, predicate = p["predicate"] as? String,
            obj = p["object"] as? String, confidence = (p["confidence"] as? Number)?.toDouble(),
            quote = p["quote"] as? String, begin = (p["begin"] as? Number)?.toInt(),
            end = (p["end"] as? Number)?.toInt(), polarity = p["polarity"] as? Boolean,
            modality = p["modality"] as? String, reasons = p.array("reasons").map { it as String }.toSeries(),
            receiptCid = (p["receiptCid"] as? String)?.let(::ContentId),
        ) } }.toSeries()
        return DocumentCurationRecord(source, nlp, model, m.str("modelId"), proposals,
            m.array("reasons").map { it as String }.toSeries(),
            m.cids("reserved"), m.cids("submitted"), m.cids("duplicates"),
            m.array("observerFailures").map { it as String }.toSeries())
    }

    /** Validate syntax strictly before crossing the existing JSON-shaped value boundary. */
    fun strictJson(text: String): Any? = JsonSupport.parseStrict(text)

    private fun Map<*, *>.str(key: String) = this[key] as String
    private fun Map<*, *>.int(key: String) = (this[key] as Number).toInt()
    private fun Map<*, *>.array(key: String) = this[key] as List<*>
    private fun Map<*, *>.obj(key: String) = (this[key] as Map<*, *>).entries.associate { it.key as String to it.value }
    private fun Map<*, *>.cids(key: String) = array(key).map { ContentId(it as String) }.toSeries()
}

internal fun <T, R> Series<T>.values(f: (T) -> R): List<R> = List(size) { f(this[it]) }
internal fun <T> Series<T>.values(): List<T> = values { it }
