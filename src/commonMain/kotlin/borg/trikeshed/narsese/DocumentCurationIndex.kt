package borg.trikeshed.narsese

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken

/** Linguistic facets share the MetaSeries/OpK contract; Syntax retains syntax scanning. */
typealias DocumentCurationIndex = FacetedRow<Any>

enum class DocumentNlpStatus { AVAILABLE, UNAVAILABLE, INVALID }
enum class DocumentVerificationStatus { UNAVAILABLE }

/**
 * Spans are UTF-16 [begin, end) offsets into Source.text. Sentence selectors take
 * Series ordinals; parser sentence/token indices are retained in their records.
 * Tokens, NER labels and dependencies are parser evidence, never asserted facts.
 */
sealed class DocumentCurationIndexK<out R> : OpK<R>() {
    data object Source : DocumentCurationIndexK<DocumentSource>()
    data object Nlp : DocumentCurationIndexK<NlpDocument?>()
    data object NlpStatus : DocumentCurationIndexK<DocumentNlpStatus>()
    data object Reasons : DocumentCurationIndexK<Series<String>>()
    data object Sentences : DocumentCurationIndexK<Series<NlpSentence>>()
    data object SentenceSpans : DocumentCurationIndexK<Series<Twin<Int>>>()
    data object Tokens : DocumentCurationIndexK<(Int) -> Series<NlpToken>>()
    data object TokenSpans : DocumentCurationIndexK<(Int) -> Series<Twin<Int>>>()
    data object Ner : DocumentCurationIndexK<(Int) -> Series<String>>()
    data object Dependencies : DocumentCurationIndexK<(Int) -> Series<NlpDependency>>()
    data object SentenceCursor : DocumentCurationIndexK<Cursor>()
    data object TokenCursor : DocumentCurationIndexK<(Int) -> Cursor>()
    data object DependencyCursor : DocumentCurationIndexK<(Int) -> Cursor>()
    data object Model : DocumentCurationIndexK<ModelResponse?>()
    data object ModelId : DocumentCurationIndexK<String?>()
    data object Proposals : DocumentCurationIndexK<Series<DocumentProposal>>()
    /** This reader supplies no calibrated parse score. Model confidence remains in Proposals. */
    data object ParseConfidence : DocumentCurationIndexK<Double?>()
    /** No certainty interpretation is supplied; its lexical/dependency evidence remains above. */
    data object ExpressedCertainty : DocumentCurationIndexK<String?>()
    data object ExternalVerification : DocumentCurationIndexK<DocumentVerificationStatus>()
}

@Suppress("UNCHECKED_CAST")
fun <R> DocumentCurationIndex.facet(key: DocumentCurationIndexK<R>): R = b(key as Any) as R

/** Preliminary curation view, available before the model is invoked. */
fun DocumentSource.curationIndex(
    nlp: NlpDocument?,
    reasons: Series<String> = emptySeriesOf(),
): DocumentCurationIndex = curationIndex(nlp, reasons, null, null, emptySeriesOf())

fun DocumentCurationRecord.curationIndex(): DocumentCurationIndex =
    source.curationIndex(nlp, reasons, model, modelId, proposals)

fun DocumentCurationResult.curationIndex(): DocumentCurationIndex = record.curationIndex()

