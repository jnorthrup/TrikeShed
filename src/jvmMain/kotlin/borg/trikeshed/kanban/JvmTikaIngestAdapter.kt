package borg.trikeshed.kanban

import org.apache.tika.Tika
import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.name

/**
 * JvmTikaIngestAdapter — JVM-side document text extraction via Apache Tika,
 * using the tweaked config ported from jnorthrup/tika4all (no treedoc):
 *
 *   - TesseractOCRParser enabled for images / scanned PDFs
 *   - ffmpeg preprocessing hook (src/jvmMain/resources/tika/ffmpeg_ocr.sh)
 *     applies grayscale + contrast equalization before OCR
 *
 * Only the text-extraction kernel is wired here. The tika4all flywheel
 * (NVIDIA NIM, SRDF, model-card evaluation, treedoc RAG) is intentionally
 * excluded — this adapter is a pure function Path -> extracted text.
 *
 * Usage:
 *   val markdown = JvmTikaIngestAdapter.extractToMarkdown(Path.of("scan.pdf"))
 *   ForgeKanbanIngest.persistMarkdown("jim", markdownPath)
 */
object JvmTikaIngestAdapter {

    private val defaultTika: Tika by lazy { Tika() }

    private val ocrConfiguredParser: AutoDetectParser? by lazy {
        runCatching {
            val configPath = Path.of("src/jvmMain/resources/tika/tika-config.xml")
            if (Files.exists(configPath)) {
                AutoDetectParser(TikaConfig(configPath))
            } else {
                // Packaged-jar fallback: extract resource to a temp file.
                val stream: InputStream? = javaClass.getResourceAsStream("/tika/tika-config.xml")
                stream?.use { s ->
                    val tmp = Files.createTempFile("tika-config", ".xml")
                    Files.copy(s, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    AutoDetectParser(TikaConfig(tmp))
                }
            }
        }.getOrNull()
    }

    /** True when the file extension is something Tika should handle (not plain markdown/text). */
    fun isTikaCandidate(path: Path): Boolean = when (path.extension.lowercase()) {
        "md", "markdown", "txt", "kt", "kts", "java", "py", "json", "xml", "html", "htm" -> false
        else -> true
    }

    /**
     * Extract text from [path]. Markdown/plaintext inputs are returned verbatim;
     * everything else goes through Tika auto-detection (PDF -> PDFBox, DOCX -> POI,
     * images -> Tesseract OCR with ffmpeg preprocessing when tesseract+ffmpeg
     * are installed; without them Tika degrades to metadata-only output).
     */
    fun extract(path: Path): String {
        if (!isTikaCandidate(path)) {
            return Files.readString(path)
        }
        val parser = ocrConfiguredParser
        return if (parser != null) {
            val handler = BodyContentHandler(-1) // no write limit
            val metadata = Metadata()
            path.inputStream().use { input ->
                parser.parse(input, handler, metadata, ParseContext())
            }
            handler.toString().trim()
        } else {
            defaultTika.parseToString(path).trim()
        }
    }

    /**
     * Extract [path] and wrap the result as a markdown document suitable for
     * [ForgeKanbanIngest.persistMarkdown]. The extracted body lands inside a
     * fenced section under a title derived from the filename, so the ingest
     * reducer sees ordinary markdown.
     */
    fun extractToMarkdown(path: Path): String {
        val body = extract(path)
        return buildString {
            append("# ").append(path.name).append('\n').append('\n')
            append(body).append('\n')
        }
    }
}
