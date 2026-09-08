package borg.trikeshed.graal.subvm

import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Managed Apache Tika invocation through the mounted `utils/subvm/tika` module.
 *
 * This keeps Tika out of the host API surface: callers hand original bytes plus optional
 * name/media-type hints, and every Tika class is resolved from [GuestModules.loaderFor].
 */
object TikaRuntime {

    const val MODULE: String = "tika"
    const val TIKA_VERSION: String = "3.2.3"
    const val TIKA4ALL_FFMPEG_FILTER: String = "format=gray,eq=contrast=1.5:brightness=0.1:gamma=1.0:saturation=0.0"
    const val UNLIMITED_TEXT_CHARS: Int = -1
    const val DEFAULT_MAX_INPUT_BYTES: Int = 256 * 1024 * 1024

    val MANAGED_TIKA_COORDINATES: List<String> = listOf(
        "org.apache.tika:tika-core:$TIKA_VERSION",
        "org.apache.tika:tika-parsers-standard-package:$TIKA_VERSION",
    )

    val MANAGED_TIKA_REQUIRED_ARTIFACTS: List<String> = listOf(
        "tika-core-$TIKA_VERSION.jar",
        "tika-parsers-standard-package-$TIKA_VERSION.jar",
        "tika-parser-html-module-$TIKA_VERSION.jar",
        "tika-parser-image-module-$TIKA_VERSION.jar",
        "tika-parser-microsoft-module-$TIKA_VERSION.jar",
        "tika-parser-miscoffice-module-$TIKA_VERSION.jar",
        "tika-parser-ocr-module-$TIKA_VERSION.jar",
        "tika-parser-pdf-module-$TIKA_VERSION.jar",
        "tika-parser-text-module-$TIKA_VERSION.jar",
        "tika-parser-xml-module-$TIKA_VERSION.jar",
    )

    enum class TikaCompatibility {
        MANAGED_3_2_3,
        TIKA4ALL_LAUNCHER,
    }

    enum class PdfOcrStrategy {
        AUTO,
        NO_OCR,
        OCR_ONLY,
        OCR_AND_TEXT_EXTRACTION,
    }

    data class TikaLimits(
        /** Byte-array boundary for caller-supplied document bytes; callers own acquisition. */
        val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
        /** BodyContentHandler limit. -1 keeps Tika's output unbounded for full-text preservation. */
        val maxExtractedChars: Int = UNLIMITED_TEXT_CHARS,
    ) {
        init {
            require(maxInputBytes > 0) { "maxInputBytes must be > 0" }
            require(maxExtractedChars == UNLIMITED_TEXT_CHARS || maxExtractedChars > 0) {
                "maxExtractedChars must be -1 or > 0"
            }
        }
    }

    data class OcrOptions(
        val pdfStrategy: PdfOcrStrategy = PdfOcrStrategy.AUTO,
        val language: String = "eng",
        val timeoutSeconds: Int = 120,
        val tesseractCommand: String? = null,
        val requireTesseract: Boolean = false,
        /** Top-level raster images are preprocessed through FFmpeg stdin/stdout before Tika sees them. */
        val preprocessImages: Boolean = false,
        /** Tika 3.2.3 has no FFmpeg hook for PDF-rendered OCR images; requesting it is rejected. */
        val preprocessPdfImages: Boolean = false,
        val ffmpegCommand: String = "ffmpeg",
        val ffmpegFilter: String = TIKA4ALL_FFMPEG_FILTER,
    ) {
        init {
            require(language.isNotBlank()) { "language must not be blank" }
            require(timeoutSeconds > 0) { "timeoutSeconds must be > 0" }
            require(ffmpegCommand.isNotBlank()) { "ffmpegCommand must not be blank" }
            require(ffmpegFilter.isNotBlank()) { "ffmpegFilter must not be blank" }
        }
    }

    data class TikaOptions(
        val limits: TikaLimits = TikaLimits(),
        val ocr: OcrOptions = OcrOptions(),
        val compatibility: TikaCompatibility = TikaCompatibility.MANAGED_3_2_3,
    )

    data class TikaModuleEvidence(
        val module: String,
        val declared: List<String>,
        val requiredArtifacts: List<String>,
        val artifactCount: Int,
        val artifactBytes: Long,
    )

