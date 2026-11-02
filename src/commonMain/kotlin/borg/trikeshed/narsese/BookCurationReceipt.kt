package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.CorpusOntology

/** Clause plus its exact source span; normalized statement text is never used as quotation. */
data class BookClause(val clause: NormClause, val begin: Int, val end: Int, val section: Int,
    val parserReceiptCid: ContentId? = null)

/** Inverse of the section tupler's whitespace/dehyphenation normalization, retaining original offsets. */
class BookQuotationIndex(val source: String) {
    private val offsets = IntArray(source.length)
    val text: String = buildString {
        var i = 0
        while (i < source.length) {
            fun word(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
            if (source[i] == '-' && i > 0 && i + 2 < source.length && source[i + 1] == '\n' &&
                word(source[i - 1]) && word(source[i + 2])) { i += 2; continue }
            val c = source[i]
            if (c in " \t\r\n\u000B\u000C") {
                if (isNotEmpty() && last() != ' ') { offsets[length] = i; append(' ') }
            } else { offsets[length] = i; append(c) }
            i++
        }
        if (isNotEmpty() && last() == ' ') deleteAt(length - 1)
    }

    /** Returns the exact original span and the next normalized search position. */
    fun span(sentence: String, from: Int): Join<IntRange, Int>? {
        val at = text.indexOf(sentence.trim(), from)
        if (at < 0 || sentence.isBlank()) return null
        val end = at + sentence.trim().length
        return (offsets[at]..offsets[end - 1]) j end
    }
}

object BookCurationReceipt {
    const val KIND = "book-curation"
    /** The bounded CBOR decoder's present ceiling; larger receipts need a chunked format. */
    const val MAX_REVIEW_RECEIPT_BYTES = 16 * 1024 * 1024
    private const val MAX_REVIEW_PROPOSALS = 10_000

    fun conflicts(statements: Series<NormStatement>, statement: NormStatement): List<String> =
        statements.view.filter { it.proposition == statement.proposition && it.modality.opposes(statement.modality) }
            .map { it.id }.distinct()

    fun retain(cas: CasStore, book: String, source: DocumentSource, ontology: CorpusOntology,
        clauses: Series<BookClause>, parser: String, unresolved: List<Map<String, Any?>> = emptyList(),
        parserProvenance: Map<String, Any?> = emptyMap()): ContentId {
        require(cas.get(source.originalCid) != null) { "Original source is not retained" }
        require(cas.get(source.extractedTextCid)?.contentEquals(source.text.encodeToByteArray()) == true) {
            "Extracted source identity differs"
        }
        val statements = clauses α { NormStatement.of(it.clause) }
        val proposals = clauses α { span ->
            require(span.parserReceiptCid == null || cas.get(span.parserReceiptCid) != null) { "Parser derivation is not retained" }
            val statement = NormStatement.of(span.clause)
            val subjectMapping = ontology.mapping(span.clause.subject)
            val mapping = if (subjectMapping["status"] == "unresolved") ontology.mapping(span.clause.head) else subjectMapping
            val value = mapOf("statementId" to statement.id, "subject" to statement.bearer,
                "predicate" to statement.action, "object" to statement.obj, "condition" to statement.condition,
                "modality" to statement.modality.key, "polarity" to span.clause.affirmative,
                "section" to span.section, "quotation" to source.quotation(span.begin, span.end),
                "ontologyCid" to ontology.cid.value, "ontologyVersion" to ontology.version,
                "ontologyMapping" to mapping, "objectMapping" to statement.obj?.let(ontology::mapping),
                "derivation" to mapOf("parser" to parser, "kind" to if (statement.condition != null || span.clause.modal == "would") "hypothetical" else "assertion",
                    "parserReceiptCid" to span.parserReceiptCid?.value, "taxonomyClosureIsCausalProof" to false),
                "conflicts" to conflicts(statements, statement), "reviewStatus" to "unreviewed")
            val cid = cas.put(CanonicalCbor.encodeMap(value))
            value + mapOf("id" to cid.value, "proposalCid" to cid.value)
        }
        val sourceMap = DocumentCuratorCodec.source(source)
        val receipt = mapOf("kind" to KIND, "book" to book, "family" to "$book:${ontology.corpus}",
            "source" to sourceMap, "sourceRevision" to ContentId.of(CanonicalCbor.encodeMap(sourceMap)).value,
            "ontologyCid" to ontology.cid.value, "ontologyVersion" to ontology.version,
            "sumoAlignmentCid" to ontology.alignment?.cid?.value, "parser" to parser,
            "relationships" to proposals.view.toList(), "unresolved" to unresolved) +
            if (parserProvenance.isEmpty()) emptyMap() else mapOf("parserProvenance" to parserProvenance)
        val bytes = CanonicalCbor.encodeMap(receipt)
        val cid = cas.put(bytes)
        require(cas.get(cid)?.contentEquals(bytes) == true) { "Book curation retention failed" }
        cas.sync()
        return cid
    }