private fun DocumentSource.curationIndex(
    nlp: NlpDocument?,
    reasons: Series<String>,
    model: ModelResponse?,
    modelId: String?,
    proposals: Series<DocumentProposal>,
): DocumentCurationIndex {
    val source = this
    val sentences = nlp?.sentences ?: emptySeriesOf()
    // Validation is shared and deferred; constructing a projection never reifies the token Series.
    val validation by lazy { nlpErrors(nlp) }
    val status: () -> DocumentNlpStatus = {
        when {
            nlp == null -> DocumentNlpStatus.UNAVAILABLE
            validation.size > 0 -> DocumentNlpStatus.INVALID
            else -> DocumentNlpStatus.AVAILABLE
        }
    }
    val tokens: (Int) -> Series<NlpToken> = { sentences[it].tokens }
    val tokenSpans: (Int) -> Series<Twin<Int>> = { ordinal -> tokens(ordinal) α { it.begin j it.end } }
    val ner: (Int) -> Series<String> = { ordinal -> tokens(ordinal) α { it.ner } }
    val dependencies: (Int) -> Series<NlpDependency> = { sentences[it].dependencies }
    val tokenCursor: (Int) -> Cursor = { ordinal ->
        val sentence = sentences[ordinal]
        sentence.tokens.size j { position: Int ->
            val token = sentence.tokens[position]
            documentRow(tokenColumns) { column ->
                when (column) {
                    DocumentIndexColumn.ORIGINAL_CID -> originalCid.value
                    DocumentIndexColumn.EXTRACTED_TEXT_CID -> extractedTextCid.value
                    DocumentIndexColumn.SENTENCE -> sentence.index
                    DocumentIndexColumn.INDEX -> token.index
                    DocumentIndexColumn.BEGIN -> token.begin
                    DocumentIndexColumn.END -> token.end
                    DocumentIndexColumn.WORD -> token.word
                    DocumentIndexColumn.LEMMA -> token.lemma
                    DocumentIndexColumn.TAG -> token.tag
                    DocumentIndexColumn.NER -> token.ner
                    else -> error("Not a token column: $column")
                }
            }
        }
    }
    val dependencyCursor: (Int) -> Cursor = { ordinal ->
        val sentence = sentences[ordinal]
        sentence.dependencies.size j { position: Int ->
            val dependency = sentence.dependencies[position]
            documentRow(dependencyColumns) { column ->
                when (column) {
                    DocumentIndexColumn.ORIGINAL_CID -> originalCid.value
                    DocumentIndexColumn.EXTRACTED_TEXT_CID -> extractedTextCid.value
                    DocumentIndexColumn.SENTENCE -> sentence.index
                    DocumentIndexColumn.GOVERNOR -> dependency.governor
                    DocumentIndexColumn.DEPENDENT -> dependency.dependent
                    DocumentIndexColumn.RELATION -> dependency.relation
                    else -> error("Not a dependency column: $column")
                }
            }
        }
    }
    val sentenceCursor: Cursor = sentences.size j { ordinal: Int ->
        val sentence = sentences[ordinal]
        documentRow(sentenceColumns) { column ->
            when (column) {
                DocumentIndexColumn.ORIGINAL_CID -> originalCid.value
                DocumentIndexColumn.EXTRACTED_TEXT_CID -> extractedTextCid.value
                DocumentIndexColumn.SENTENCE -> sentence.index
                DocumentIndexColumn.BEGIN -> sentence.begin
                DocumentIndexColumn.END -> sentence.end
                DocumentIndexColumn.TOKENS -> tokenCursor(ordinal)
                DocumentIndexColumn.DEPENDENCIES -> dependencyCursor(ordinal)
                else -> error("Not a sentence column: $column")
            }
        }
    }
    return sentences.size j { operation: Any ->
        when (operation) {
            DocumentCurationIndexK.Source -> source
            DocumentCurationIndexK.Nlp -> nlp
            DocumentCurationIndexK.NlpStatus -> status()
            DocumentCurationIndexK.Reasons -> (reasons.size + validation.size) j { i: Int ->
                if (i < reasons.size) reasons[i] else validation[i - reasons.size]
            }
            DocumentCurationIndexK.Sentences -> sentences
            DocumentCurationIndexK.SentenceSpans -> sentences α { it.begin j it.end }
            DocumentCurationIndexK.Tokens -> tokens
            DocumentCurationIndexK.TokenSpans -> tokenSpans
            DocumentCurationIndexK.Ner -> ner
            DocumentCurationIndexK.Dependencies -> dependencies
            DocumentCurationIndexK.SentenceCursor -> sentenceCursor
            DocumentCurationIndexK.TokenCursor -> tokenCursor
            DocumentCurationIndexK.DependencyCursor -> dependencyCursor
            DocumentCurationIndexK.Model -> model
            DocumentCurationIndexK.ModelId -> modelId
            DocumentCurationIndexK.Proposals -> proposals
            DocumentCurationIndexK.ParseConfidence, DocumentCurationIndexK.ExpressedCertainty -> null
            DocumentCurationIndexK.ExternalVerification -> DocumentVerificationStatus.UNAVAILABLE
            else -> null
        }
    }
}