    data class TikaSource(
        val originalBytes: Int,
        val parsedBytes: Int,
        val originalName: String?,
        val parsedName: String?,
        val originalMediaType: String?,
        val parsedMediaType: String?,
        val transforms: List<String>,
        val module: TikaModuleEvidence,
    )

    data class TikaExtract(
        val text: String,
        val metadata: Map<String, List<String>>,
        val name: String?,
        val mediaType: String?,
        val module: String,
        val source: TikaSource,
    )

    fun extract(
        bytes: ByteArray,
        name: String? = null,
        mediaType: String? = null,
        module: String = MODULE,
        options: TikaOptions = TikaOptions(),
    ): TikaExtract {
        rejectUnsupported(options)
        check(bytes.size <= options.limits.maxInputBytes) {
            "tika input '${name ?: "<bytes>"}' is ${bytes.size} bytes, above maxInputBytes=${options.limits.maxInputBytes}"
        }
        check(GuestModules.isInstalled(module)) {
            "guest module '$module' is not installed - install it: " +
                "./gradlew -p utils/subvm install${gradleTaskName(module)}"
        }
        val moduleEvidence = validateManagedModule(module)
        val loader = GuestModules.loaderFor(module)
            ?: throw IllegalStateException("guest module '$module' resolved no classpath to mount")
        return try {
            withLoader(loader) {
                val bridge = Bridge(loader)
                val tikaConfig = bridge.tikaConfig()
                val detectedMediaType = bridge.detect(tikaConfig, bytes, name)
                val input = prepareInput(bytes, name, mediaType, detectedMediaType, options, moduleEvidence)
                bridge.extract(tikaConfig, input, module, options)
            }
        } catch (t: InvocationTargetException) {
            val cause = t.targetException ?: t
            throw IllegalStateException(
                "tika extract failed for '${name ?: "<bytes>"}': ${cause.message ?: cause::class.java.name}",
                cause,
            )
        } catch (t: ClassNotFoundException) {
            throw IllegalStateException(
                "guest module '$module' is missing a Tika class: ${t.message}. " +
                    "Re-resolve it: ./gradlew -p utils/subvm install${gradleTaskName(module)}",
                t,
            )
        } catch (t: NoSuchMethodException) {
            throw IllegalStateException(
                "guest module '$module' has an incompatible Tika API: ${t.message}. " +
                    "Re-resolve it: ./gradlew -p utils/subvm install${gradleTaskName(module)}",
                t,
            )
        }
    }

    internal fun preprocessImageForOcr(
        bytes: ByteArray,
        name: String? = null,
        mediaType: String? = null,
        options: OcrOptions = OcrOptions(),
    ): ByteArray {
        check(isRasterImage(name, mediaType)) {
            "ffmpeg preprocessing is supported only for top-level raster images, got name=${name ?: "<none>"} mediaType=${mediaType ?: "<none>"}"
        }
        return ffmpegPreprocess(bytes, name, options).bytes
    }

    internal class Bridge(private val loader: ClassLoader) {
        private val parserClass: Class<*> = loader.loadClass("org.apache.tika.parser.AutoDetectParser")
        private val parserInterfaceClass: Class<*> = loader.loadClass("org.apache.tika.parser.Parser")
        private val compositeParserClass: Class<*> = loader.loadClass("org.apache.tika.parser.CompositeParser")
        private val parserDecoratorClass: Class<*> = loader.loadClass("org.apache.tika.parser.ParserDecorator")
        private val tesseractParserClass: Class<*> = loader.loadClass("org.apache.tika.parser.ocr.TesseractOCRParser")
        private val tikaConfigClass: Class<*> = loader.loadClass("org.apache.tika.config.TikaConfig")
        private val tikaInputStreamClass: Class<*> = loader.loadClass("org.apache.tika.io.TikaInputStream")
        private val initializableProblemHandlerClass: Class<*> =
            loader.loadClass("org.apache.tika.config.InitializableProblemHandler")
        private val initializableThrowHandler: Any = initializableProblemHandlerClass.getField("THROW").get(null)
        private val metadataClass: Class<*> = loader.loadClass("org.apache.tika.metadata.Metadata")
        private val parseContextClass: Class<*> = loader.loadClass("org.apache.tika.parser.ParseContext")
        private val bodyContentHandlerClass: Class<*> = loader.loadClass("org.apache.tika.sax.BodyContentHandler")
        private val contentHandlerClass: Class<*> = Class.forName("org.xml.sax.ContentHandler")

