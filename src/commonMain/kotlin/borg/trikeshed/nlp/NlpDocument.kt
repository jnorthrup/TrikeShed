package borg.trikeshed.nlp

import borg.trikeshed.lib.Series

/** Offsets are UTF-16 indices into extracted text, never source-file byte offsets. */
data class NlpToken(
    val index: Int,
    val begin: Int,
    val end: Int,
    val word: String,
    val lemma: String,
    val tag: String,
    val ner: String,
)

/** Sentence-local, one-based indices; governor zero denotes the parser's explicit root. */
data class NlpDependency(val governor: Int, val dependent: Int, val relation: String)

/**
 * An open relation triple (subject; relation; object) as token-index spans of its sentence, with the
 * extractor's confidence. Natural-logic polarity is carried on the relation's own tokens.
 */
data class NlpRelation(
    val subject: IntRange, val relation: IntRange, val `object`: IntRange,
    val subjectText: String, val relationText: String, val objectText: String,
    val confidence: Double,
)

/** A coreference mention (sentence ordinal, token span) and the representative mention of its chain. */
data class NlpMention(val sentence: Int, val span: IntRange, val text: String, val chain: Int, val representative: Boolean)

data class NlpSentence(
    val index: Int,
    val begin: Int,
    val end: Int,
    val tokens: Series<NlpToken>,
    val dependencies: Series<NlpDependency>,
    val relations: List<NlpRelation> = emptyList(),
)

/**
 * Identity of the reader that produced a document, as observed at read time: the actual
 * [processor] class, the host [implementation] class, the [runtime] package/module/manifest
 * identity that was available, and the annotator [configuration] applied. Nothing here is
 * guessed: a version the runtime does not report is absent, not defaulted.
 */
data class NlpMetadata(
    val processor: String,
    val implementation: String,
    val runtime: Map<String, String> = emptyMap(),
    val configuration: Map<String, String> = emptyMap(),
)

/** [metadata] is null when the reader reports none: fixtures and records from before it existed. */
data class NlpDocument(
    val text: String, val sentences: Series<NlpSentence>, val metadata: NlpMetadata? = null,
    /** Coreference mentions across the whole text, when the reader resolves them. */
    val mentions: List<NlpMention> = emptyList(),
)

fun interface NlpReader {
    suspend fun read(text: String): NlpDocument
}
