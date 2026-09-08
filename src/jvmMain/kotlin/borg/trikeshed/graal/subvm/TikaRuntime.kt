package borg.trikeshed.graal.subvm

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Managed Apache Tika invocation through the mounted `utils/subvm/tika` module.
 *
 * This keeps Tika out of the host API surface: callers hand original bytes plus optional
 * name/media-type hints, and every Tika class is resolved from [GuestModules.loaderFor].
 */
object TikaRuntime {

    const val MODULE: String = "tika"

    data class TikaExtract(
        val text: String,
        val metadata: Map<String, List<String>>,
        val name: String?,
        val mediaType: String?,
        val module: String,
    )

    fun extract(
        bytes: ByteArray,
        name: String? = null,
        mediaType: String? = null,
        module: String = MODULE,
    ): TikaExtract {
        check(GuestModules.isInstalled(module)) {
            "guest module '$module' is not installed - install it: " +
                "./gradlew -p utils/subvm install${gradleTaskName(module)}"
        }
        val loader = GuestModules.loaderFor(module)
            ?: throw IllegalStateException("guest module '$module' resolved no classpath to mount")
        return try {
            withLoader(loader) {
                Bridge(loader).extract(bytes, name, mediaType, module)
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

    internal class Bridge(private val loader: ClassLoader) {
        private val parserClass: Class<*> = loader.loadClass("org.apache.tika.parser.AutoDetectParser")
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
        private val metadataNames: Method = metadataClass.getMethod("names")
        private val metadataValues: Method = metadataClass.getMethod("getValues", String::class.java)
        private val contextSet: Method = parseContextClass.methods.first {
            it.name == "set" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Class::class.java
        }

        fun extract(
            bytes: ByteArray,
            name: String?,
            mediaType: String?,
            module: String,
        ): TikaExtract {
            val parser = parserClass.getConstructor().newInstance()
            val metadata = metadataClass.getConstructor().newInstance()
            seedMetadata(metadata, name, mediaType)
            val context = parseContext()
            val handler = bodyContentHandlerClass
                .getConstructor(Int::class.javaPrimitiveType)
                .newInstance(-1)

            ByteArrayInputStream(bytes).use { input ->
                parseMethod.invoke(parser, input, handler, metadata, context)
            }

            return TikaExtract(
                text = handler.toString(),
                metadata = metadataMap(metadata),
                name = name,
                mediaType = mediaType,
                module = module,
            )
        }

        private fun seedMetadata(metadata: Any, name: String?, mediaType: String?) {
            if (!name.isNullOrBlank()) {
                metadataSetString.invoke(metadata, "resourceName", name)
            }
            if (!mediaType.isNullOrBlank()) metadataSetString.invoke(metadata, "Content-Type", mediaType)
        }

        private fun parseContext(): Any {
            val context = parseContextClass.getConstructor().newInstance()
            setTesseractConfig(context)
            setPdfConfig(context)
            return context
        }

        private fun setTesseractConfig(context: Any) {
            val configClass = loader.loadClass("org.apache.tika.parser.ocr.TesseractOCRConfig")
            val config = configClass.getConstructor().newInstance()
            invokeSetter(config, "setLanguage", "eng")
            invokeSetter(config, "setEnableImagePreprocessing", false)
            System.getenv("TESSERACT")?.takeIf { it.isNotBlank() }?.let { path ->
                invokeSetter(config, "setTesseractPath", path.substringBeforeLast('/', path))
            }
            contextSet.invoke(context, configClass, config)
        }

        private fun setPdfConfig(context: Any) {
            val configClass = loader.loadClass("org.apache.tika.parser.pdf.PDFParserConfig")
            val strategyClass = loader.loadClass("org.apache.tika.parser.pdf.PDFParserConfig\$OCR_STRATEGY")
            val config = configClass.getConstructor().newInstance()
            val auto = strategyClass.enumConstants.first { (it as Enum<*>).name == "AUTO" }
            invokeSetter(config, "setOcrStrategy", auto)
            invokeSetter(config, "setExtractInlineImages", false)
            contextSet.invoke(context, configClass, config)
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

        private fun invokeSetter(target: Any, name: String, value: Any) {
            target.javaClass.methods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.size == 1 &&
                    accepts(method.parameterTypes[0], value)
            }?.invoke(target, value)
        }

        private fun accepts(type: Class<*>, value: Any): Boolean = when {
            type.isPrimitive && type == java.lang.Boolean.TYPE -> value is Boolean
            type.isPrimitive && type == java.lang.Integer.TYPE -> value is Int
            type.isPrimitive && type == java.lang.Long.TYPE -> value is Long
            type.isPrimitive && type == java.lang.Double.TYPE -> value is Double
            type.isPrimitive -> false
            else -> type.isInstance(value)
        }
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
