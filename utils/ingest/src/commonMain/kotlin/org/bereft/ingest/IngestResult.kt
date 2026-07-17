@file:Suppress("ObjectPropertyName", "NonAsciiCharacters")

package org.bereft.ingest

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series

enum class IngestProjection {
    TEXT_EXTRACTION,
    TABLE_EXTRACTION,
    AUDIO_TRANSCRIPTION,
    TAXONOMY,
    COGNITIVE_LOAD,
    METADATA;

    companion object {
        val ALL: Set<IngestProjection> = entries.toSet()
        val TEXT_ONLY: Set<IngestProjection> = setOf(TEXT_EXTRACTION, METADATA)
    }
}

/**
 * Legacy IngestResult. Kept for backwards compatibility.
 * Prefer IngestEnvelope which carries Confix/CAS contract.
 */
data class IngestResult(
    val sourcePath: String,
    val mediaType: String,
    val detectedFormat: String,
    val extractedContent: Series<Char>,
    val projections: Set<IngestProjection>,
    val qualityMetrics: Map<String, Double> = emptyMap(),
    val processingTimeMs: Long = 0L,
    val blockIds: List<String> = emptyList(),
) {
    operator fun get(i: Int): Char = extractedContent.b(i)
    val contentSize: Int get() = extractedContent.size
}

fun charSeries(text: String): Series<Char> = text.length j { i -> text[i] }
