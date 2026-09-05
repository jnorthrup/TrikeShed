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
import keymux.HarnessRegistry
import keymux.KeyMux
import keymux.harness
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
 * Thrown by [BrainClient.chatSeat] when every candidate provider was tried (or
 * skipped on a cached no-key verdict) and none answered. [attempts] is the
 * per-provider failover trail in attempt order — `"<endpoint>/<model>: <error>"`
 * lines — so a refused council seat can put the whole route on the record
 * instead of a silent empty ruling.
 */
class BrainNoRoute(val attempts: List<String>) :
    Exception("no provider answered: " + attempts.joinToString(" -> "))

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
 *
 * DISCOVERY (external keyMux): when an external [keyMux] is provided, the
 * endpoint roster is NOT env-gated — the full [rosterInto] table is admitted,
 * stably ordered so env-present providers come first ([orderEnvFirst]), and
 * per-call resolution through the shared KeyMux/harness lane decides key
 * presence at chat time. Harness-file-only setups (~/.hermes/.env, auth.json
 * via keymux `harness()`) therefore yield a full roster with per-call key
 * resolution instead of an empty roster. Standalone mode (keyMux == null)
 * keeps the historic env-gated [discoverEndpoints] behaviour unchanged.
 */
open class BrainClient(
    /** If non-null, overrides auto-discovery and uses a single endpoint. */
    apiKey: String? = null,
    base: String = "https://integrate.api.nvidia.com/v1",
    model: String = "poolside/laguna-xs-2.1",
    private val errorSink: BrainErrorSink = DiscardingBrainErrorSink,
    /**
     * External KeyMux — shared with the daemon. When provided, provider discovery
     * is UN-GATED: the full roster is admitted (env-present providers ordered
     * first) and this key pool resolves credentials per call — harness-file-only
     * setups get a full roster instead of an empty one — while the MuxReactor
     * tracks all key accesses. Null = create internal KeyMux from env
     * (standalone mode, env-gated discovery unchanged).
     */
    private val keyMux: KeyMux? = null,
    /**
     * External ModelMux — shared with the daemon. When provided, chat calls route
     * through ModelMux.chat() which uses HtxKey from coroutine context and records
     * receipts via MuxReactor. Null = create internal ModelMux (standalone mode).
     */
    private val modelMux: ModelMux? = null,
    /** Quota legion for the internal mux — the daemon passes its ledger-fed legion so every mux meters one pool. */
    private val quotaLegion: modelmux.QuotaLegion? = null,
) {
    /** Outer timeout for the entire multi-provider failover loop. */
    companion object {
        /** Outer timeout: if every provider fails to respond within this window, abort the whole call. */
        const val OUTER_TIMEOUT_MS = 60_000L

        /** The [keymux.HarnessRegistry] provider id whose [keymux.HarnessProvider.envVars] contains [envVar], if any. */
        private fun providerTagFor(envVar: String): String? {
            for (i in 0 until HarnessRegistry.providers.size) {
                val p = HarnessRegistry.providers[i]
                for (j in 0 until p.envVars.size) if (p.envVars[j] == envVar) return p.id
            }
            return null
        }

        /**
         * Stable partition of [specs]: entries whose env var answers [probe]
         * non-blank come first, relative order preserved on both sides. Used by
         * the external-keyMux discovery path — env-present providers lead the
         * roster, but nothing is dropped (per-call key resolution decides).
         */
        internal fun orderEnvFirst(
            specs: List<EndpointSpec>,
            probe: (String) -> String? = { SystemOperations.default.getenv(it) },
        ): List<EndpointSpec> {
            val (present, absent) = specs.partition { !probe(it.envVar).isNullOrBlank() }
            return present + absent
        }

        /**
         * Candidate order for a council seat: rotate [modelIds] to START at
         * [preferred] when it is in the roster; otherwise the incoming order
         * (the caller's lastGood rotation) stands.
         */
        internal fun seatOrder(modelIds: List<String>, preferred: String?): List<String> {
            if (preferred == null) return modelIds
            val start = modelIds.indexOf(preferred)
            if (start < 0) return modelIds
            return List(modelIds.size) { offset -> modelIds[(start + offset) % modelIds.size] }
        }

        /**
         * Classifies a chat failure as a MISSING-KEY verdict — "no key" /
         * "key not found" / 401-unauthorized text. Such a provider is skipped
         * for the rest of the process ([noKeyVerdicts]): a key absent from the
         * env AND every harness store does not appear mid-process.
         */
        internal fun isMissingKeyFailure(message: String): Boolean {
            val m = message.lowercase()
            return "no key" in m || "key not found" in m || "401" in m || "unauthorized" in m
        }

        /**
         * Classifies a chat failure as a RETIRED-MODEL verdict — HTTP 410 Gone, or
         * the provider saying the model id reached end of life / is no longer
         * available (NVIDIA's shape, seen live 2026-09-04 for six roster ids), or
         * HTTP 404 "… Not found …" (NVIDIA's shape for a catalogued id whose
         * function is not served to this account — four roster rows on
         * 2026-09-04, each a 0.15 s dead round trip). Such an endpoint is skipped
         * for the rest of the process ([retiredVerdicts]): a retired or
         * unserved model id does not come back mid-process, and retrying it on
         * every call is a dead round trip before the first live provider gets
         * asked. 5xx and 429 stay with [isRetryableFailure] — transient.
         */
        internal fun isRetiredModelFailure(message: String): Boolean {
            val m = message.lowercase()
            return "http 410" in m || "end of life" in m || "no longer available" in m ||
                ("http 404" in m && "not found" in m)
        }

        /** Rate-limit signature: HTTP 429 or the z.ai/Zhipu limit codes (1302 rpm, 1305 overload). */
        internal fun isRateLimitFailure(message: String): Boolean {
            val m = message.lowercase()
            return "429" in m || "rate limit" in m || "\"1302\"" in m || "\"1305\"" in m || "temporarily overloaded" in m
        }

        /** Transient-provider signature the single-endpoint ladder also retries:
         *  rate limits plus upstream 5xx ("Operation failed" is z.ai's 500 body).
         *  4xx (except 429) stays fatal — retrying a bad request is noise. */
        internal fun isRetryableFailure(message: String): Boolean {
            if (isRateLimitFailure(message)) return true
            val m = message.lowercase()
            return "http 500" in m || "http 502" in m || "http 503" in m || "http 504" in m || "operation failed" in m
        }

        /** Backoff ladder for retrying a rate-limited SINGLE-endpoint lane (the pin has no failover by design). */
        internal val RATE_LIMIT_BACKOFF_MS = longArrayOf(15_000L, 30_000L, 60_000L)

        /**
         * Process-lifetime no-key verdict cache: endpoint NAMES whose chatSeat
         * attempt failed with the missing-key signature. Never cleared — a
         * process gains no credentials it did not start with.
         */
        private val noKeyVerdicts: MutableSet<String> = mutableSetOf()

        /**
         * Process-lifetime retired-model verdict cache: endpoint NAMES whose chat
         * or chatSeat attempt failed with the retired signature
         * ([isRetiredModelFailure]). Never cleared — consulted by BOTH loops, so
         * a dead roster row costs one round trip per process, not one per call.
         */
        private val retiredVerdicts: MutableSet<String> = mutableSetOf()
    }

    /** One OpenAI-compatible endpoint + the env var that KeyMux resolves. */
    data class EndpointSpec(
        val name: String,
        val envVar: String,
        val base: String,
        val model: String,
        /**
         * [HarnessRegistry] provider id sharing [envVar], when one exists — auto-derived,
         * never hand-mapped, so a wrong tag can't silently route a call to the wrong
         * provider's key. Passed to [modelmux.ModelMuxBuilder.model] as `provider` so
         * [modelmux.ModelMux.session] resolves via the pooled `llm.<provider>.key` path —
         * the one [keymux.HarnessSource] actually answers from the operator's real
         * credential stores (hermes profile .env/auth.json, codex, opencode), not just
         * this class's own literal per-model-id bindings ([buildKeyMux], standalone-only).
         */
        val provider: String? = providerTagFor(envVar),
    )

    private val endpoints: List<EndpointSpec> = when {
        apiKey != null -> listOf(EndpointSpec("override", "BRAIN_OVERRIDE", base, model))
        // External keyMux: the full roster is admitted (env-present providers
        // first) — per-call KeyMux/harness resolution decides key presence.
        this.keyMux != null -> orderEnvFirst(fullRoster())
        else -> discoverEndpoints()
    }
    private val endpointByModel: Map<String, EndpointSpec> = endpoints.associateBy { it.model }

    /** Internal KeyMux — created when no external one is provided. */
    private val internalKeyMux: KeyMux = this.keyMux ?: buildKeyMux(apiKey, model)

    /** Internal ModelMux — created when no external one is provided. */
    private val internalModelMux: ModelMux = this.modelMux ?: ModelMux(internalKeyMux) {
        this@BrainClient.quotaLegion?.let { quota(it) }
        endpoints.forEach { endpoint ->
            model(
                id = endpoint.model, caps = setOf("chat", "conflict-resolve"), baseUrl = endpoint.base,
                provider = endpoint.provider,
            )
        }
    }

    /** Last model id that answered a chat. */
    private var lastGoodModelId: String? = endpoints.firstOrNull()?.model

    /** True if at least one provider endpoint was discovered. */
    open fun hasEndpoints(): Boolean = endpoints.isNotEmpty()

    /** Read-only view of the discovered roster — name/base/model, no key material. */
    open fun endpointSummaries(): List<EndpointSpec> = endpoints

    /** The model id that most recently answered a chat, or null before the first success. */
    open fun lastModel(): String? = lastGoodModelId

    /**
     * Quota-legion standings from the mux (usable-first). Call under the mux
     * reactor context — without it the roster is unknowable and this is empty.
     */
    open suspend fun quotaStandings(nowMs: Long): List<modelmux.QuotaStanding> =
        internalModelMux.quotaStandings(nowMs)

    /**
     * The ModelMux this client chats through — provider-tagged cards over the
     * full roster (external-keyMux mode) or the discovered subset (standalone).
     */
    open fun modelMux(): modelmux.ModelMux = internalModelMux

    /**
     * The machine's full provider capability set — the static [rosterInto]
     * table (name/envVar/base/model + auto-derived provider tag), un-gated by
     * key presence. Exposed so the LCNC surface can build a mux over the
     * daemon's shared KeyMux that reflects every provider this machine COULD
     * talk to, independent of the Brain's runtime pin (which narrows the
     * Brain's own dispatch to a single endpoint but must not narrow the
     * panel's view of the machine).
     */
    open fun providerRoster(): List<EndpointSpec> = fullRoster()

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
     *
     * When an external [keyMux] is provided, key presence is resolved through the
     * full KeyMux chain (env → dotenv → harness credential files) instead of raw
     * getenv() — harness-stored keys (hermes .env, codex, opencode) are visible.
     */
    open suspend fun rosterStatus(): List<Map<String, Any>> {
        val discovered = endpoints.mapTo(mutableSetOf()) { it.name }
        return fullRoster().map { spec ->
            val keyPresent = if (this@BrainClient.keyMux != null) {
                // Resolve through the shared KeyMux — covers env + dotenv + harness stores.
                runCatching { this@BrainClient.keyMux!!.get("llm.${spec.provider ?: spec.name}.key") }
                    .getOrNull()?.isNotBlank() == true
            } else {
                // Standalone mode: raw env check only.
                !SystemOperations.default.getenv(spec.envVar).isNullOrBlank()
            }
            mapOf(
                "name" to spec.name,
                "envVar" to spec.envVar,
                "base" to spec.base,
                "model" to spec.model,
                "keyPresent" to keyPresent,
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
    open suspend fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 256,
        temperature: Double = 0.2,
        contextId: String? = null,
    ): String {
        if (endpoints.isEmpty()) error("Brain: no provider endpoints discovered")

        return withTimeout(outerBudgetMs()) {
            chatInner(messages, maxTokens, temperature, contextId)
        }
    }

    /** A single-endpoint lane (the pin / override) retries rate limits with backoff —
     *  its outer budget must cover the ladder; multi-endpoint lanes fail over instead. */
    private fun outerBudgetMs(): Long =
        if (endpoints.size == 1) OUTER_TIMEOUT_MS + RATE_LIMIT_BACKOFF_MS.sum() + RATE_LIMIT_BACKOFF_MS.size * OUTER_TIMEOUT_MS
        else OUTER_TIMEOUT_MS

    /** Retry [attempt] through the backoff ladder while it fails rate-limited — single-endpoint lanes only. */
    private suspend fun <T> withRateLimitRetry(attempt: suspend () -> Result<T>): Result<T> {
        var result = attempt()
        if (endpoints.size != 1) return result
        for (backoffMs in RATE_LIMIT_BACKOFF_MS) {
            val msg = result.exceptionOrNull()?.message ?: return result
            if (!isRetryableFailure(msg)) return result
            kotlinx.coroutines.delay(backoffMs)
            result = attempt()
        }
        return result
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
            if (endpoint.name in retiredVerdicts) continue

            // Route through ModelMux.chat() — uses HtxKey from coroutine context,
            // records receipts via MuxReactor, respects quota/lease tracking.
            val acpMessages = messages.size j { i: Int -> messages[i].first j messages[i].second }
            val result = withRateLimitRetry {
                runCatching {
                    internalModelMux.chat(
                        modelId = modelId,
                        messages = acpMessages,
                        assessmentId = contextId,
                        maxTokens = maxTokens,
                        temperature = temperature,
                    ).getOrThrow() // chat() is already Result-shaped; unwrap so fold sees AcpResponse, not Result<Result<…>>
                }
            }
            result.fold(
                onSuccess = { response ->
                    lastGoodModelId = modelId
                    return response.a  // AcpResponse.a = full_text content
                },
                onFailure = { t ->
                    val message = t.message.orEmpty()
                    lastError = "Brain ${endpoint.name} chat failed: $message"
                    if (isRetiredModelFailure(message)) retiredVerdicts.add(endpoint.name)
                    logError(endpoint.name, -1, message.take(500))
                },
            )
        }
        error(lastError)
    }

    /**
     * Council-seat chat: like [chat] but returns `(content to answeredByModelId)`
     * and carries the full failover trail on failure.
     *
     * Candidate order is [orderedModelIds] (the lastGood rotation) rotated by
     * [seatOrder] to START at [preferredModel] when it is in the roster — a
     * seat's assigned model leads, the rest of the roster backs it up.
     * Providers with a cached no-key verdict ([noKeyVerdicts], populated when a
     * failure matches [isMissingKeyFailure]) are skipped for the rest of the
     * process. Every failed or skipped attempt appends a
     * `"<endpoint>/<model>: <message>"` line to the trail; exhaustion throws
     * [BrainNoRoute] carrying that trail so the refusal lands on the record.
     */
    open suspend fun chatSeat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        temperature: Double = 0.2,
        contextId: String? = null,
        preferredModel: String? = null,
    ): Pair<String, String> = withTimeout(outerBudgetMs()) {
        chatSeatInner(messages, maxTokens, temperature, contextId, preferredModel)
    }

    /** Inner seat loop: called inside the outer timeout. */
    private suspend fun chatSeatInner(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        contextId: String?,
        preferredModel: String?,
    ): Pair<String, String> {
        val trail = mutableListOf<String>()
        if (endpoints.isEmpty()) throw BrainNoRoute(trail + "no provider endpoints discovered")
        val routed = internalModelMux.route("conflict-resolve").a
        for (modelId in seatOrder(orderedModelIds(routed), preferredModel)) {
            val endpoint = endpointByModel[modelId] ?: continue
            if (endpoint.name in noKeyVerdicts) {
                trail.add("${endpoint.name}/$modelId: skipped (no-key verdict cached)")
                continue
            }
            if (endpoint.name in retiredVerdicts) {
                trail.add("${endpoint.name}/$modelId: skipped (retired-model verdict cached)")
                continue
            }

            val acpMessages = messages.size j { i: Int -> messages[i].first j messages[i].second }
            val result = withRateLimitRetry {
                runCatching {
                    internalModelMux.chat(
                        modelId = modelId,
                        messages = acpMessages,
                        assessmentId = contextId,
                        maxTokens = maxTokens,
                        temperature = temperature,
                    ).getOrThrow() // chat() is already Result-shaped; unwrap so fold sees AcpResponse
                }
            }
            result.fold(
                onSuccess = { response ->
                    lastGoodModelId = modelId
                    return response.a to modelId // AcpResponse.a = full_text content
                },
                onFailure = { t ->
                    val message = t.message ?: t.toString()
                    trail.add("${endpoint.name}/$modelId: ${message.take(200)}")
                    if (isMissingKeyFailure(message)) noKeyVerdicts.add(endpoint.name)
                    if (isRetiredModelFailure(message)) retiredVerdicts.add(endpoint.name)
                    logError(endpoint.name, -1, message.take(500))
                },
            )
        }
        throw BrainNoRoute(trail)
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
            // Harness fallback: when an endpoint's own env var is absent, the key can
            // still resolve from hermes .env / codex / opencode credential stores.
            harness()
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
        // NVIDIA retires model ids with HTTP 410 and a dated successor. Probed
        // 2026-09-04 against /v1/models + a one-token chat: deepseek-v4-pro (EOL
        // 08-07) → -0813, deepseek-v4-flash (EOL 08-07) → -0731; nemotron-super-49b
        // (EOL 08-26), z-ai/glm-5.2 (08-21), gpt-oss-120b (09-03) and inkling
        // (08-25) are gone with no successor on NVIDIA — dropped. The first row
        // is the roster's first pick until something answers, so a dead row
        // here was one 410 round trip ahead of EVERY brain call. Still listed
        // in NVIDIA's catalog but answering 404 "Function … Not found for
        // account" to this key the same day: mistral-large-2, codestral-22b,
        // nemotron-ultra-253b, kimi-k2.6 — kept (an entitlement, not an EOL),
        // and [retiredVerdicts] parks them after one 0.15 s miss per process.
        // nemotron-ultra-550b answered, but took 22 s for one token.
        add("nv-deepseek-v4-pro", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-pro-0813")
        add("nv-nemotron-super-120b", "NVIDIA_API_KEY", nvidia, "nvidia/nemotron-3-super-120b-a12b")
        add("nv-mistral-large-2", "NVIDIA_API_KEY", nvidia, "mistralai/mistral-large-2-instruct")
        add("nv-deepseek-v4-flash", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-flash-0731")
        add("nv-kimi-k26", "NVIDIA_API_KEY", nvidia, "moonshotai/kimi-k2.6")
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
        add("perplexity", "PERPLEXITY_API_KEY", "https://api.perplexity.ai", "sonar")
        add("xai", "XAI_API_KEY", "https://api.x.ai/v1", "grok-2-latest")
        add("moonshot", "MOONSHOT_API_KEY", "https://api.moonshot.ai/v1", "moonshot-v1-32k")
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
