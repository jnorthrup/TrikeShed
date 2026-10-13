package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.*
import org.graalvm.polyglot.Context
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Optional source compiler behind the existing OWN-trust JVM invocation boundary. */
object ClojureSource {
    const val MODULE = "clojure"

    fun loader(): URLClassLoader {
        check(GuestModules.isInstalled(MODULE)) {
            "Clojure source requires ./gradlew -p utils/subvm installClojure"
        }
        for (module in GuestModules.chain(MODULE)) {
            check(GuestModules.manifestFile(module) != null) { "Clojure module '$module' requires a manifest" }
            val verification = GuestModules.verify(module)
            check(verification.ok) { "Clojure module '$module' failed verification: ${verification.problems.joinToString()}" }
        }
        // Never reuse the mounted loader: Clojure namespaces and Vars are mutable.
        return URLClassLoader(GuestModules.composedClasspath(MODULE).map { it.toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader())
    }

    fun available(): Boolean = if (!GuestModules.isInstalled(MODULE)) false else loader().use {
        GuestModules.canResolve(it, "clojure.java.api.Clojure") && it.getResource("clojure/data/json.clj") != null
    }

    @JvmStatic
    fun invoke(body: String, input: String, name: String): Any? {
        require(body.isNotBlank() && body.encodeToByteArray().size <= 65_536) { "Invalid Clojure function body size" }
        val context = Context.getCurrent()
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        return loader().use { loader ->
            var executors = emptyList<ExecutorService>()
            try {
                thread.contextClassLoader = loader
                // Bootstrap through the public API before resolving Compiler (RT owns its initialization).
                val api = Class.forName("clojure.java.api.Clojure", true, loader)
                val variable = api.getMethod("var", Any::class.java, Any::class.java)
                val read = api.getMethod("read", String::class.java)
                val call = loader.loadClass("clojure.lang.IFn").getMethod("invoke", Any::class.java)
                call.invoke(variable.invoke(null, "clojure.core", "require"), read.invoke(null, "clojure.data.json"))
                val agent = loader.loadClass("clojure.lang.Agent")
                executors = listOf("pooledExecutor", "soloExecutor").map {
                    agent.getField(it).get(null) as ExecutorService
                }
                val data = call.invoke(variable.invoke(null, "clojure.data.json", "read-str"), input)
                val compile = loader.loadClass("clojure.lang.Compiler").getMethod("load",
                    Reader::class.java, String::class.java, String::class.java)
                val function = StringReader("(fn [input] $body\n)").use {
                    compile.invoke(null, it, "$name.clj", "$name.clj")
                }
                val result = call.invoke(function, data)
                // Freeze through the existing bounded ABI before releasing the compiler loader.
                // No keyword stringification, JSON writer fallback, or executable host result.
                Teleported.of(context.asValue(result)).languageValue()
            } catch (failure: InvocationTargetException) {
                val cause = failure.cause ?: failure
                throw IllegalArgumentException("$name.clj: ${cause.message}", cause)
            } finally {
                try {
                    // Futures/agents use these per-loader executors. Closing a jar is not drain.
                    executors.forEach { it.shutdown() }
                    var interrupted = false
                    try {
                        for (executor in executors) while (!executor.isTerminated) {
                            try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
                            catch (_: InterruptedException) { interrupted = true }
                        }
                    } finally { if (interrupted) thread.interrupt() }
                } finally { thread.contextClassLoader = previous }
            }
        }
    }
}
