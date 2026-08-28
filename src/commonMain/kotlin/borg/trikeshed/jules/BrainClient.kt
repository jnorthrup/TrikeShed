package borg.trikeshed.jules

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import keymux.EnvVarSource
import keymux.FixedKeySource
import keymux.KeyMux
import modelmux.ModelEntry
import modelmux.ModelMux
import modelmux.acp.AcpMessage
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/** Target adapter for the optional durable brain-error audit trail. */
fun interface BrainErrorSink {
    fun append(entry: String)
}

object DiscardingBrainErrorSink : BrainErrorSink {
    override fun append(entry: String) = Unit
}

/**
 * BrainClient — the answer brain.
 *
 * OpenAI-compatible chat completions over common reactor HTX with multi-provider
 * failover. Callers use this to answer Jules sessions with project conventions as
 * the system prompt.
 *
 * The routing/key design is authoritative here: provider selection goes through
 * ModelMux and credential resolution goes through KeyMux. Environment variables
 * are used only to discover which bootstrap bindings exist.
 *
 * When an external [keyMux] and/or [modelMux] are provided, BrainClient uses
 * those shared instances — the MuxReactor tracks quota, leases, and provider
 * health across the entire daemon. When null, BrainClient creates its own
 * (standalone mode for tests and embedded use).
 */
