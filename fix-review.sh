#!/bin/bash
# 1. Update IngestResult to not be deleted to not break backwards compatibility
# 2. Update MediaFormatInfo.toRowVec to use `b(index)` and construct RowVec appropriately
cat << 'EOF2' > utils/ingest/src/commonMain/kotlin/org/bereft/ingest/MediaFormatChannel.kt
package org.bereft.ingest

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

interface MediaFormatChannel {
    fun detect(path: String): MediaFormatInfo
    fun availableProjections(mediaType: String): Set<IngestProjection>

    companion object {
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

data class MediaFormatInfo(
    val path: String,
    val mediaType: String,
    val formatFacet: String,
    val confidence: Double,
    val availableProjections: Set<IngestProjection>,
    val sizeBytes: Long = 0L,
) {
    override fun toString(): String =
        "$path\t$mediaType\t$formatFacet\t${"%.2f".format(confidence)}\t${availableProjections.count()}\t$sizeBytes"

    // Convert into a RowVec to comply with Cursor schema requirement.
    fun toRowVec(): RowVec {
        val metaPath: ColumnMeta = ColumnMeta("path", IOMemento.IoString)
        val metaType: ColumnMeta = ColumnMeta("mediaType", IOMemento.IoString)
        val metaFacet: ColumnMeta = ColumnMeta("formatFacet", IOMemento.IoString)
        val metaConf: ColumnMeta = ColumnMeta("confidence", IOMemento.IoDouble)
        val metaSize: ColumnMeta = ColumnMeta("sizeBytes", IOMemento.IoLong)
        
        val values = arrayOf<Any?>(path, mediaType, formatFacet, confidence, sizeBytes)
        val meta = arrayOf<ColumnMeta↻>({ metaPath }, { metaType }, { metaFacet }, { metaConf }, { metaSize })
        
        val valueSeries: Series<Any?> = values.size j { i -> values[i] }
        val metaSeries: Series<ColumnMeta↻> = meta.size j { i -> meta[i] }
        
        return valueSeries joins metaSeries
    }
}

fun interface Catalog {
    fun entries(): Series<MediaFormatInfo>
    val size: Int get() = entries().size
    
    // Expose catalog rows as a stable Cursor schema
    fun cursor(): Cursor = size j { i -> entries().b(i).toRowVec() }
}
EOF2

cat << 'EOF2' > utils/ingest/src/commonMain/kotlin/org/bereft/ingest/IngestResult.kt
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
EOF2