        private val parseMethod: Method = parserClass.getMethod(
            "parse",
            InputStream::class.java,
            contentHandlerClass,
            metadataClass,
            parseContextClass,
        )
        private val metadataSetString: Method =
            metadataClass.getMethod("set", String::class.java, String::class.java)
        private val metadataAddString: Method =
            metadataClass.getMethod("add", String::class.java, String::class.java)
        private val metadataNames: Method = metadataClass.getMethod("names")
        private val metadataValues: Method = metadataClass.getMethod("getValues", String::class.java)
        private val contextSet: Method = parseContextClass.getMethod("set", Class::class.java, Any::class.java)
        private val tikaConfigConstructor = tikaConfigClass.getConstructor(ClassLoader::class.java)
        private val tikaConfigGetDetector = tikaConfigClass.getMethod("getDetector")
        private val tikaInputStreamGetBytes = tikaInputStreamClass.getMethod("get", ByteArray::class.java, metadataClass)
        private val parserConstructor = parserClass.getConstructor(tikaConfigClass)
        private val parserGetSupportedTypes: Method = parserInterfaceClass.getMethod("getSupportedTypes", parseContextClass)
        private val compositeGetAllComponentParsers: Method = compositeParserClass.getMethod("getAllComponentParsers")
        private val decoratorGetWrappedParser: Method = parserDecoratorClass.getMethod("getWrappedParser")

        fun tikaConfig(): Any = tikaConfigConstructor.newInstance(loader)

        fun detect(tikaConfig: Any, bytes: ByteArray, name: String?): String? {
            val metadata = metadataClass.getConstructor().newInstance()
            if (!name.isNullOrBlank()) {
                metadataSetString.invoke(metadata, "resourceName", name)
            }
            val detector = tikaConfigGetDetector.invoke(tikaConfig)
            val detectMethod = detector.javaClass.getMethod("detect", InputStream::class.java, metadataClass)
            val stream = tikaInputStreamGetBytes.invoke(null, bytes, metadata) as java.io.Closeable
            stream.use {
                return detectMethod.invoke(detector, stream, metadata)?.toString()
            }
        }

        fun extract(
            tikaConfig: Any,
            input: PreparedInput,
            module: String,
            options: TikaOptions,
        ): TikaExtract {
            val parser = parserConstructor.newInstance(tikaConfig)
            val metadata = metadataClass.getConstructor().newInstance()
            seedMetadata(metadata, input)
            val context = parseContext(input, options.ocr)
            configureTesseractParsers(parser, context, input, options.ocr)
            val handler = bodyContentHandlerClass
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(options.limits.maxExtractedChars)

            ByteArrayInputStream(input.bytes).use { stream ->
                parseMethod.invoke(parser, stream, handler, metadata, context)
            }

            return TikaExtract(
                text = handler.toString(),
                metadata = metadataMap(metadata),
                name = input.source.originalName,
                mediaType = input.source.originalMediaType,
                module = module,
                source = input.source,
            )
        }

        private fun seedMetadata(metadata: Any, input: PreparedInput) {
            val source = input.source
            if (!source.parsedName.isNullOrBlank()) {
                metadataSetString.invoke(metadata, "resourceName", source.parsedName)
            }
            if (!source.parsedMediaType.isNullOrBlank()) metadataSetString.invoke(metadata, "Content-Type", source.parsedMediaType)
            source.originalName?.let { metadataSetString.invoke(metadata, "trikeshed:source:name", it) }
            source.originalMediaType?.let { metadataSetString.invoke(metadata, "trikeshed:source:mediaType", it) }
            metadataSetString.invoke(metadata, "trikeshed:source:originalBytes", source.originalBytes.toString())
            metadataSetString.invoke(metadata, "trikeshed:source:parsedBytes", source.parsedBytes.toString())
            source.transforms.forEach { metadataAddString.invoke(metadata, "trikeshed:source:transform", it) }
        }

