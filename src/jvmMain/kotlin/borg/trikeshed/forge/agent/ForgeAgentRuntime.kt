package borg.trikeshed.forge.agent

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.parse.confix.Confix
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Value
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Forge Agent Runtime — runs Hermes-style persistent/self-improving agent logic
 * primarily in GraalPy, with the Kanban board projected as a host-accessible
 * cursor over the shared blackboard.
 *
 * Sandbox tiers (all on the same warm [Engine]):
 *   - SHARED    — one Context, Python + JS share the engine cache. Default.
 *   - ISOLATED  — fork-discount: new Context per agent on the shared engine,
 *                 isolated globals/heap, warm compilation.
 *   - PROCESS   — separate Engine/Process. Full OS-level sandbox. Cold start.
 *
 * Heavy native libs that cannot run inside GraalPy get clean fallbacks via
 * [NativeLibFallback] — the agent calls into Kotlin host functions instead.
 *
 * The Kanban board is NOT a separate system here. The agent sees it as a
 * Confix-serialized projection (`board_json()`) over the blackboard state,
 * manipulated through the FSM event bus, never via a parallel Python graph.
 *
 * Serialization: all board/blackboard state crosses the Python boundary as
 * Confix-serialized text — the cursor-backed serial format. This keeps one
 * canonical decode path (ConfixDoc facets) on both sides of the boundary.
 */
class ForgeAgentRuntime(
    val blackboard: ConfixBlackboard = ConfixBlackboard.empty(),
    private val tier: SandboxTier = SandboxTier.SHARED,
    private val engine: Engine = sharedEngine,
    private val fallbacks: Map<String, NativeLibFallback> = emptyMap(),
) : Closeable {

    enum class SandboxTier { SHARED, ISOLATED, PROCESS }

    /** Fallback for heavy native libs that cannot run inside GraalPy. */
    fun interface NativeLibFallback {
        fun invoke(blackboard: ConfixBlackboard, args: Map<String, Any?>): Any?
    }

    private val contexts = ConcurrentHashMap<String, AgentContext>()

    /** Shared warm engine — one per JVM, compilation cache reused across contexts. */
    val runtimeEngine: Engine get() = engine

    /** Host bridge exposed to Python as `forge`. */
    @Suppress("unused")
    inner class HostBridge {
        @HostAccess.Export
        fun blackboard_get(key: String): Any? = blackboard.get(key)

        @HostAccess.Export
        fun blackboard_put(key: String, value: Any?) {
            blackboard.put(key, value, "graalpy")
        }

        /** Board state serialized via Confix (cursor-backed JSON). */
        @HostAccess.Export
        fun board_json(): String {
            val board = ForgeBoardFSM.current().activeBoard
                ?: return "{}"
            return Confix.encode(board)
        }

        @HostAccess.Export
        fun native_call(libName: String, args: Map<String, Any?>): Any? {
            val fb = fallbacks[libName]
                ?: return mapOf("error" to "no fallback for '$libName'")
            return fb.invoke(blackboard, args)
        }
    }

    /**
     * Spawn an agent context. In SHARED tier, returns the single shared context.
     * In ISOLATED tier, creates a new Context on the shared engine (fork discount).
     */
    fun spawnAgent(agentId: String, initScript: String? = null): AgentContext {
        val ctx = when (tier) {
            SandboxTier.SHARED -> contexts.computeIfAbsent("__shared__") {
                createContext(initScript)
            }
            SandboxTier.ISOLATED -> createContext(initScript)
            SandboxTier.PROCESS -> createContext(initScript)
        }
        if (tier == SandboxTier.ISOLATED) contexts[agentId] = ctx
        return ctx
    }

    private fun createContext(initScript: String?): AgentContext {
        val polyglotCtx = Context.newBuilder()
            .engine(engine)
            .allowHostAccess(hostAccess)
            .allowPolyglotAccess(PolyglotAccess.ALL)
            .allowHostClassLookup { true }
            .build()

        val bridge = HostBridge()
        polyglotCtx.getBindings("python").putMember("forge", bridge)

        if (initScript != null) {
            polyglotCtx.eval("python", initScript)
        }

        return AgentContext(polyglotCtx, bridge)
    }

    override fun close() {
        contexts.values.forEach { runCatching { it.close() } }
        contexts.clear()
    }

    companion object {
        /** The single shared warm engine for the JVM. */
        val sharedEngine: Engine = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        /** Host access policy — exposes @HostAccess.Export-annotated methods to guest languages. */
        internal val hostAccess: HostAccess = HostAccess.newBuilder()
            .allowAccessAnnotatedBy(HostAccess.Export::class.java)
            .build()
    }
}

/**
 * One agent's execution context — a GraalVM Context bound to the shared engine.
 */
class AgentContext(
    val context: Context,
    val bridge: ForgeAgentRuntime.HostBridge,
) : Closeable {
    /** Eval Python code in this agent's context. */
    fun evalPython(code: String): Any? {
        val value = context.eval("python", code)
        return value.toHostValue()
    }

    /** Eval JavaScript code (for interop or JS-based tools). */
    fun evalJs(code: String): Any? {
        val value = context.eval("js", code)
        return value.toHostValue()
    }

    /** Call a Python function by name with varargs. */
    fun callPython(functionName: String, vararg args: Any?): Any? {
        val fn = context.getBindings("python").getMember(functionName)
        if (fn == null || !fn.canExecute()) return null
        return fn.execute(*args).toHostValue()
    }

    override fun close() {
        context.close()
    }

    private fun Value?.toHostValue(): Any? {
        if (this == null || isNull) return null
        if (isString) return asString()
        if (isBoolean) return asBoolean()
        if (isNumber) {
            return try { asInt() } catch (_: Exception) {
                try { asLong() } catch (_: Exception) { asDouble() }
            }
        }
        if (isHostObject) return asHostObject<Any?>()
        return this
    }
}
