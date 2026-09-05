package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.io.IOAccess

/**
 * Phase-1 proof that a pure canvas runner can be the daemon implementation too.
 *
 * This does not carry a second spelling of `pick`: [loadPickMethod] extracts the
 * method authored in `web/patch.js`'s RUNNERS table. `pick` has no await, so
 * [pickRunner] removes only its `async` declaration before evaluating that same
 * method body synchronously. Every invocation gets a fresh, closed GraalJS
 * context with no host, class, IO, native, or thread capability.
 */
object CanvasJsPureNodes {
    private const val PANELS_RESOURCE = "web/patch.js"
    private const val PICK_MARKER = "\"pick\": {"

    /** Public only at the JVM module boundary so the jvmTest sandbox gate can inspect it. */
    internal fun newSandbox(): Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .allowIO(IOAccess.NONE)
        .allowCreateThread(false)
        .allowNativeAccess(false)
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    suspend fun registry(): Map<String, LcncNodeRunner> {
        val method = withContext(Dispatchers.IO) { loadPickMethod() }
        return mapOf("pick" to pickRunner(method))
    }

    internal fun pickRunner(method: String = loadPickMethod()): LcncNodeRunner =
        LcncNodeRunner { node, inputs ->
            // Context construction and eval are synchronous. Keep both off the
            // CCEK reactor rather than blocking the caller's coroutine thread.
            withContext(Dispatchers.IO) {
                newSandbox().use { context ->
                    val bindings = context.getBindings("js")
                    bindings.putMember("__nodeJson", JsonSupport.stringify(mapOf("params" to node.params)))
                    bindings.putMember("__inputsJson", JsonSupport.stringify(inputs))
                    val synchronousMethod = method.replaceFirst("async run", "run")
                    require(synchronousMethod != method) { "canvas pick runner is no longer async run(n,i)" }
                    val resultJson = context.eval(
                        "js",
                        """
                        (() => {
                          const p = (n, k) => n.params[k] ?? "";
                          const runner = { $synchronousMethod };
                          return JSON.stringify(runner.run(JSON.parse(__nodeJson), JSON.parse(__inputsJson)));
                        })()
                        """.trimIndent(),
                    ).asString()
                    @Suppress("UNCHECKED_CAST")
                    (JsonSupport.parse(resultJson) as? Map<String, Any?>)
                        ?: error("canvas pick runner returned a non-object")
                }
            }
        }

    internal fun loadPickMethod(): String {
        val html = CanvasJsPureNodes::class.java.classLoader
            .getResourceAsStream(PANELS_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("missing classpath resource $PANELS_RESOURCE")
        val entryStart = html.indexOf(PICK_MARKER)
        require(entryStart >= 0) { "canvas RUNNERS has no pick entry" }
        val methodStart = entryStart + PICK_MARKER.length
        val entryEnd = html.indexOf("\n  },", methodStart)
        require(entryEnd >= 0) { "canvas pick entry is not terminated" }
        return html.substring(methodStart, entryEnd).trim()
    }
}