/** Invalid evidence stays inspectable. Consumers must gate derivation on NlpStatus. */
private fun DocumentSource.nlpErrors(nlp: NlpDocument?): Series<String> {
    if (nlp == null) return s_["nlp unavailable"]
    val errors = mutableListOf<String>()
    if (nlp.text != text) errors.add("nlp text differs from extracted text")
    if (text.isNotBlank() && nlp.sentences.size == 0) errors.add("nlp sentences unavailable for nonblank text")
    val sentenceIndices = mutableSetOf<Int>()
    var previousSentenceEnd = 0
    for (sentence in nlp.sentences.view) {
        if (sentence.index < 0 || !sentenceIndices.add(sentence.index))
            errors.add("nlp sentence index invalid: ${sentence.index}")
        if (sentence.begin < 0 || sentence.end < sentence.begin || sentence.end > text.length)
            errors.add("nlp sentence ${sentence.index} span invalid: ${sentence.begin}..${sentence.end}")
        if (sentence.begin < previousSentenceEnd)
            errors.add("nlp sentence ${sentence.index} span overlaps preceding sentence")
        previousSentenceEnd = sentence.end
        if (sentence.tokens.size == 0) errors.add("nlp sentence ${sentence.index} tokens unavailable")
        if (sentence.dependencies.size == 0) errors.add("nlp sentence ${sentence.index} dependencies unavailable")
        val tokenIndices = mutableSetOf<Int>()
        var previousTokenEnd = sentence.begin
        for (token in sentence.tokens.view) {
            if (token.index <= 0 || !tokenIndices.add(token.index))
                errors.add("nlp sentence ${sentence.index} token index invalid: ${token.index}")
            if (token.begin < sentence.begin || token.end <= token.begin || token.end > sentence.end)
                errors.add("nlp sentence ${sentence.index} token ${token.index} span invalid: ${token.begin}..${token.end}")
            if (token.begin < previousTokenEnd)
                errors.add("nlp sentence ${sentence.index} token ${token.index} span overlaps preceding token")
            previousTokenEnd = token.end
        }
        for (dependency in sentence.dependencies.view) {
            if ((dependency.governor != 0 && dependency.governor !in tokenIndices) ||
                dependency.dependent !in tokenIndices)
                errors.add("nlp sentence ${sentence.index} dependency endpoint invalid: ${dependency.governor}->${dependency.dependent}")
        }
    }
    return errors.toSeries()
}

private enum class DocumentIndexColumn(val columnName: String, val type: IOMemento) {
    ORIGINAL_CID("originalCid", IOMemento.IoString),
    EXTRACTED_TEXT_CID("extractedTextCid", IOMemento.IoString),
    SENTENCE("sentence", IOMemento.IoInt),
    INDEX("index", IOMemento.IoInt),
    BEGIN("begin", IOMemento.IoInt),
    END("end", IOMemento.IoInt),
    WORD("word", IOMemento.IoString),
    LEMMA("lemma", IOMemento.IoString),
    TAG("tag", IOMemento.IoString),
    NER("ner", IOMemento.IoString),
    GOVERNOR("governor", IOMemento.IoInt),
    DEPENDENT("dependent", IOMemento.IoInt),
    RELATION("relation", IOMemento.IoString),
    TOKENS("tokens", IOMemento.IoArray),
    DEPENDENCIES("dependencies", IOMemento.IoArray),
}

private val sentenceColumns = s_[DocumentIndexColumn.ORIGINAL_CID, DocumentIndexColumn.EXTRACTED_TEXT_CID,
    DocumentIndexColumn.SENTENCE, DocumentIndexColumn.BEGIN, DocumentIndexColumn.END,
    DocumentIndexColumn.TOKENS, DocumentIndexColumn.DEPENDENCIES]
private val tokenColumns = s_[DocumentIndexColumn.ORIGINAL_CID, DocumentIndexColumn.EXTRACTED_TEXT_CID,
    DocumentIndexColumn.SENTENCE, DocumentIndexColumn.INDEX, DocumentIndexColumn.BEGIN, DocumentIndexColumn.END,
    DocumentIndexColumn.WORD, DocumentIndexColumn.LEMMA, DocumentIndexColumn.TAG, DocumentIndexColumn.NER]
private val dependencyColumns = s_[DocumentIndexColumn.ORIGINAL_CID, DocumentIndexColumn.EXTRACTED_TEXT_CID,
    DocumentIndexColumn.SENTENCE, DocumentIndexColumn.GOVERNOR, DocumentIndexColumn.DEPENDENT, DocumentIndexColumn.RELATION]

private fun documentRow(columns: Series<DocumentIndexColumn>, value: (DocumentIndexColumn) -> Any?): RowVec =
    columns.size j { position: Int ->
        val column = columns[position]
        value(column) j { ColumnMeta(column.columnName, column.type) }
    }
