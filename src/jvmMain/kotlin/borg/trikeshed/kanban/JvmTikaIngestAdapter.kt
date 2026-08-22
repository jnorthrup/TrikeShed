package borg.trikeshed.kanban

import borg.trikeshed.userspace.nio.process.ProcessCapability
import borg.trikeshed.userspace.nio.process.ProcessSpec
import borg.trikeshed.userspace.nio.process.ProcessWorkerFactory
import kotlinx.coroutines.runBlocking
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.ocr.TesseractOCRConfig
import org.apache.tika.parser.pdf.PDFParserConfig
import org.apache.tika.sax.BodyContentHandler
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.name

/**
 * JvmTikaIngestAdapter — Path -> extracted text. Markdown/plaintext verbatim; everything else through Tika
 * (PDF -> PDFBox with OCR_STRATEGY.AUTO, DOCX -> POI, images -> Tesseract when on PATH). Images first get the
 * tika4all pre-pass — grayscale + contrast/brightness equalisation — run through ffmpeg via the process factory. This used to be tika-config.xml +
 * ffmpeg_ocr.sh; Tika 3 has no `imageProcessingCommand` param, so that config never loaded and the script never ran.
 */
object JvmTikaIngestAdapter {
    private val parser = AutoDetectParser()
    private val context = ParseContext().apply {
        set(TesseractOCRConfig::class.java, TesseractOCRConfig())
        set(PDFParserConfig::class.java, PDFParserConfig().apply { ocrStrategy = PDFParserConfig.OCR_STRATEGY.AUTO })
    }
    private val worker = ProcessWorkerFactory.create(ProcessCapability("forge-ingest", setOf("ffmpeg")))
    private val images = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "heic")

    val ffmpeg = "ffmpeg"

    /** OCR pre-pass (was ffmpeg_ocr.sh): returns a temp PNG the caller deletes. */
    suspend fun preprocess(image: Path): Path {
        val out = Files.createTempFile("forge-ocr-", ".png")
        val r = worker.spawn(ProcessSpec(ffmpeg, listOf("-y", "-i", image.toString(), "-vf",
            "format=gray,eq=contrast=1.5:brightness=0.1:gamma=1.0:saturation=0.0", out.toString()), timeoutMs = 120_000))
        require(r.exitCode == 0) { "ffmpeg exit ${r.exitCode}: ${r.stderr.decodeToString().takeLast(300)}" }
        return out
    }

    /** True when the file extension is something Tika should handle (not plain markdown/text). */
    fun isTikaCandidate(path: Path): Boolean = when (path.extension.lowercase()) {
        "md", "markdown", "txt", "kt", "kts", "java", "py", "json", "xml", "html", "htm" -> false
        else -> true
    }

    fun extract(path: Path): String {
        if (!isTikaCandidate(path)) return Files.readString(path)
        val src = if (path.extension.lowercase() in images) runBlocking { preprocess(path) } else path
        try {
            val handler = BodyContentHandler(-1)
            src.inputStream().use { parser.parse(it, handler, Metadata(), context) }
            return handler.toString().trim()
        } finally { if (src !== path) Files.deleteIfExists(src) }
    }

    /** [extract] wrapped as markdown under a `# <filename>` heading, the shape [ForgeKanbanIngest] expects. */
    fun extractToMarkdown(path: Path): String = "# ${path.name}\n\n${extract(path)}\n"
}
