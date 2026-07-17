package org.bereft.ingest.jvm

import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.toItem
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.confix.ConfixDoc
import org.apache.tika.Tika
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.bereft.ingest.IngestEnvelope
import org.bereft.ingest.IngestProjection
import org.bereft.ingest.MediaFormatChannel
import org.bereft.ingest.MediaFormatInfo
import java.io.File
import java.util.Locale

class TikaMediaFormatChannel(
    private val casStore: CasStore
) : MediaFormatChannel {

    private val tika = Tika()
    private val parser = AutoDetectParser()
    private val fallback = JvmMediaFormatChannel()

    override fun detect(path: String): MediaFormatInfo {
        val file = File(path)

        if (!file.exists() || !file.isFile) {
            return fallback.detect(path)
        }

        return try {
            val metadata = Metadata()
            metadata.set(Metadata.RESOURCE_NAME_KEY, file.name)
            
            val inputStream = TikaInputStream.get(file)
            val mediaType = tika.detect(inputStream, metadata)
            inputStream.close()
            
            val mimeType = mediaType.toString().lowercase(Locale.ROOT)
            val facet = mimeTypeToFacet(mimeType)
            val projections = availableProjections(mimeType)
            
            MediaFormatInfo(
                path = path,
                mediaType = mimeType,
                formatFacet = facet,
                confidence = 0.95,
                availableProjections = projections,
                sizeBytes = file.length(),
            )
        } catch (e: Exception) {
            fallback.detect(path)
        }
    }

    fun extract(path: String, requestedProjections: Set<IngestProjection>): IngestEnvelope {
        val file = File(path)
        val rawBytes = file.readBytes()
        val rawCid = casStore.put(rawBytes)

        val metadata = Metadata()
        metadata.set(Metadata.RESOURCE_NAME_KEY, file.name)

        val handler = BodyContentHandler(-1)
        val parseContext = ParseContext()

        var mimeType = "application/octet-stream"
        var facet = "unknown"

        try {
            // First pass for detection
            TikaInputStream.get(file).use { input ->
                mimeType = tika.detect(input, metadata).toString().lowercase(Locale.ROOT)
            }
            
            facet = mimeTypeToFacet(mimeType)
            if (metadata.get(Metadata.CONTENT_TYPE).isNullOrBlank()) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType)
            }

            // Second pass for parsing
            TikaInputStream.get(file).use { input ->
                parser.parse(input, handler, metadata, parseContext)
            }
        } catch (e: Exception) {
            // Best effort, continue with what we have
        }

        val extractedCids = mutableListOf<ContentId>()
        if (IngestProjection.TEXT_EXTRACTION in requestedProjections) {
            val extractedTextBytes = handler.toString().toByteArray(Charsets.UTF_8)
            if (extractedTextBytes.isNotEmpty()) {
                extractedCids.add(casStore.put(extractedTextBytes))
            }
        }

        val metadataMap = mutableMapOf<Item, Item>()
        val sortedNames = metadata.names().sorted()
        for (name in sortedNames) {
            val values = metadata.getValues(name)
            if (values.size == 1) {
                metadataMap[name.toItem()] = values[0].toItem()
            } else if (values.size > 1) {
                val list = values.map { it.toItem() }
                metadataMap[name.toItem()] = list.toItem()
            }
        }

        val metadataConfixDoc = mapToConfixDoc(metadataMap)
        val metadataCid = casStore.put(metadataConfixDoc)

        return IngestEnvelope(
            sourcePath = path,
            mediaType = mimeType,
            formatFacet = facet,
            projections = requestedProjections,
            rawPayloadCid = rawCid,
            extractedPayloadCids = extractedCids,
            canonicalMetadataCid = metadataCid,
            metadataCursor = metadataConfixDoc.roots
        )
    }

    private fun mapToConfixDoc(map: Map<Item, Item>): ConfixDoc {
        // Tika uses java.util.Map to hold string/list values.
        // We use CanonicalCbor.encode and Confix Parser from trikeshed.
        val canonicalCborBytes = borg.trikeshed.collections.associative.Cbor.encode(map.toItem())
        return parseCanonicalCborToConfixDoc(canonicalCborBytes)
    }

    private fun parseCanonicalCborToConfixDoc(cborBytes: ByteArray): ConfixDoc {
        val byteSeries = borg.trikeshed.lib.j(cborBytes.size) { i -> cborBytes[i] }
        val cursor = borg.trikeshed.parse.confix.Syntax.CBOR.scan(byteSeries)
        val index = borg.trikeshed.lib.j(borg.trikeshed.parse.confix.ConfixIndexK.TreeCursor, cursor)
        return borg.trikeshed.lib.j(index, byteSeries)
    }

    override fun availableProjections(mediaType: String): Set<IngestProjection> =
        MediaFormatChannel.DEFAULT_PROJECTIONS[mediaType.lowercase(Locale.ROOT)]
            ?: setOf(IngestProjection.METADATA)

    companion object {
        fun create(casStore: CasStore): TikaMediaFormatChannel = TikaMediaFormatChannel(casStore)
    }
}