        private fun parseContext(input: PreparedInput, ocr: OcrOptions): Any {
            val context = parseContextClass.getConstructor().newInstance()
            setTesseractConfig(context, input, ocr)
            setPdfConfig(context, ocr)
            return context
        }

        private fun setTesseractConfig(context: Any, input: PreparedInput, ocr: OcrOptions) {
            val configClass = loader.loadClass("org.apache.tika.parser.ocr.TesseractOCRConfig")
            val config = configClass.getConstructor().newInstance()
            invokeRequiredSetter(config, "setLanguage", ocr.language)
            invokeRequiredSetter(config, "setTimeoutSeconds", ocr.timeoutSeconds)
            invokeRequiredSetter(config, "setEnableImagePreprocessing", false)
            invokeRequiredSetter(config, "setSkipOcr", ocr.pdfStrategy == PdfOcrStrategy.NO_OCR)
            contextSet.invoke(context, configClass, config)
        }

        private fun setPdfConfig(context: Any, ocr: OcrOptions) {
            val configClass = loader.loadClass("org.apache.tika.parser.pdf.PDFParserConfig")
            val strategyClass = loader.loadClass("org.apache.tika.parser.pdf.PDFParserConfig\$OCR_STRATEGY")
            val config = configClass.getConstructor().newInstance()
            val strategy = strategyClass.enumConstants.first { (it as Enum<*>).name == ocr.pdfStrategy.name }
            invokeRequiredSetter(config, "setOcrStrategy", strategy)
            invokeRequiredSetter(config, "setExtractInlineImages", false)
            contextSet.invoke(context, configClass, config)
        }

        private fun configureTesseractParsers(parser: Any, context: Any, input: PreparedInput, ocr: OcrOptions) {
            if (!input.needsOcr) return
            val parsers = tesseractParsers(parser)
            check(parsers.isNotEmpty()) {
                "managed Tika parser tree has no TesseractOCRParser for OCR input '${input.source.originalName ?: "<bytes>"}'"
            }
            parsers.forEach { configureTesseractParser(it, context, input, ocr) }
        }

        private fun tesseractParsers(parser: Any): List<Any> {
            val out = ArrayList<Any>()
            val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            collectTesseractParsers(parser, seen, out)
            return out
        }

        private fun collectTesseractParsers(parser: Any, seen: MutableSet<Any>, out: MutableList<Any>) {
            if (!seen.add(parser)) return
            if (tesseractParserClass.isInstance(parser)) out += parser
            if (compositeParserClass.isInstance(parser)) {
                val components = compositeGetAllComponentParsers.invoke(parser) as Iterable<*>
                components.filterNotNull().forEach { collectTesseractParsers(it, seen, out) }
            }
            if (parserDecoratorClass.isInstance(parser)) {
                decoratorGetWrappedParser.invoke(parser)?.let { collectTesseractParsers(it, seen, out) }
            }
        }

        private fun configureTesseractParser(parser: Any, context: Any, input: PreparedInput, ocr: OcrOptions) {
            invokeRequiredSetter(parser, "setLanguage", ocr.language)
            invokeRequiredSetter(parser, "setTimeout", ocr.timeoutSeconds)
            invokeRequiredSetter(parser, "setEnableImagePreprocessing", false)
            invokeRequiredSetter(parser, "setSkipOCR", ocr.pdfStrategy == PdfOcrStrategy.NO_OCR)
            input.tesseractExecutable?.let { path ->
                invokeRequiredSetter(parser, "setTesseractPath", File(path).parent ?: path.substringBeforeLast('/', path))
            }
            val available = invokeRequiredBoolean(parser, "hasTesseract")
            check(available || !ocr.requireTesseract) {
                "configured TesseractOCRParser did not find tesseract command " +
                    "'${input.tesseractExecutable ?: ocr.tesseractCommand ?: "tesseract"}'"
            }
            invokeRequired(parser, "initialize", Map::class.java).invoke(parser, emptyMap<String, Any>())
            invokeRequired(parser, "checkInitialization", initializableProblemHandlerClass)
                .invoke(parser, initializableThrowHandler)
            val supportedTypes = parserGetSupportedTypes.invoke(parser, context) as Set<*>
            check(supportedTypes.isNotEmpty() || !input.needsOcr || ocr.pdfStrategy == PdfOcrStrategy.NO_OCR || !ocr.requireTesseract) {
                "configured TesseractOCRParser has no supported OCR media types for '${input.source.originalName ?: "<bytes>"}'"
            }
        }

