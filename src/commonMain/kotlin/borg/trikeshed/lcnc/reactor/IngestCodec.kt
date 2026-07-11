package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series

/**
 * Reactor asynchronous gems for parsing and digesting various formats.
 * Codecs parcelize machine-readable formats (pastes, files, links)
 * into structured ISAM/Associative taxonomies.
 */

/**
 * Defines the source of the ingested content.
 */
sealed class IngestSource {
    data class Paste(val content: String) : IngestSource()
    data class FileStream(val data: ByteArray) : IngestSource() // Simulating byte stream
    data class Link(val uri: String) : IngestSource()
}

/**
 * The expected format of the ingested content.
 */
enum class IngestFormat {
    CSV, TSV, MARKDOWN, HTML, JSON, LCNC_NATIVE
}

/**
 * A codec capable of decoding an IngestSource into Lcnc entities.
 * In the Reactor model, this can be an asynchronous operation yielding events.
 */
interface IngestCodec {
    val supportedFormats: Set<IngestFormat>

    /**
     * Parses the source and emits a Series of parsed Lcnc entities.
     * In a full reactive implementation, this might return a Flow or emit via CCEK.
     */
    suspend fun decode(source: IngestSource, format: IngestFormat): Series<LcncEntity>
}
