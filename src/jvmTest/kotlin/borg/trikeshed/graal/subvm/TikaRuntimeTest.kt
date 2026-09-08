package borg.trikeshed.graal.subvm

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TikaRuntimeTest {

    @Test
    fun missingModuleRefusesBeforeHostClasspathFallback() = withSubvmHome(Files.createTempDirectory("tika-empty-")) {
        val err = assertFailsWith<IllegalStateException> {
            TikaRuntime.extract(
                bytes = "host fallback must not parse".encodeToByteArray(),
                name = "fallback.html",
                mediaType = "text/html",
            )
        }
        val msg = err.message.orEmpty()
        assertTrue("guest module 'tika' is not installed" in msg, msg)
        assertTrue("installTika" in msg, msg)
    }

    @Test
    fun unicodeHtmlExtractsThroughMountedTika() {
        requireTikaModule()
        val text = "LLP curator feed unicode: cafe \u00e9lan \u03a9 \u6f22\u5b57"
        val html = "<!doctype html><html><body><p>$text</p></body></html>"
        val out = TikaRuntime.extract(
            bytes = html.encodeToByteArray(),
            name = "llp-unicode.html",
            mediaType = "text/html",
        )

        assertTrue(text in out.text, "expected full unicode text in managed Tika output, got: ${out.text}")
        assertEquals("llp-unicode.html", out.name)
        assertEquals("text/html", out.mediaType)
        assertEquals(TikaRuntime.MODULE, out.module)
        assertTrue(out.metadata.isNotEmpty(), "expected Tika metadata")
    }

    @Test
    fun byteExtractionKeepsResourceNameMetadata() {
        requireTikaModule()
        val out = TikaRuntime.extract(
            bytes = "<html><body><p>byte sidecar token rtx-17</p></body></html>".encodeToByteArray(),
            name = "byte-source.html",
            mediaType = "text/html",
        )
        assertTrue("rtx-17" in out.text, "expected byte text in managed Tika output, got: ${out.text}")
        assertEquals("byte-source.html", out.name)
        assertTrue(
            out.metadata["resourceName"].orEmpty().contains("byte-source.html"),
            "resourceName metadata missing from ${out.metadata}",
        )
    }

    @Test
    fun defaultOptionsPreserveExistingByteCallers() {
        val options = TikaRuntime.TikaOptions()
        assertEquals(TikaRuntime.TikaCompatibility.MANAGED_3_2_3, options.compatibility)
        assertEquals(TikaRuntime.PdfOcrStrategy.AUTO, options.ocr.pdfStrategy)
        assertFalse(options.ocr.preprocessImages, "default extract must not require ffmpeg")
        assertFalse(options.ocr.requireTesseract, "default extract must not require tesseract")
        assertEquals(TikaRuntime.UNLIMITED_TEXT_CHARS, options.limits.maxExtractedChars)
    }

    @Test
    fun managedModuleEvidencePinsAggregateRecipeAndResolvedArtifacts() {
        requireTikaModule()
        val out = TikaRuntime.extract(
            bytes = "<html><body><p>managed recipe token ktr-32</p></body></html>".encodeToByteArray(),
            name = "managed-recipe.html",
            mediaType = "text/html",
        )

        assertEquals(TikaRuntime.MANAGED_TIKA_COORDINATES, out.source.module.declared)
        assertTrue(out.source.module.artifactCount >= TikaRuntime.MANAGED_TIKA_REQUIRED_ARTIFACTS.size)
        assertTrue(out.source.module.artifactBytes > 1_000_000)
        assertTrue("tika-parser-pdf-module-${TikaRuntime.TIKA_VERSION}.jar" in out.source.module.requiredArtifacts)
        assertTrue("tika-parser-ocr-module-${TikaRuntime.TIKA_VERSION}.jar" in out.source.module.requiredArtifacts)
    }

    @Test
    fun managedLoaderOwnsTikaParserClass() {
        requireTikaModule()
        val loader = GuestModules.loaderFor(TikaRuntime.MODULE)
        assertNotNull(loader, "expected managed Tika loader")

        val parserClass = loader.loadClass("org.apache.tika.parser.AutoDetectParser")
        assertSame(loader, parserClass.classLoader, "AutoDetectParser must come from the managed guest module")
    }

    @Test
    fun unsupportedCompatibilityModeIsRejectedExplicitly() {
        val err = assertFailsWith<IllegalStateException> {
            TikaRuntime.extract(
                bytes = "no shell wrapped tika".encodeToByteArray(),
                name = "mode.txt",
                mediaType = "text/plain",
                options = TikaRuntime.TikaOptions(compatibility = TikaRuntime.TikaCompatibility.TIKA4ALL_LAUNCHER),
            )
        }
        assertTrue("TIKA4ALL_LAUNCHER" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun pdfFfmpegPreprocessingIsRejectedBecauseTikaHasNoHook() {
        val err = assertFailsWith<IllegalStateException> {
            TikaRuntime.extract(
                bytes = "%PDF-1.4\n%%EOF".encodeToByteArray(),
                name = "scan.pdf",
                mediaType = "application/pdf",
                options = TikaRuntime.TikaOptions(
                    ocr = TikaRuntime.OcrOptions(preprocessPdfImages = true),
                ),
            )
        }
        assertTrue("preprocessPdfImages is unsupported" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun bytePipeFfmpegPreprocessGraysRasterImage() {
        val ffmpeg = requireCommand("ffmpeg")
        val input = solidPng(32, 32, Color(0xCC, 0x22, 0x00))

        val processed = TikaRuntime.preprocessImageForOcr(
            bytes = input,
            name = "red.png",
            mediaType = "image/png",
            options = TikaRuntime.OcrOptions(preprocessImages = true, ffmpegCommand = ffmpeg),
        )

        val gray = ImageIO.read(processed.inputStream())
        val p = gray.getRGB(16, 16)
        val r = p shr 16 and 0xFF
        val g = p shr 8 and 0xFF
        val b = p and 0xFF
        assertTrue(r == g && g == b, "expected gray FFmpeg output, got $r/$g/$b")
    }

    @Test
    fun realImageOcrUsesManagedTikaWithBytePipePreprocess() {
        requireTikaModule()
        val ffmpeg = requireCommand("ffmpeg")
        val tesseract = requireCommand("tesseract")
        val input = textPng("TRIKE OCR 7421")

        val out = TikaRuntime.extract(
            bytes = input,
            name = "ocr-token.png",
            mediaType = "image/png",
            options = TikaRuntime.TikaOptions(
                ocr = TikaRuntime.OcrOptions(
                    preprocessImages = true,
                    ffmpegCommand = ffmpeg,
                    tesseractCommand = tesseract,
                    requireTesseract = true,
                ),
            ),
        )

        val compact = out.text.uppercase().filter { it.isLetterOrDigit() }
        assertTrue("TRIKE" in compact, "expected OCR word in managed Tika output, got: ${out.text}")
        assertTrue("7421" in compact, "expected OCR digits in managed Tika output, got: ${out.text}")
        assertEquals(listOf("ffmpeg:${TikaRuntime.TIKA4ALL_FFMPEG_FILTER}"), out.source.transforms)
        assertEquals(input.size, out.source.originalBytes)
        assertTrue(out.source.parsedBytes > 0)
        assertEquals("image/png", out.source.parsedMediaType)
        assertTrue(
            out.metadata["trikeshed:source:transform"].orEmpty()
                .contains("ffmpeg:${TikaRuntime.TIKA4ALL_FFMPEG_FILTER}"),
            "source transform metadata missing from ${out.metadata}",
        )
    }

    @Test
    fun requiredOcrUsesManagedDetectionBeforeCallerHints() {
        requireTikaModule()
        val ffmpeg = requireCommand("ffmpeg")
        val tesseract = requireCommand("tesseract")
        val input = textPng("TRIKE OCR 7421")

        val out = TikaRuntime.extract(
            bytes = input,
            name = "misleading.txt",
            mediaType = "text/plain",
            options = TikaRuntime.TikaOptions(
                ocr = TikaRuntime.OcrOptions(
                    preprocessImages = true,
                    ffmpegCommand = ffmpeg,
                    tesseractCommand = tesseract,
                    requireTesseract = true,
                ),
            ),
        )

        val compact = out.text.uppercase().filter { it.isLetterOrDigit() }
        assertTrue("TRIKE" in compact, "expected detected-image OCR output, got: ${out.text}")
        assertEquals("text/plain", out.source.originalMediaType)
        assertEquals("image/png", out.source.parsedMediaType)
        assertEquals("misleading.png", out.source.parsedName)
        assertEquals(listOf("ffmpeg:${TikaRuntime.TIKA4ALL_FFMPEG_FILTER}"), out.source.transforms)
    }

    @Test
    fun arbitraryTesseractBasenameIsRejected() {
        requireTikaModule()
        val err = assertFailsWith<IllegalStateException> {
            TikaRuntime.extract(
                bytes = solidPng(16, 16, Color.WHITE),
                name = "ocr-token.png",
                mediaType = "image/png",
                options = TikaRuntime.TikaOptions(
                    ocr = TikaRuntime.OcrOptions(
                        tesseractCommand = "/tmp/not-the-tesseract-binary",
                        requireTesseract = true,
                    ),
                ),
            )
        }
        assertTrue("must name" in err.message.orEmpty(), err.message.orEmpty())
        assertTrue("TesseractOCRParser.setTesseractPath" in err.message.orEmpty(), err.message.orEmpty())
    }

    @Test
    fun tika4allBaselineIsExternalEvidenceNotRuntimeApi() {
        assertEquals("org.apache.tika:tika-parent:3.0.0", LegacyTika4AllEvidence.parent)
        assertTrue("org.apache.tika:tika-parsers:3.0.0-BETA2" in LegacyTika4AllEvidence.declared)
        assertTrue(LegacyTika4AllEvidence.declared != TikaRuntime.MANAGED_TIKA_COORDINATES)
        assertTrue(LegacyTika4AllEvidence.defects.any { "temporary project.basedir" in it })
        assertTrue(LegacyTika4AllEvidence.defects.any { "generated classpath" in it })
        assertTrue(LegacyTika4AllEvidence.incompatibilities.any { "imageProcessingCommand" in it })
        assertFalse(
            TikaRuntime::class.java.declaredClasses.any { it.simpleName == "Tika4AllBaseline" },
            "legacy launcher provenance must not be a portable runtime API",
        )
    }

    @Test
    fun longUnicodeExtractKeepsTailBeyondDefaultHandlerLimit() {
        requireTikaModule()
        val tail = "TAIL-SENTINEL-\u03c0-\u7d42"
        val text = buildString {
            append("LONG-BEGIN ")
            repeat(30_000) { append("\u00e9\u03a9\u6f22\u5b57") }
            append(' ')
            append(tail)
        }
        val out = TikaRuntime.extract(
            bytes = "<html><body><p>$text</p></body></html>".encodeToByteArray(),
            name = "long-unicode.html",
            mediaType = "text/html",
        )

        assertTrue(out.text.length > 100_000, "expected full text beyond default Tika limit, got ${out.text.length}")
        assertTrue(tail in out.text, "expected long tail sentinel in managed Tika output")
    }

    @Test
    fun contextLoaderIsRestoredAfterSuccessAndParseFailure() {
        requireTikaModule()
        val thread = Thread.currentThread()
        val prior = thread.contextClassLoader
        val sentinel = object : ClassLoader(null) {}
        thread.contextClassLoader = sentinel
        try {
            TikaRuntime.extract(
                bytes = "<html><body><p>context loader success</p></body></html>".encodeToByteArray(),
                name = "loader.html",
                mediaType = "text/html",
            )
            assertSame(sentinel, thread.contextClassLoader, "context loader was not restored after success")

            val failure = runCatching {
                TikaRuntime.extract(
                    bytes = "%PDF-1.4\nnot a valid pdf body\n%%EOF".encodeToByteArray(),
                    name = "broken.pdf",
                    mediaType = "application/pdf",
                )
            }.exceptionOrNull()
            assertNotNull(failure, "expected corrupt PDF parse failure")
            assertSame(sentinel, thread.contextClassLoader, "context loader was not restored after parse failure")
        } finally {
            thread.contextClassLoader = prior
        }
    }

    private fun requireTikaModule() {
        assertTrue(
            GuestModules.isInstalled(TikaRuntime.MODULE),
            "guest module '${TikaRuntime.MODULE}' is not installed - run: ./gradlew -p utils/subvm installTika",
        )
    }

    private fun withSubvmHome(home: java.nio.file.Path, block: () -> Unit) {
        val prior = System.getProperty(GuestModules.HOME_PROPERTY)
        System.setProperty(GuestModules.HOME_PROPERTY, home.toAbsolutePath().toString())
        try {
            block()
        } finally {
            if (prior == null) System.clearProperty(GuestModules.HOME_PROPERTY)
            else System.setProperty(GuestModules.HOME_PROPERTY, prior)
            home.toFile().deleteRecursively()
        }
    }

    private fun commandPath(command: String): String? {
        val dirs = LinkedHashSet<String>()
        System.getenv("PATH")?.split(File.pathSeparator)?.filterTo(dirs) { it.isNotBlank() }
        dirs += "/opt/homebrew/bin"
        dirs += "/usr/local/bin"
        dirs += "/usr/bin"
        dirs += "/bin"
        return dirs.asSequence()
            .map { File(it, command) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    private fun requireCommand(command: String): String =
        commandPath(command) ?: error("native command '$command' is required for this OCR test")

    private fun solidPng(width: Int, height: Int, color: Color): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return pngBytes(image)
    }

    private fun textPng(text: String): ByteArray {
        val image = BufferedImage(900, 240, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 78)
            g.drawString(text, 50, 145)
        } finally {
            g.dispose()
        }
        return pngBytes(image)
    }

    private fun pngBytes(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private object LegacyTika4AllEvidence {
        const val parent = "org.apache.tika:tika-parent:3.0.0"
        val declared = listOf(
            "org.apache.tika:tika-core:3.0.0-BETA2",
            "org.apache.tika:tika-parsers:3.0.0-BETA2",
            "org.apache.logging.log4j:log4j-core:2.20.0",
            "org.slf4j:slf4j-api:1.7.36",
        )
        val defects = listOf(
            "embedded POM resolves tika-local under the launcher's temporary project.basedir; no repo is staged there",
            "org.apache.tika:tika-parsers:jar:3.0.0-BETA2 did not resolve from Maven Central during probe",
            "generated classpath is written but not used by the java -jar launch",
            "no shade/app plugin is declared to make the default project jar a standalone Tika app",
        )
        val incompatibilities = listOf(
            "managed Tika ${TikaRuntime.TIKA_VERSION} uses tika-parsers-standard-package, not the old tika-parsers BETA2 artifact",
            "imageProcessingCommand is not an available managed Tika ${TikaRuntime.TIKA_VERSION} OCR config API",
        )
    }
}
