package borg.trikeshed.kanban

import borg.trikeshed.graal.subvm.TikaRuntime
import borg.trikeshed.media.officeText
import borg.trikeshed.util.io.ContentTypes
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * JvmTikaIngestAdapter — Path -> extracted text. Markdown/plaintext verbatim; docx/pptx/xlsx through the commonMain
 * zip walker + [officeText] (raw deflate via `Inflater(true)`, no POI); only PDF and images go through managed Tika
 * ([TikaRuntime]: PDF OCR_STRATEGY.AUTO, images -> Tesseract when on PATH). Images first get the
 * tika4all pre-pass — grayscale + contrast/brightness equalisation — through the byte-pipe process SPI. That preserves the
 * old ffmpeg_ocr.sh filter for top-level raster images; managed Tika 3.2.3 has no `imageProcessingCommand` hook
 * for PDF-rendered OCR images.
 * CLI twin, same filter and same Tika config: src/jvmMain/resources/tika/run_tika.sh.
 */
object JvmTikaIngestAdapter {
    private val images = setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "heic")

    val ffmpeg = "ffmpeg"

    /** OCR pre-pass (was ffmpeg_ocr.sh): returns a temp PNG the caller deletes. */
    suspend fun preprocess(image: Path): Path {
        val out = Files.createTempFile("forge-ocr-", ".png")
        val processed = TikaRuntime.preprocessImageForOcr(
            bytes = Files.readAllBytes(image),
            name = image.fileName.toString(),
            mediaType = ContentTypes.forPath(image.fileName.toString()),
            options = TikaRuntime.OcrOptions(preprocessImages = true, ffmpegCommand = ffmpeg),
        )
        Files.write(out, processed)
        return out
    }

    /** True when the file is something Tika should handle (not plain markdown/text). Alias of [ingestRoute]. */
    fun isTikaCandidate(path: Path): Boolean = ingestRoute(path.fileName.toString()) != IngestRoute.Text

    /** Raw deflate (zip method 8) on the JVM; the trailing dummy byte is what `Inflater(nowrap=true)` asks for. */
    private val inflate: suspend (ByteArray) -> ByteArray = { raw ->
        val inf = java.util.zip.Inflater(true)
        val out = java.io.ByteArrayOutputStream(maxOf(raw.size * 4, 1 shl 12)); val buf = ByteArray(1 shl 16)
        try {
            inf.setInput(raw + 0)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally { inf.end() }
        out.toByteArray()
    }

    fun extract(path: Path): String {
        when (ingestRoute(path.fileName.toString())) {
            IngestRoute.Text -> return Files.readString(path)
            IngestRoute.Office -> return runBlocking { Files.readAllBytes(path).officeText(inflate) }
            else -> {}
        }
        val name = path.fileName.toString()
        val mediaType = ContentTypes.forPath(name)
        val options = if (path.extension.lowercase() in images) {
            TikaRuntime.TikaOptions(
                ocr = TikaRuntime.OcrOptions(preprocessImages = true, ffmpegCommand = ffmpeg),
            )
        } else {
            TikaRuntime.TikaOptions()
        }
        return TikaRuntime.extract(
            bytes = Files.readAllBytes(path),
            name = name,
            mediaType = mediaType,
            options = options,
        ).text.trim()
    }

    /** [extract] wrapped as markdown under a `# <filename>` heading, the shape [ForgeKanbanIngest] expects. */
    fun extractToMarkdown(path: Path): String = "# ${path.name}\n\n${extract(path)}\n"
}
