package org.bereft.ingest.jvm

import org.apache.tika.Tika
import org.apache.tika.exception.TikaException
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.bereft.ingest.IngestProjection
import org.bereft.ingest.MediaFormatChannel
import org.bereft.ingest.MediaFormatInfo
import org.xml.sax.ContentHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Tika-backed implementation of [MediaFormatChannel].
 * 
 * Uses Apache Tika 3.x for robust media type detection and metadata extraction.
 * Falls back to suffix-based detection if Tika is unavailable or throws.
 * 
 * Configuration: uses default Tika config (AutoDetectParser with standard parsers).
 * For OCR support, ensure Tesseract is installed and add tika-config.xml to classpath
 * referencing TesseractOCRParser with image preprocessing enabled.
 */
class TikaMediaFormatChannel : MediaFormatChannel {

    private val tika = Tika()
    private val parser = AutoDetectParser()
    private val parseContext = ParseContext()
    private val fallback = JvmMediaFormatChannel()

    override fun detect(path: String): MediaFormatInfo {
        val file = File(path)
        
        // Fast path: if file doesn't exist, fall back to suffix-based
        if (!file.exists() || !file.isFile) {
            return fallback.detect(path)
        }

        return try {
            val metadata = Metadata()
            metadata.set(Metadata.RESOURCE_NAME_KEY, file.name)
            
            val inputStream = TikaInputStream.get(file)
            val mediaType = tika.detect(inputStream, metadata)
            
            val mimeType = mediaType.toString().lowercase(Locale.ROOT)
            val facet = mimeTypeToFacet(mimeType)
            
            // Extract rich metadata via Tika parser
            val richMetadata = extractMetadata(inputStream, metadata)
            
            val projections = availableProjections(mimeType)
            val confidence = 0.95
            
            MediaFormatInfo(
                path = path,
                mediaType = mimeType,
                formatFacet = facet,
                confidence = confidence,
                availableProjections = projections,
                sizeBytes = file.length(),
            )
        } catch (e: Exception) {
            // Fall back to suffix-based detection on any Tika error
            fallback.detect(path)
        }
    }

    override fun availableProjections(mediaType: String): Set<IngestProjection> =
        MediaFormatChannel.DEFAULT_PROJECTIONS[mediaType.lowercase(Locale.ROOT)]
            ?: setOf(IngestProjection.METADATA)

    /**
     * Extract rich metadata using Tika's AutoDetectParser.
     * Returns a map of metadata keys to values for projection enrichment.
     */
    private fun extractMetadata(input: TikaInputStream, metadata: Metadata): Map<String, String> {
        val handler = DefaultHandler()
        val result = mutableMapOf<String, String>()
        
        try {
            parser.parse(input, handler, metadata, parseContext)
            
            // Extract all metadata fields
            for (name in metadata.names()) {
                val value = metadata.get(name)
                if (value.isNotBlank()) {
                    result[name] = value
                }
            }
            
            // Add common derived fields
            if (metadata.get(Metadata.CONTENT_TYPE).isNullOrBlank()) {
                result["tika:detectedType"] = detector.detect(input, metadata).toString()
            }
            
        } catch (e: Exception) {
            // Metadata extraction is best-effort; don't fail detection
        } finally {
            input.close()
        }
        
        return result
    }

    companion object {
        /** Create a new Tika-backed channel. */
        fun create(): TikaMediaFormatChannel = TikaMediaFormatChannel()
    }
}