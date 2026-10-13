package borg.trikeshed.graal.subvm

import borg.trikeshed.parse.reifyStrict
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** Shared compiler for Couch views and OWN-trust LCNC functions. This is a JVM target, not a sandbox. */
class KotlinSource(val loader: ClassLoader = KotlinSource::class.java.classLoader) {
    private val host = BasicJvmScriptingHost()
    private val compilation = ScriptCompilationConfiguration {
        jvm { dependenciesFromClassloader(classLoader = loader, wholeClasspath = true) }
        // Matches the workspace target: common algebra contains inline JVM-25 bytecode.
        compilerOptions.append("-jvm-target", "25")
    }
    private val evaluation = ScriptEvaluationConfiguration { jvm { baseClassLoader(loader) } }

    fun evaluate(source: String, name: String, bodyLine: Int = 1): Any? =
        when (val result = host.eval(source.toScriptSource(name), compilation, evaluation)) {
            is ResultWithDiagnostics.Success -> when (val value = result.value.returnValue) {
                is ResultValue.Value -> value.value
                is ResultValue.Error -> throw IllegalArgumentException("$name: ${value.error.message}", value.error)
                else -> throw IllegalArgumentException("$name: script produced no function")
            }
            is ResultWithDiagnostics.Failure -> throw IllegalArgumentException(
                result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.joinToString("\n") { d ->
                    val position = d.location?.start?.let { ":${(it.line - bodyLine + 1).coerceAtLeast(1)}:${it.col}" }.orEmpty()
                    "$name$position: ${d.message}"
                })
        }

    companion object {
        fun available(): Boolean = runCatching {
            Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", false, KotlinSource::class.java.classLoader)
            true
        }.getOrDefault(false)

        @JvmStatic
        fun invoke(body: String, input: String, name: String): Any? {
            require(body.isNotBlank() && body.encodeToByteArray().size <= 65_536) { "Invalid Kotlin function body size" }
            // Compile a fresh function, not a shared mutable script instance. Data never enters source text.
            val source = "import borg.trikeshed.lib.*\nimport borg.trikeshed.cursor.*\n" +
                "val function = fun(input: Any?): Any? {\n$body\n}\nfunction\n"
            @Suppress("UNCHECKED_CAST")
            val function = KotlinSource().evaluate(source, "$name.kts", 4) as (Any?) -> Any?
            return try { function(reifyStrict(input)) }
            catch (failure: Exception) { throw IllegalArgumentException("$name.kts: ${failure.message}", failure) }
        }
    }
}
