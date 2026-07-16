package org.bereft.ingest

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series

/**
 * SPI for media/format detection and projection routing.
 *
 * A platform implementation probes a source and returns which
 * [IngestProjection]s are available for that media type. This is the
 * Confix-aware access layer for media channels: it describes what is possible,
 * it does NOT execute extraction.
 *
 * Ported from the J01 ingest-cascade `MediaFormatChannel` contract.
 */
interface MediaFormatChannel {

    /** Detect the media type of a source path. */
    fun detect(path: String): MediaFormatInfo

    /** Return the projections available for a detected media type. */
    fun availableProjections(mediaType: String): Set<IngestProjection>

    companion object {
        /**
         * Canonical media-type → projection mapping. Platform implementations
         * may override, but this is the shared default so detection stays
         * consistent across targets.
         */
        val DEFAULT_PROJECTIONS: Map<String, Set<IngestProjection>> = mapOf(
            "application/pdf" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.TABLE_EXTRACTION, IngestProjection.METADATA),
            "text/plain" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.TAXONOMY, IngestProjection.COGNITIVE_LOAD, IngestProjection.METADATA),
            "text/markdown" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.TAXONOMY, IngestProjection.COGNITIVE_LOAD, IngestProjection.METADATA),
            "application/json" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA),
            "text/x-python" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.COGNITIVE_LOAD, IngestProjection.METADATA),
            "text/x-kotlin" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.COGNITIVE_LOAD, IngestProjection.METADATA),
            "application/x-sh" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA),
            "text/csv" to setOf(IngestProjection.TABLE_EXTRACTION, IngestProjection.METADATA),
            "application/xml" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA),
            "application/yaml" to setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA),
            "application/zip" to setOf(IngestProjection.METADATA),
            "application/x-sqlite3" to setOf(IngestProjection.TABLE_EXTRACTION, IngestProjection.METADATA),
            "audio/wav" to setOf(IngestProjection.AUDIO_TRANSCRIPTION, IngestProjection.METADATA),
            "audio/mpeg" to setOf(IngestProjection.AUDIO_TRANSCRIPTION, IngestProjection.METADATA),
            "video/mp4" to setOf(IngestProjection.AUDIO_TRANSCRIPTION, IngestProjection.METADATA),
        )
    }
}

/**
 * One row in a media catalog: a path, its detected type, its Confix facet, a
 * confidence score, and the projections available for it.
 */
data class MediaFormatInfo(
    val path: String,
    val mediaType: String,           // "application/pdf", "audio/wav"
    val formatFacet: String,         // Confix facet key: "pdf", "zip", "audio", "text"
    val confidence: Double,
    val availableProjections: Set<IngestProjection>,
    val sizeBytes: Long = 0L,
) {
    override fun toString(): String =
        "$path\t$mediaType\t$formatFacet\t${"%.2f".format(confidence)}\t${availableProjections.count()}\t$sizeBytes"
}

/**
 * A scanned directory as a lazy [Series] of [MediaFormatInfo] — PRELOAD shape:
 * size paired with an index oracle. No materialized list; project with `α`.
 */
fun interface Catalog {
    fun entries(): Series<MediaFormatInfo>
    val size: Int get() = entries().size
}
