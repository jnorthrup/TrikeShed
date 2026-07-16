@file:Suppress("ObjectPropertyName", "NonAsciiCharacters")

package org.bereft.ingest

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series

/**
 * What an [IngestSchedule] can produce from a source. The closed set of
 * projections the ingest seam knows how to apply. Each one maps to a
 * `ForgeBlockKind` family when a result is committed to a Forge document.
 *
 * Ported from the J01 ingest-cascade contract (legacy/ingest-cascade/) into
 * this standalone project. The CCEK reactor keeps its own
 * `borg.trikeshed.ccek.ProjectionKind` (DOCUMENT/BOARD/MARKDOWN) — this is the
 * media-side enum, not the document-projection one.
 */
enum class IngestProjection {
    TEXT_EXTRACTION,       // raw text out              → ForgeBlockKind.TEXT / HEADING_*
    TABLE_EXTRACTION,      // structured tables          → ForgeBlockKind.TABLE_ROW
    AUDIO_TRANSCRIPTION,   // speaker turns              → ForgeBlockKind.TEXT
    TAXONOMY,              // word-power scoring         → ForgeBlockKind.TEXT metadata
    COGNITIVE_LOAD,        // complexity scoring         → quality metrics
    METADATA;              // file metadata only         → block properties

    companion object {
        val ALL: Set<IngestProjection> = entries.toSet()
        val TEXT_ONLY: Set<IngestProjection> = setOf(TEXT_EXTRACTION, METADATA)
    }
}

/**
 * The result of one projection applied to one source. An ingest job emits one
 * [IngestResult] per (source, projection) pair down its result channel.
 *
 * `extractedContent` is a [Series]<Char> per PRELOAD: size paired with an index
 * oracle, no materialized copy. Read it via `content[i]` or `.view`.
 */
data class IngestResult(
    val sourcePath: String,
    val mediaType: String,                          // MIME-ish: "application/pdf", "audio/wav"
    val detectedFormat: String,                     // Confix facet: "pdf", "archive-zip", "audio-whisper"
    val extractedContent: Series<Char>,             // lazy char oracle — no copy until indexed
    val projections: Set<IngestProjection>,         // which projections produced this result
    val qualityMetrics: Map<String, Double> = emptyMap(),
    val processingTimeMs: Long = 0L,
    val blockIds: List<String> = emptyList(),       // ForgeBlockIds created from this ingest
) {
    /** PRELOAD-compliant content access: index the Series, don't copy it. */
    operator fun get(i: Int): Char = extractedContent.b(i)
    val contentSize: Int get() = extractedContent.size
}

/** Build a char [Series] from a plain string without materializing a second copy. */
fun charSeries(text: String): Series<Char> = text.length j { i -> text[i] }