class BrainClient(
    /** If non-null, overrides auto-discovery and uses a single endpoint. */
    apiKey: String? = null,
    base: String = "https://integrate.api.nvidia.com/v1",
    model: String = "poolside/laguna-xs-2.1",
    private val errorSink: BrainErrorSink = DiscardingBrainErrorSink,
    /**
     * External KeyMux — shared with the daemon. When provided, provider discovery
     * uses this key pool and the MuxReactor tracks all key accesses. Null = create
     * internal KeyMux from env (standalone mode).
     */
    private val keyMux: KeyMux? = null,
    /**
     * External ModelMux — shared with the daemon. When provided, chat calls route
     * through ModelMux.chat() which uses HtxKey from coroutine context and records
     * receipts via MuxReactor. Null = create internal ModelMux (standalone mode).
     */
    private val modelMux: ModelMux? = null,
) {
    /** Outer timeout for the entire multi-provider failover loop. */
    companion object {
        /** Outer timeout: if every provider fails to respond within this window, abort the whole call. */
        const val OUTER_TIMEOUT_MS = 60_000L
    }

    /** One OpenAI-compatible endpoint + the env var that KeyMux resolves. */
    data class EndpointSpec(
        val name: String,
        val envVar: String,
        val base: String,
        val model: String,
    )

    private val endpoints: List<EndpointSpec> = if (apiKey != null) {
        listOf(EndpointSpec("override", "BRAIN_OVERRIDE", base, model))
    } else {
        discoverEndpoints()
    }
    private val endpointByModel: Map<String, EndpointSpec> = endpoints.associateBy { it.model }

    /** Internal KeyMux — created when no external one is provided. */
    private val internalKeyMux: KeyMux = this.keyMux ?: buildKeyMux(apiKey, model)

    /** Internal ModelMux — created when no external one is provided. */
    private val internalModelMux: ModelMux = this.modelMux ?: ModelMux(internalKeyMux) {
        endpoints.forEach { endpoint ->
            model(id = endpoint.model, caps = setOf("chat", "conflict-resolve"), baseUrl = endpoint.base)
        }
    }

    /** Last model id that answered a chat. */
    private var lastGoodModelId: String? = endpoints.firstOrNull()?.model

    /** True if at least one provider endpoint was discovered. */
    fun hasEndpoints(): Boolean = endpoints.isNotEmpty()

    /** Read-only view of the discovered roster — name/base/model, no key material. */
    fun endpointSummaries(): List<EndpointSpec> = endpoints

    /** The model id that most recently answered a chat, or null before the first success. */
    fun lastModel(): String? = lastGoodModelId

    /**
     * Quota-legion standings from the mux (usable-first). Call under the mux
     * reactor context — without it the roster is unknowable and this is empty.
     */
    suspend fun quotaStandings(nowMs: Long): List<modelmux.QuotaStanding> =
        internalModelMux.quotaStandings(nowMs)

    /** The static provider table (ungated) — discoverEndpoints() is this, filtered by key presence. */
    private fun fullRoster(): List<EndpointSpec> {
        val out = mutableListOf<EndpointSpec>()
        val seen = mutableSetOf<String>()
        fun add(name: String, envVar: String, base: String, model: String) {
            if (seen.add("$name:$base:$model")) out.add(EndpointSpec(name, envVar, base.trim(), model.trim()))
        }
        rosterInto(::add)
        return out
    }

    /**
     * Full provider-roster status regardless of discovery: every table entry with a
     * key-PRESENCE flag only — key VALUES never leave this class. The patch-panel
     * keymux surface reads this (quota VOLUME visibility without secret exposure).
     */
    fun rosterStatus(): List<Map<String, Any>> {
        val discovered = endpoints.mapTo(mutableSetOf()) { it.name }
        return fullRoster().map { spec ->
            mapOf(
                "name" to spec.name,
                "envVar" to spec.envVar,
                "base" to spec.base,
                "model" to spec.model,
                "keyPresent" to !SystemOperations.default.getenv(spec.envVar).isNullOrBlank(),
                "discovered" to (spec.name in discovered),
            )
        }
    }

    /**
     * Non-streaming chat completion with multi-provider failover.
     *
     * Outer timeout ([OUTER_TIMEOUT_MS]) bounds the entire failover loop —
     * every modelMux.chat() call across all providers must complete within
     * this window or the call aborts.
     *
     * When an external modelMux was provided, each provider attempt routes
     * through ModelMux.chat() which uses HtxKey from coroutine context and
     * records receipts via MuxReactor — quota, lease, and health tracking
     * are airtight.
     *
     * [contextId] threads the conversation identity end to end: it is stamped
     * on every [borg.trikeshed.modelmux.ModelResponseReceipt] as the
     * `assessmentId` seam's sibling (plan step 5) so a receipt points back to
     * the rolling frame chain the call belongs to — cache affinity, restage
     * deltas, and the commander view all key on it. Null = stateless call
     * (the historic behaviour).
     *
     * If every provider fails, throws with the last error message.
     */
    suspend fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 256,
        temperature: Double = 0.2,
        contextId: String? = null,
    ): String {
        if (endpoints.isEmpty()) error("Brain: no provider endpoints discovered")

        return withTimeout(OUTER_TIMEOUT_MS) {
            chatInner(messages, maxTokens, temperature, contextId)
        }
    }

    /** Inner loop: called inside the outer timeout. */
    private suspend fun chatInner(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        contextId: String?,
    ): String {
        var lastError = "all providers exhausted"
        val routed = internalModelMux.route("conflict-resolve").a
        for (modelId in orderedModelIds(routed)) {
            val endpoint = endpointByModel[modelId] ?: continue

            // Route through ModelMux.chat() — uses HtxKey from coroutine context,
            // records receipts via MuxReactor, respects quota/lease tracking.
            val acpMessages = messages.size j { i: Int -> messages[i].first j messages[i].second }
            val result = runCatching {
                internalModelMux.chat(
                    modelId = modelId,
                    messages = acpMessages,
                    assessmentId = contextId,
                    maxTokens = maxTokens,
                    temperature = temperature,
                ).getOrThrow() // chat() is already Result-shaped; unwrap so fold sees AcpResponse, not Result<Result<…>>
            }
            result.fold(
                onSuccess = { response ->
                    lastGoodModelId = modelId
                    return response.a  // AcpResponse.a = full_text content
                },
                onFailure = { t ->
                    lastError = "Brain ${endpoint.name} chat failed: ${t.message}"
                    logError(endpoint.name, -1, t.message.orEmpty().take(500))
                },
            )
        }
        error(lastError)
    }

    private fun orderedModelIds(routed: Series<ModelEntry>): List<String> {
        val modelIds = routed α { it.a }
        val preferred = lastGoodModelId ?: return modelIds.view.toList()
        val start = modelIds.view.indexOf(preferred)
        if (start < 0) return modelIds.view.toList()

        val series = modelIds.size j { offset: Int -> modelIds[(start + offset) % modelIds.size] }
        return series.view.toList()
    }

    private fun buildKeyMux(overrideKey: String?, overrideModel: String): KeyMux = KeyMux {
        if (overrideKey != null) {
            bind("llm.$overrideModel.key", FixedKeySource(overrideKey, name = "brain-override"))
        } else {
            endpoints.forEach { endpoint ->
                bind("llm.${endpoint.model}.key", EnvVarSource(endpoint.envVar))
            }
        }
    }

    private fun logError(provider: String, statusCode: Int, bodySnippet: String) {
        val entry = buildString {
            append("{\"t\":")
            append(Clock.System.now().toEpochMilliseconds())
            append(",\"provider\":")
            append(jsonStr(provider))
            append(",\"status\":")
            append(statusCode)
            append(",\"body\":")
            append(jsonStr(bodySnippet))
            append('}')
        }
        errorSink.append(entry)
    }

    /** Discover all configured OpenAI-compatible endpoints. */
    private fun discoverEndpoints(): List<EndpointSpec> {
        val out = mutableListOf<EndpointSpec>()
        val seen = mutableSetOf<String>()

        fun add(name: String, envVar: String, base: String, model: String) {
            val key = SystemOperations.default.getenv(envVar)
            val id = "$name:$base:$model"
            if (!key.isNullOrBlank() && seen.add(id)) {
                out.add(EndpointSpec(name, envVar, base.trim(), model.trim()))
            }
        }
        rosterInto(::add)
        return out
    }

    /** ONE provider table, two consumers: discovery (key-gated) and status (ungated). */
    private fun rosterInto(add: (String, String, String, String) -> Unit) {

        val nvidia = "https://integrate.api.nvidia.com/v1"
        add("nv-deepseek-v4-pro", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-pro")
        add("nv-nemotron-super-120b", "NVIDIA_API_KEY", nvidia, "nvidia/nemotron-3-super-120b-a12b")
        add("nv-mistral-large-2", "NVIDIA_API_KEY", nvidia, "mistralai/mistral-large-2-instruct")
        add("nv-deepseek-v4-flash", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-flash")
        add("nv-nemotron-super-49b", "NVIDIA_API_KEY", nvidia, "nvidia/llama-3.3-nemotron-super-49b-v1.5")
        add("nv-glm-52", "NVIDIA_API_KEY", nvidia, "z-ai/glm-5.2")
        add("nv-kimi-k26", "NVIDIA_API_KEY", nvidia, "moonshotai/kimi-k2.6")
        add("nv-gpt-oss-120b", "NVIDIA_API_KEY", nvidia, "openai/gpt-oss-120b")
        add("nv-inkling", "NVIDIA_API_KEY", nvidia, "thinkingmachines/inkling")
        add("nv-minimax-m3", "NVIDIA_API_KEY", nvidia, "minimaxai/minimax-m3")
        add("nv-nemotron-ultra-253b", "NVIDIA_API_KEY", nvidia, "nvidia/llama-3.1-nemotron-ultra-253b-v1")
        add("nv-codestral-22b", "NVIDIA_API_KEY", nvidia, "mistralai/codestral-22b-instruct-v0.1")
        add("nv-nemotron-ultra-550b", "NVIDIA_API_KEY", nvidia, "nvidia/nemotron-3-ultra-550b-a55b")
        add("nv-laguna", "NVIDIA_API_KEY", nvidia, "poolside/laguna-xs-2.1")
        add("openrouter-glm52", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "z-ai/glm-5.2")
        add("openrouter-nemotron", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "nvidia/nemotron-3-ultra-550b-a55b:free")
        add("zai", "ZAI_API_KEY", "https://api.z.ai/api/paas/v4", "glm-5.2")
        add("groq", "GROQ_API_KEY", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        add("deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "deepseek-chat")
        add("cerebras", "CEREBRAS_API_KEY", "https://api.cerebras.ai/v1", "llama-3.3-70b")
        val openAiBase = SystemOperations.default.getenv("OPENAI_API_BASE") ?: "https://api.openai.com/v1"
        if ((SystemOperations.default.getenv("OPENAI_API_KEY") ?: "").startsWith("sk-")) {
            add("openai", "OPENAI_API_KEY", openAiBase, "gpt-4o-mini")
        }
        add("perplexity", "PERPLEXITY_API_KEY", "https://api.perplexity.ai", "llama-3.1-sonar-small-128k-online")
        add("xai", "XAI_API_KEY", "https://api.x.ai/v1", "grok-2-latest")
        add("moonshot", "MOONSHOT_API_KEY", "https://api.moonshot.cn/v1", "moonshot-v1-32k")
        add("minimax-m3", "MINIMAX_API_KEY", "https://api.minimax.chat/v1", "MiniMax-M3")
        add("minimax-m25", "MINIMAX_API_KEY", "https://api.minimax.chat/v1", "MiniMax-Text-01")
        // ── Hermes: Nous Portal / local proxy ──
        add("hermes-go", "HERMES_CUSTOM_API_TOKENROUTER_COM_API_KEY", "https://api.tokentrouter.com/v1", "nousresearch/hermes-3-llama-3.1-405b")
        add("hermes-nvidia", "HERMES_CUSTOM_INTEGRATE_API_NVIDIA_COM_API_KEY", nvidia, "nousresearch/hermes-3-llama-3.1-405b")
        add("hermes-synth", "HERMES_CUSTOM_API_SYNTHETIC_NEW_API_KEY", "https://api.synthetic.new/v1", "nousresearch/hermes-3-llama-3.1-405b")
        // ── OpenCode: coding-optimized providers ──
        add("opencode-go", "OPENCODE_GO_API_KEY", "https://api.opencode.ai/v1", "opencode/go-1")
        add("opencode-zen", "OPENCODE_ZEN_API_KEY", "https://api.opencode.ai/v1", "opencode/zen-1")
        add("opencode", "OPENCODE_API_KEY", "https://api.opencode.ai/v1", "opencode/default")
    }

    /** JSON string escaping — handles ", \, \n, \r, \t. Used for log entries. */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }
}
