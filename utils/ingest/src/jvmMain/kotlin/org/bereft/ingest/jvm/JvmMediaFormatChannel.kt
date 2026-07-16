package org.bereft.ingest.jvm

import org.bereft.ingest.IngestProjection
import org.bereft.ingest.MediaFormatChannel
import org.bereft.ingest.MediaFormatInfo
import java.io.File

/**
 * JVM implementation of [MediaFormatChannel]. Detection is suffix-based with a
 * small magic-byte sniff for the ambiguous cases (zip/pdf). No external deps —
 * this is the floor of the SPI; a Tika-backed implementation can layer on top.
 */
class JvmMediaFormatChannel : MediaFormatChannel {

    override fun detect(path: String): MediaFormatInfo {
        val file = File(path)
        val (mediaType, facet) = sniff(file)
        val projections = availableProjections(mediaType)
        return MediaFormatInfo(
            path = path,
            mediaType = mediaType,
            formatFacet = facet,
            confidence = if (file.exists()) 0.9 else 0.5,
            availableProjections = projections,
            sizeBytes = if (file.isFile) file.length() else 0L,
        )
    }

    override fun availableProjections(mediaType: String): Set<IngestProjection> =
        MediaFormatChannel.DEFAULT_PROJECTIONS[mediaType]
            ?: setOf(IngestProjection.METADATA)

    private fun sniff(file: File): Pair<String, String> {
        val name = file.name.lowercase()
        // Magic bytes first for the formats that carry them.
        if (file.isFile && file.length() >= 4) {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                input.read(head)
                val b0 = head[0].toInt() and 0xFF
                val b1 = head[1].toInt() and 0xFF
                val b2 = head[2].toInt() and 0xFF
                val b3 = head[3].toInt() and 0xFF
                when {
                    b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46 -> // %PDF
                        return "application/pdf" to "pdf"
                    b0 == 0x50 && b1 == 0x4B -> // PK
                        return "application/zip" to "zip"
                }
            }
        }
        return when {
            name.endsWith(".pdf") -> "application/pdf" to "pdf"
            name.endsWith(".md") || name.endsWith(".markdown") -> "text/markdown" to "markdown"
            name.endsWith(".json") -> "application/json" to "json"
            name.endsWith(".txt") -> "text/plain" to "text"
            name.endsWith(".py") -> "text/x-python" to "python"
            name.endsWith(".kt") || name.endsWith(".kts") -> "text/x-kotlin" to "kotlin"
            name.endsWith(".sh") || name.endsWith(".bash") -> "application/x-sh" to "shell"
            name.endsWith(".csv") -> "text/csv" to "csv"
            name.endsWith(".xml") -> "application/xml" to "xml"
            name.endsWith(".yaml") || name.endsWith(".yml") -> "application/yaml" to "yaml"
            name.endsWith(".toml") -> "application/toml" to "toml"
            name.endsWith(".log") -> "text/plain" to "log"
            name.endsWith(".memvid") -> "application/x-memvid" to "memvid"
            name.endsWith(".zip") -> "application/zip" to "zip"
            name.endsWith(".wav") -> "audio/wav" to "audio"
            name.endsWith(".mp3") -> "audio/mpeg" to "audio"
            name.endsWith(".mp4") -> "video/mp4" to "video"
            name.endsWith(".db") || name.endsWith(".sqlite") -> "application/x-sqlite3" to "sqlite"
            else -> "application/octet-stream" to "unknown"
        }
    }
}