        private fun metadataMap(metadata: Any): Map<String, List<String>> {
            val names = metadataNames.invoke(metadata) as Array<*>
            val out = LinkedHashMap<String, List<String>>()
            for (raw in names) {
                val key = raw?.toString() ?: continue
                val values = metadataValues.invoke(metadata, key) as Array<*>
                out[key] = values.mapNotNull { it?.toString() }
            }
            return out
        }

        private fun invokeRequiredSetter(target: Any, name: String, value: Any) {
            val method = target.javaClass.methods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == 1 &&
                    accepts(method.parameterTypes[0], value)
            } ?: throw NoSuchMethodException("${target.javaClass.name}.$name(${value.javaClass.name})")
            method.invoke(target, value)
        }

        private fun invokeRequiredBoolean(target: Any, name: String): Boolean =
            invokeRequired(target, name).invoke(target) as Boolean

        private fun invokeRequired(target: Any, name: String, vararg parameterTypes: Class<*>): Method =
            target.javaClass.getMethod(name, *parameterTypes)

        private fun accepts(type: Class<*>, value: Any): Boolean = when {
            type.isPrimitive && type == java.lang.Boolean.TYPE -> value is Boolean
            type.isPrimitive && type == java.lang.Integer.TYPE -> value is Int
            type.isPrimitive && type == java.lang.Long.TYPE -> value is Long
            type.isPrimitive && type == java.lang.Double.TYPE -> value is Double
            type.isPrimitive -> false
            else -> type.isInstance(value)
        }
    }

    internal data class PreparedInput(
        val bytes: ByteArray,
        val tesseractExecutable: String?,
        val needsOcr: Boolean,
        val source: TikaSource,
    )

    private fun rejectUnsupported(options: TikaOptions) {
        check(options.compatibility == TikaCompatibility.MANAGED_3_2_3) {
            "compatibility mode ${options.compatibility} is not executable here. " +
                "The tika4all launcher embeds Maven generation and shell-wrapped java -jar behavior; " +
                "managed extraction supports only ${TikaCompatibility.MANAGED_3_2_3}."
        }
        check(!options.ocr.preprocessPdfImages) {
            "preprocessPdfImages is unsupported: Tika $TIKA_VERSION exposes PDF OCR strategy, " +
                "but no FFmpeg hook for PDF-rendered OCR images"
        }
    }

    private fun validateManagedModule(module: String): TikaModuleEvidence {
        val manifest = GuestModules.manifest(module)
        check(manifest.entries.isNotEmpty()) {
            "guest module '$module' has no MANIFEST.tsv entries; managed Tika extraction requires a verified module"
        }
        check(manifest.declared == MANAGED_TIKA_COORDINATES) {
            "guest module '$module' declaration mismatch: ${manifest.declared} != $MANAGED_TIKA_COORDINATES"
        }
        val present = manifest.entries.mapTo(HashSet()) { it.file }
        val missing = MANAGED_TIKA_REQUIRED_ARTIFACTS.filter { it !in present }
        check(missing.isEmpty()) {
            "guest module '$module' is missing required Tika artifacts: ${missing.joinToString()}"
        }
        return TikaModuleEvidence(
            module = module,
            declared = manifest.declared,
            requiredArtifacts = MANAGED_TIKA_REQUIRED_ARTIFACTS,
            artifactCount = manifest.entries.size,
            artifactBytes = manifest.totalBytes,
        )
    }

    private fun prepareInput(
        bytes: ByteArray,
        name: String?,
        mediaType: String?,
        detectedMediaType: String?,
        options: TikaOptions,
        module: TikaModuleEvidence,
    ): PreparedInput {
        val effectiveMediaType = effectiveMediaType(detectedMediaType, mediaType)
        val raster = isRasterImage(name, effectiveMediaType)
        val pdf = isPdf(name, effectiveMediaType)
        val needsOcr = (raster || pdf) && options.ocr.pdfStrategy != PdfOcrStrategy.NO_OCR
        val tesseract = resolveTesseract(options.ocr, needsOcr = needsOcr)
        val prepared = if (raster && options.ocr.preprocessImages) ffmpegPreprocess(bytes, name, options.ocr) else null
        val parsedBytes = prepared?.bytes ?: bytes
        val transforms = prepared?.transforms ?: emptyList()
        val parsedMediaType = if (prepared != null) "image/png" else effectiveMediaType
        val parsedName = if (prepared != null) name?.replaceAfterLast('.', "png") ?: "tika-ocr-input.png" else name
        return PreparedInput(
            bytes = parsedBytes,
            tesseractExecutable = tesseract,
            needsOcr = needsOcr,
            source = TikaSource(
                originalBytes = bytes.size,
                parsedBytes = parsedBytes.size,
                originalName = name,
                parsedName = parsedName,
                originalMediaType = mediaType,
                parsedMediaType = parsedMediaType,
                transforms = transforms,
                module = module,
            ),
        )
    }

    private fun effectiveMediaType(detectedMediaType: String?, hintedMediaType: String?): String? {
        val detected = detectedMediaType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        return detected ?: hintedMediaType
    }

    private data class PreprocessedBytes(
        val bytes: ByteArray,
        val transforms: List<String>,
    )

    private fun ffmpegPreprocess(bytes: ByteArray, name: String?, ocr: OcrOptions): PreprocessedBytes {
        val ffmpeg = resolveExecutable(ocr.ffmpegCommand)
            ?: error("ffmpeg command '${ocr.ffmpegCommand}' is required for image preprocessing of '${name ?: "<bytes>"}'")
        val result = runBlocking {
            withTimeout(ocr.timeoutSeconds.toLong() * 1000L) {
                JvmProcessOperations().exec(
                    command = ffmpeg,
                    args = listOf(
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-i",
                        "pipe:0",
                        "-vf",
                        ocr.ffmpegFilter,
                        "-f",
                        "image2pipe",
                        "-vcodec",
                        "png",
                        "pipe:1",
                    ),
                    stdin = bytes,
                )
            }
        }
        check(result.exitCode == 0) {
            "ffmpeg preprocessing failed for '${name ?: "<bytes>"}' with exit ${result.exitCode}: " +
                result.stderr.decodeToString().takeLast(600)
        }
        check(result.stdout.isNotEmpty()) {
            "ffmpeg preprocessing produced no output for '${name ?: "<bytes>"}'"
        }
        return PreprocessedBytes(result.stdout, listOf("ffmpeg:${ocr.ffmpegFilter}"))
    }

    private fun resolveTesseract(ocr: OcrOptions, needsOcr: Boolean): String? {
        if (!needsOcr) return null
        val expectedName = tesseractProgramName()
        val command = ocr.tesseractCommand ?: expectedName
        check(File(command).name == expectedName) {
            "tesseractCommand must name '$expectedName': Tika $TIKA_VERSION TesseractOCRParser.setTesseractPath " +
                "accepts only a directory and appends the fixed executable name"
        }
        val resolved = resolveExecutable(command)
        check(resolved != null || !ocr.requireTesseract) {
            "tesseract command '$command' is required for OCR but was not found"
        }
        return resolved
    }

    private fun tesseractProgramName(): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "tesseract.exe" else "tesseract"

    private fun isRasterImage(name: String?, mediaType: String?): Boolean {
        val type = mediaType?.lowercase()
        if (type != null && type.startsWith("image/") && type != "image/svg+xml") return true
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return ext in setOf("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "heic")
    }

    private fun isPdf(name: String?, mediaType: String?): Boolean =
        mediaType?.lowercase() == "application/pdf" || name?.lowercase()?.endsWith(".pdf") == true

    private fun resolveExecutable(command: String): String? {
        val candidate = File(command)
        if (command.contains(File.separator) || command.contains('/')) {
            return candidate.takeIf { it.isFile && it.canExecute() }?.absolutePath
        }
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

    private fun <T> withLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun gradleTaskName(module: String): String =
        module.split('-').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}