    fun read(cas: CasStore, cid: ContentId): Map<String, Any?> =
        CanonicalCbor.decodeMap(requireNotNull(cas.get(cid)) { "Book curation is not retained" }).also {
            require(it["kind"] == KIND) { "Receipt is not book curation" }
        }

    fun source(cas: CasStore, cid: ContentId): DocumentSource =
        DocumentCuratorCodec.decodeSource(read(cas, cid)["source"] as Map<*, *>)

    /**
     * Review-only interpretation of one frozen and digest-verified receipt. This validates
     * internal quotation/anchor consistency, not OCR, document authenticity, or legal meaning.
     * The embedded proposal values bind their IDs; separate proposal CAS block availability is
     * not established. Unbounded stores and older unsupported formats fail closed here.
     */
    internal class VerifiedBook(
        val cid: ContentId,
        val book: String,
        val family: String,
        val sourceRevision: String,
        val source: DocumentSource,
        val ontologyCid: ContentId,
        private val proposals: Map<String, Map<String, Any?>>,
    ) {
        fun candidate(selection: List<String>): CurationReviewCandidate {
            require(selection.isNotEmpty() && selection.size <= 4096 && selection.distinct().size == selection.size) {
                "Choose distinct retained curation selections"
            }
            val selected = selection.map { id ->
                val value = requireNotNull(proposals[id]) { "Selected relationship is absent from retained curation" }
                val quote = value["quotation"] as? Map<*, *> ?: error("Selected quotation is absent")
                fun offset(key: String): Int {
                    val n = quote[key] as? Long ?: error("Invalid quotation $key")
                    require(n in 0..Int.MAX_VALUE.toLong()) { "Invalid quotation $key" }
                    return n.toInt()
                }
                val begin = offset("begin")
                val end = offset("end")
                val text = quote["quote"] as? String ?: error("Quotation text is absent")
                require(text.isNotBlank() && DocumentQuotationCheck.exactSpan(source.text, begin, end, text)) {
                    "Selected quotation differs from retained extracted text"
                }
                val encoded = quote.entries.associate { (key, value) ->
                    (key as? String ?: error("Quotation field is not text")) to value
                }
                require(CanonicalCbor.encodeMap(encoded).contentEquals(CanonicalCbor.encodeMap(source.quotation(begin, end)))) {
                    "Selected quotation metadata differs from the retained source"
                }
                CurationReviewSelection(id, "relationship", value, CurationReviewQuotation(
                    source.originalCid, source.extractedTextCid, begin, end, text))
            }.toSeries()
            return CurationReviewCandidate("book", family, cid, ontologyCid, sourceRevision, selected)
        }

        /** Re-resolve even old saved snapshots against this exact receipt before admitting them. */
        fun matches(stored: CurationReviewCandidate): Boolean {
            if (stored.kind != "book" || stored.curationReceiptCid != cid || stored.family != family ||
                stored.ontologyCid != ontologyCid || stored.sourceRevision != sourceRevision || stored.runReceiptCid != null ||
                stored.selections.size !in 1..4096) return false
            val supplied = stored.selections.view.toList()
            val expected = try { candidate(supplied.map { it.id }).selections.view.toList() }
                catch (_: Exception) { return false }
            if (supplied.size != expected.size) return false
            return supplied.indices.all { i ->
                val actual = supplied[i]; val want = expected[i]
                actual.id == want.id && actual.kind == want.kind && actual.quotation == want.quotation &&
                    CanonicalCbor.encodeMap(actual.value).contentEquals(CanonicalCbor.encodeMap(want.value))
            }
        }
    }

