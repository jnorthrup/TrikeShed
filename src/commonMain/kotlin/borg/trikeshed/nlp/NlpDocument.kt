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

data class NlpSentence(
    val index: Int,
    val begin: Int,
    val end: Int,
    val tokens: Series<NlpToken>,
    val dependencies: Series<NlpDependency>,
)

data class NlpDocument(val text: String, val sentences: Series<NlpSentence>)

fun interface NlpReader {
    suspend fun read(text: String): NlpDocument
}
