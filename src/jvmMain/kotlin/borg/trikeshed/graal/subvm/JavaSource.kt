package borg.trikeshed.graal.subvm

import borg.trikeshed.parse.reifyStrict
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.tools.*

/** JDK source compilation behind the existing OWN-trust JVM guest call boundary. */
object JavaSource {
    const val CLASS = "LcncFunction"
    const val BODY_LINE = 4

    fun available(): Boolean = ToolProvider.getSystemJavaCompiler() != null

    @JvmStatic
    fun invoke(body: String, input: String, name: String): Any? {
        require(body.isNotBlank() && body.encodeToByteArray().size <= 65_536) { "Invalid Java function body size" }
        val source = "import java.util.*;\npublic final class $CLASS {\npublic static Object invoke(Object input) throws Exception {\n$body\n}\n}\n"
        val function = compile(CLASS, source, name, BODY_LINE).getMethod("invoke", Any::class.java)
        return try { function.invoke(null, reifyStrict(input)) }
        catch (failure: InvocationTargetException) { throw (failure.cause ?: failure) }
    }

    /** Explicit roots only: ordinary functions remain JDK-only; native DSLs supply verified jars. */
    internal fun compile(className: String, text: String, name: String, bodyLine: Int,
                         classpath: List<java.io.File> = emptyList(),
                         parent: ClassLoader = ClassLoader.getPlatformClassLoader()): Class<*> {
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Java source requires a JDK compiler" }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val output = StringWriter()
        val bytecode = mutableMapOf<String, ByteArrayOutputStream>()
        val source = object : SimpleJavaFileObject(URI.create("string:///$className.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
        }
        compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8).use { files ->
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath.map { it.toPath() })
            files.setLocationFromPaths(StandardLocation.SOURCE_PATH, emptyList())
            val memory = object : ForwardingJavaFileManager<StandardJavaFileManager>(files) {
                override fun getJavaFileForOutput(location: JavaFileManager.Location, className: String,
                    kind: JavaFileObject.Kind, sibling: FileObject?): JavaFileObject =
                    object : SimpleJavaFileObject(URI.create("memory:///${className.replace('.', '/')}${kind.extension}"), kind) {
                        override fun openOutputStream(): ByteArrayOutputStream = ByteArrayOutputStream().also {
                            bytecode[className] = it
                        }
                    }
            }
            val compiled = compiler.getTask(output, memory, diagnostics, listOf("-proc:none"), null, listOf(source)).call()
            val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
            require(compiled == true && errors.isEmpty()) {
                errors.joinToString("\n") { diagnostic ->
                    "$name.java:${(diagnostic.lineNumber - bodyLine + 1).coerceAtLeast(1)}:${diagnostic.columnNumber}: ${diagnostic.getMessage(Locale.ROOT)}"
                }.ifBlank { "$name.java: compilation failed: $output" }
            }
        }
        // Only in-memory bytes live in this loader; no jar/file handles or shared class cache.
        val loader = object : ClassLoader(parent) {
            override fun findClass(name: String): Class<*> {
                val bytes = bytecode[name]?.toByteArray() ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        return loader.loadClass(className)
    }
}