    internal fun verified(cas: CasStore, cid: ContentId): VerifiedBook {
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required for book review")
        val returned = requireNotNull(reader.getBounded(cid, MAX_REVIEW_RECEIPT_BYTES)) { "Book curation is not retained" }
        require(returned.size <= MAX_REVIEW_RECEIPT_BYTES) { "Book curation exceeds review limit" }
        val bytes = returned.copyOf()
        require(ContentId.of(bytes) == cid) { "Book curation receipt identity differs" }
        val receipt = CanonicalCbor.decodeMapBounded(bytes, MAX_REVIEW_RECEIPT_BYTES)
        require(receipt["kind"] == KIND) { "Receipt is not book curation" }
        fun string(key: String): String = (receipt[key] as? String)?.takeIf { it.isNotBlank() }
            ?: error("Book curation $key is absent")
        val sourceMap = (receipt["source"] as? Map<*, *>)?.entries?.associate { (key, value) ->
            (key as? String ?: error("Book source field is not text")) to value
        } ?: error("Book source is absent")
        val sourceRevision = ContentId(string("sourceRevision"))
        require(ContentId.of(CanonicalCbor.encodeMap(sourceMap)) == sourceRevision) { "Book source revision differs" }
        val source = DocumentCuratorCodec.decodeSource(sourceMap)
        require(ContentId.of(source.text.encodeToByteArray()) == source.extractedTextCid) { "Book extracted text identity differs" }
        val ontology = ContentId(string("ontologyCid"))
        val ontologyVersion = string("ontologyVersion")
        val book = string("book")
        val family = string("family")
        val alignment = receipt["sumoAlignmentCid"]?.let {
            ContentId(it as? String ?: error("Invalid SUMO alignment ID"))
        }
        val rows = receipt["relationships"] as? List<*> ?: error("Book relationships are absent")
        require(rows.size <= MAX_REVIEW_PROPOSALS) { "Book proposal review limit" }
        val proposals = LinkedHashMap<String, Map<String, Any?>>()
        val canonical = HashMap<String, ByteArray>()
        for (raw in rows) {
            val row = (raw as? Map<*, *>)?.entries?.associate { (key, value) ->
                (key as? String ?: error("Book proposal field is not text")) to value
            } ?: error("Invalid book proposal")
            val id = ContentId(row["id"] as? String ?: error("Book proposal ID is absent"))
            require(row["proposalCid"] == id.value) { "Book proposal IDs differ" }
            require(row.containsKey("condition")) { "Book proposal condition field is absent" }
            val value = row - setOf("id", "proposalCid")
            require(ContentId.of(CanonicalCbor.encodeMap(value)) == id) { "Book proposal value differs from its ID" }
            require(row["ontologyCid"] == ontology.value && row["ontologyVersion"] == ontologyVersion) {
                "Book proposal ontology differs from the receipt"
            }
            require(row["reviewStatus"] == "unreviewed" &&
                (row["derivation"] as? Map<*, *>)?.get("taxonomyClosureIsCausalProof") == false) {
                "Book proposal review or derivation status differs"
            }
            fun mapping(raw: Any?) {
                val m = raw as? Map<*, *> ?: error("Book ontology mapping is absent")
                val corpus = m["corpus"] as? String ?: error("Book ontology corpus is absent")
                require(m["ontologyCid"] == ontology.value && m["version"] == ontologyVersion &&
                    "$book:$corpus" == family && m["causalProof"] == false && m["derivation"] == "taxonomy") {
                    "Book ontology mapping identity differs"
                }
                val mapped = m["alignment"]
                if (alignment == null) require(mapped == null) { "Unexpected SUMO alignment" }
                else require((mapped as? Map<*, *>)?.get("ontologyCid") == alignment.value) { "SUMO alignment identity differs" }
            }
            mapping(row["ontologyMapping"])
            if (row["object"] == null) require(row["objectMapping"] == null) { "Unexpected object ontology mapping" }
            else mapping(row["objectMapping"])
            val encoded = CanonicalCbor.encodeMap(row)
            canonical[id.value]?.let { require(it.contentEquals(encoded)) { "Conflicting duplicate book proposal" } }
                ?: run { canonical[id.value] = encoded; proposals[id.value] = row }
        }
        return VerifiedBook(cid, book, family, sourceRevision.value, source, ontology, proposals)
    }

    internal fun candidateVerified(cas: CasStore, cid: ContentId, selection: List<String>): CurationReviewCandidate =
        verified(cas, cid).candidate(selection)

    fun candidate(cas: CasStore, cid: ContentId, selection: List<String>): CurationReviewCandidate {
        val receipt = read(cas, cid)
        val all = (receipt["relationships"] as List<*>).associate { value ->
            @Suppress("UNCHECKED_CAST") val m = value as Map<String, Any?>
            m["id"] as String to m
        }
        require(selection.all { it in all }) { "Selected relationship is absent from retained curation" }
        val selected = selection.toSeries() α { id ->
            val value = requireNotNull(all[id]) { "Selected relationship is absent from retained curation" }
            val quote = value["quotation"] as Map<*, *>
            CurationReviewSelection(id, "relationship", value, CurationReviewQuotation(
                ContentId(quote["originalCid"] as String), ContentId(quote["extractedTextCid"] as String),
                (quote["begin"] as Number).toInt(), (quote["end"] as Number).toInt(), quote["quote"] as String))
        }
        return CurationReviewCandidate("book", receipt["family"] as String, cid,
            ContentId(receipt["ontologyCid"] as String), receipt["sourceRevision"] as String, selected)
    }
}
