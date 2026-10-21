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

    fun conflicts(statements: Series<NormStatement>, statement: NormStatement): List<String> =
        statements.view.filter { it.proposition == statement.proposition && it.modality.opposes(statement.modality) }
            .map { it.id }.distinct()

    fun retain(cas: CasStore, book: String, source: DocumentSource, ontology: CorpusOntology,
        clauses: Series<BookClause>, parser: String, unresolved: List<Map<String, Any?>> = emptyList()): ContentId {
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
            "relationships" to proposals.view.toList(), "unresolved" to unresolved)
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
