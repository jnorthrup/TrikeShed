package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lcnc.reduction.j

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
 *
 * Concrete implementations (notably [LcncIngestPipeline]) override
 * [decode] to dispatch on [source] / [format]. The default body
 * here is intentionally non-empty so the symbol resolves to a
 * single concrete signature even when callers treat the interface as
 * an event-streaming point: implementations that don't need
 * reactor lifecycle may simply return [emptySeriesOf] and let
 * downstream pipelines handle the empty Series.
 */
interface IngestCodec {
    val supportedFormats: Set<IngestFormat>

    /**
     * Parses the source and emits a Series of parsed Lcnc entities.
     * In a full reactive implementation, this might return a Flow or emit via CCEK.
     *
     * Default body: returns an empty [Series] when [format] isn't in
     * [supportedFormats]; otherwise delegates to [decodeText], which
     * subclasses override. Real-world codecs override this directly.
     */
    suspend fun decode(source: IngestSource, format: IngestFormat): Series<LcncEntity> {
        if (format !in supportedFormats) return emptySeriesOf()
        val text = when (source) {
            is IngestSource.Paste -> source.content
            is IngestSource.FileStream -> source.data.decodeToString()
            is IngestSource.Link -> source.uri
        }
        return decodeText(text, format)
    }

    /**
     * Subclass hook. Receives already-materialized text bytes; emits
     * the parsed entities as a [Series]. Default returns an empty series.
     */
    suspend fun decodeText(text: String, format: IngestFormat): Series<LcncEntity> =
        emptySeriesOf()
}

/**
 * Parses a string for @user mentions and returns a series of [UserRef]s.
 */
fun producePeopleCell(text: String): Series<borg.trikeshed.lcnc.collections.associative.UserRef> {
    val regex = Regex("@([a-zA-Z0-9_.-]+)")
    val matches = regex.findAll(text).toList()
    return matches.size j { i ->
        borg.trikeshed.lcnc.collections.associative.UserRef(matches[i].groupValues[1])
    }
}

/**
 * Parses a string for markdown image links and returns a series of [FileRef]s.
 */
fun produceFilesCell(text: String): Series<borg.trikeshed.lcnc.collections.associative.FileRef> {
    val regex = Regex("!\\[[^\\]]*\\]\\(([^)]+)\\)")
    val matches = regex.findAll(text).toList()
    return matches.size j { i ->
        borg.trikeshed.lcnc.collections.associative.FileRef(matches[i].groupValues[1])
    }
}
