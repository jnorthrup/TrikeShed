package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.vm.*
import org.graalvm.polyglot.Engine

/** The installed engine is probed at the runtime boundary, never inferred from VmFacet names. */
object VmLanguages {
    fun capabilities(host: VmHost): Series<VmLanguageCapability> {
        val probe = if (host is HypervisorVmHost) runCatching {
            Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build().use { it.languages.keys.toSet() }
        } else Result.success(host.languages.map { it.id }.toSet())
        val installed = probe.getOrDefault(emptySet())
        return VmLanguage.all α { language ->
            val moduleProbe = if (language == VmLanguage.GROOVY) runCatching {
                GuestModules.loaderFor("groovy")?.let { loader ->
                    GuestModules.canResolve(loader, "groovy.lang.GroovyShell") &&
                        GuestModules.canResolve(loader, "groovy.json.JsonSlurper")
                } == true
            } else if (language == VmLanguage.CLOJURE) runCatching { ClojureSource.available() }
            else Result.success(false)
            val present = when (language) {
                VmLanguage.KOTLIN_COMMON -> "js" in installed && KotlinSource.available()
                VmLanguage.JAVA -> "js" in installed && JavaSource.available()
                VmLanguage.GROOVY, VmLanguage.CLOJURE -> "js" in installed && moduleProbe.getOrDefault(false)
                else -> language.runtime == VmLanguageRuntime.TRUFFLE && language.id in installed
            }
            val adapter = language in setOf(VmLanguage.KOTLIN_COMMON, VmLanguage.JAVASCRIPT, VmLanguage.PYTHON, VmLanguage.RUBY, VmLanguage.GROOVY, VmLanguage.JAVA, VmLanguage.CLOJURE)
            val supported = adapter && language.facet in host.languages
            val available = present && supported
            val note = when {
                language == VmLanguage.KOTLIN_COMMON && !present -> "Kotlin source requires the workspace's Kotlin scripting compiler and GraalJS."
                language == VmLanguage.KOTLIN_COMMON && supported -> "Kotlin function body over Any? input, with borg.trikeshed.lib and cursor imports. Uses the Couch-view compiler and workspace classpath on the JVM target; common-source portability is not enforced. OWN trust only, no statement or wall-clock enforcement; not a sandbox."
                language == VmLanguage.KOTLIN_COMMON -> "The bound VM host does not expose the JVM interop facet required by Kotlin."
                language == VmLanguage.JAVA && !present -> "Java source requires a JDK compiler and GraalJS in the bound VM host."
                language == VmLanguage.JAVA && supported -> "JDK JavaCompiler compiles an OWN-trust function body with java.util imports and Object input. Each invocation has fresh in-memory classes; only the JDK is on the compilation classpath. No statement or wall-clock enforcement; not a sandbox."
                language == VmLanguage.JAVA -> "The bound VM host does not expose the JVM interop facet required by Java source."
                language == VmLanguage.CLOJURE && moduleProbe.isFailure -> "Clojure module verification failed: ${moduleProbe.exceptionOrNull()?.message}"
                language == VmLanguage.CLOJURE && !present -> "Install the Clojure source compiler: ./gradlew -p utils/subvm installClojure (GraalJS is also required)."
                language == VmLanguage.CLOJURE && supported -> "Clojure compiles an OWN-trust function body with input bound to string-keyed data. Each invocation has fresh namespaces and a verified module classloader. No statement or wall-clock enforcement; not a sandbox."
                language == VmLanguage.CLOJURE -> "The bound VM host does not expose the JVM interop facet required by Clojure."
                language == VmLanguage.GROOVY && moduleProbe.isFailure -> "Groovy module verification failed: ${moduleProbe.exceptionOrNull()?.message}"
                language == VmLanguage.GROOVY && !present -> "Install the Groovy source compiler: ./gradlew -p utils/subvm installGroovy (GraalJS is also required)."
                language == VmLanguage.GROOVY && supported -> "GroovyShell compiles OWN-trust source in an invocation-owned classloader. TimedInterrupt checks loops and instance methods; compilation, static methods and blocking host calls are not bounded. Not a sandbox."
                language == VmLanguage.GROOVY -> "The bound VM host does not expose the JVM interop facet required by Groovy."
                language.runtime == VmLanguageRuntime.JVM -> "No ${language.label} source adapter is registered. The JVM facet is Java interop from GraalJS, not a ${language.label} compiler."
                probe.isFailure -> "Engine probe failed: ${probe.exceptionOrNull()?.message}"
                !present -> "${language.label} engine is not installed in the bound VM host."
                !adapter -> "${language.label} engine is installed, but GuestBounds and the typed source adapter do not support it."
                !supported -> "The bound VM host does not expose ${language.label}."
                language == VmLanguage.RUBY -> "TruffleRuby function body receives native string-keyed Hash/Array data. Native, file, process and Java access remain denied. Method roots are observable; automatic root rebinding is not supported. Return text keys, not Ruby Symbols."
                else -> "Function body receives typed input through VmHandle.call. Return payload data or a Confix route definition; no native Camel object crosses the guest boundary."
            }
            VmLanguageCapability(language, present, available, note)
        }
    }

    fun require(host: VmHost, language: VmLanguage): VmLanguageCapability =
        capabilities(host)[language.ordinal].also { check(it.available) { "${language.label} unavailable: ${it.note}" } }
}
