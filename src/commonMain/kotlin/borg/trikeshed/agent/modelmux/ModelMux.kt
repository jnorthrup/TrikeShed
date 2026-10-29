package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.iterable
import borg.trikeshed.agent.modelmux.acp.caps
import borg.trikeshed.agent.modelmux.acp.id
import borg.trikeshed.agent.modelmux.acp.providerTag
import borg.trikeshed.agent.modelmux.acp.wireName
import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.acp.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.CacheLookup
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.parse.confix.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ═══════════════════════════════════════════
// Type algebra
// ═══════════════════════════════════════════

/** Model entry: id j card */
typealias ModelEntry = Join<String, borg.trikeshed.agent.modelmux.acp.AcpModelCard>

/** Router decision: selected entries in precedence order */
typealias RouteResult = Join<Series<borg.trikeshed.agent.modelmux.ModelEntry>, borg.trikeshed.agent.modelmux.acp.AcpAction>

/** The mux: models j router */
typealias ModelMuxCore = Join<Series<borg.trikeshed.agent.modelmux.ModelEntry>, borg.trikeshed.agent.modelmux.ModelRouter>

/** Resolved credential identity and admission from observed quota; null identity is explicit auth NONE. */
typealias ModelAdmission = Join<String?, Boolean>

/** Upstream status is separate from diagnostics so HTTP hosts can relay it without exposing response bodies. */
class ModelHttpFailure(val status: Int, message: String) : IllegalStateException(message)

private fun loomEndpoint(baseUrl: String): Boolean = baseUrl.trimEnd('/') in setOf(
    "https://api.loom.vsiwest.com/v1", "https://loom.vsiwest.com/v1",
    "https://api.loom.vsiwest.com:443/v1", "https://loom.vsiwest.com:443/v1",
)

/** LOOM commits HTTP 200 before inference; its failure envelope retains the actual status. */
private fun loomStatus(doc: ConfixDoc, baseUrl: String): Int? =
    if (borg.trikeshed.agent.modelmux.loomEndpoint(baseUrl) && doc.getAt("error") != null)
        (doc.scalar("loom", "status") as? Number)?.toInt()?.takeIf { it in 100..599 }
    else null

private fun completedResponse(raw: String, baseUrl: String): ConfixDoc {
    val doc = confixDoc(raw)
    if (doc.getAt("error") != null) {
        throw borg.trikeshed.agent.modelmux.ModelHttpFailure(
            borg.trikeshed.agent.modelmux.loomStatus(
                doc,
                baseUrl
            ) ?: 502, "Provider returned an error envelope"
        )
    }
    borg.trikeshed.agent.modelmux.acp.AcpCodec.parseCompletion(raw)
    if (borg.trikeshed.agent.modelmux.loomEndpoint(baseUrl)) {
        val text = doc.scalar("choices", 0, "message", "content") as? String ?: ""
        // This reserved prefix is also stripped by LOOM's assistant-history parser.
        // Billing can remain pending after real inference, so billing metadata is not a signal.
        var rest = text.trimStart()
        val notice = rest.startsWith("⏳ LOOM: ")
        while (rest.startsWith("⏳ LOOM: ")) rest = rest.substringAfter('\n', "").trimStart()
        if (notice && rest.isEmpty() && doc.getAt("choices", 0, "message", "tool_calls")?.kids?.size.let { it == null || it == 0 }) {
            throw borg.trikeshed.agent.modelmux.ModelHttpFailure(
                202,
                "LOOM completion pending; received only provider waiting notices"
            )
        }
    }
    return doc
}

// ═══════════════════════════════════════════
// Model Router — capability-based selection
// ═══════════════════════════════════════════

interface ModelRouter {
    fun route(models: Series<borg.trikeshed.agent.modelmux.ModelEntry>, action: borg.trikeshed.agent.modelmux.acp.AcpAction, requiredCaps: Series<String>): borg.trikeshed.agent.modelmux.RouteResult
}

object CapabilityRouter : borg.trikeshed.agent.modelmux.ModelRouter {
    override fun route(
        models: Series<borg.trikeshed.agent.modelmux.ModelEntry>,
        action: borg.trikeshed.agent.modelmux.acp.AcpAction,
        requiredCaps: Series<String>
    ): borg.trikeshed.agent.modelmux.RouteResult {
        val matching = (0 until models.size)
            .filter { i -> hasCaps(models[i].b, requiredCaps) && supportsAction(models[i].b, action) }
            .map { models[it] }
        return matching.toSeries() j action
    }

    private fun hasCaps(card: borg.trikeshed.agent.modelmux.acp.AcpModelCard, required: Series<String>): Boolean =
        (0 until required.size).all { r ->
            (0 until card.caps.size).any { card.caps[it] == required[r] }
        }

    private fun supportsAction(card: borg.trikeshed.agent.modelmux.acp.AcpModelCard, action: borg.trikeshed.agent.modelmux.acp.AcpAction): Boolean =
        action in card.caps.iterable() || card.b.b.b.a == action
}

@OptIn(ExperimentalUuidApi::class)
class LlmSession(
    val model: borg.trikeshed.agent.modelmux.ModelEntry,
    private val authKey: String?,
    val baseUrl: String,
    /** Session id — stamped on every [ModelResponseReceipt] minted from this session. */
    val sessionId: String = borg.trikeshed.agent.modelmux.defaultSecureIdGenerator.generateHexId("sess", 16),
    /** The binding path resolved with this session's credential and destination. */
    val keyId: String? = null,
) {
    /** Most recent receipt produced by this session (chat/stream/embed). */
    var lastReceipt: ModelResponseReceipt? = null
        private set

    fun recordReceipt(receipt: ModelResponseReceipt) { lastReceipt = receipt }

    /** Build HTTP headers with auth */
    fun authHeaders(): Series<Join<String, String>> {
        if (authKey == null) return s_["Content-Type" j "application/json"]
        val (headerName, headerVal) = when {
            "anthropic" in baseUrl -> "x-api-key" to authKey
            else -> "Authorization" to "Bearer $authKey"
        }
        val providerHeader = if ("anthropic" in baseUrl)
            listOf("anthropic-version" j "2023-06-01") else emptyList()
        return (listOf(
            headerName j headerVal,
            "Content-Type" j "application/json"
        ) + providerHeader).toSeries()
    }
}

// ═══════════════════════════════════════════
// ModelMux — the public surface
// ═══════════════════════════════════════════

class ModelMux internal constructor(
    private val core: borg.trikeshed.agent.modelmux.ModelMuxCore,
    internal val keyMux: borg.trikeshed.agent.keymux.KeyMux,
    private val configuredBaseUrls: Map<String, String>,
    /**
     * Optional quota legion metering every chat receipt. Null = standalone
     * mode (no metering). When present, each chat's receipt is applied to the
     * legion under the resolved key id, and a 429 exhausts the key — the
     * provider's word outranks the ledger.
     */
    private val quotaLegion: borg.trikeshed.agent.modelmux.QuotaLegion? = null,
    /**
     * Which cache identities this mux will answer from, in order.
     *
     * Defaults to [borg.trikeshed.agent.modelmux.CacheCascade.EXACT_ONLY] — byte-exact sha256, the M3
     * invariant and the only identity that can never return a wrong reply. An
     * owner opts into relaxation explicitly via [borg.trikeshed.agent.modelmux.ModelMuxBuilder.cacheStrategy],
     * because every relaxed strategy trades a correctness guarantee for a hit
     * rate and that trade belongs to whoever owns the traffic.
     */
    private val cacheCascade: borg.trikeshed.agent.modelmux.CacheCascade = borg.trikeshed.agent.modelmux.CacheCascade.EXACT_ONLY,
    /** Configured selection, independent of catalog order and previous responses. */
    val defaultModel: String? = null,
    private val configuredAuth: Map<String, borg.trikeshed.agent.keymux.LlmAuth> = emptyMap(),
    private val configuredKeys: Map<String, borg.trikeshed.agent.keymux.KeyMux> = emptyMap(),
    val activity: borg.trikeshed.agent.modelmux.MuxActivity = borg.trikeshed.agent.modelmux.MuxActivity(),
) {
    private val models: Series<borg.trikeshed.agent.modelmux.ModelEntry> get() = core.a
    private val router: borg.trikeshed.agent.modelmux.ModelRouter get() = core.b

    /**
     * Observer sink for [borg.trikeshed.agent.modelmux.ModelSelectionEvent]. Null by default. Single-writer: set once, before
     * routing. The sink is called inline on the routing path but is isolated from it — a sink
     * that throws must not fail the route it is merely observing.
     */
    var selectionObserver: ((borg.trikeshed.agent.modelmux.ModelSelectionEvent) -> Unit)? = null

    /**
     * The most recent [borg.trikeshed.agent.modelmux.ModelSelectionEvent.ModelSelected] this mux emitted, or null before the
     * first non-empty route. Recorded because [route] does not return the event's `requestId`
     * — a caller that wants to reconcile a downstream [ModelResponseReceipt] against the
     * selection that produced it reads the id from here.
     */
    var lastSelection: borg.trikeshed.agent.modelmux.ModelSelectionEvent.ModelSelected? = null
        private set

    /**
     * The most recent [ModelResponseReceipt] any call through this mux produced —
     * success or failure — or null before the first call. [lastSelection] is
     * only written by [route]; a caller that names its model outright
     * (`chat(modelId = …)`, which is what every LCNC prompt does) never routes,
     * so this is the record of what actually answered.
     */
    var lastReceipt: ModelResponseReceipt? = null
        private set

    /**
     * Label naming the ranking discipline behind a selection; stamped onto
     * [borg.trikeshed.agent.modelmux.ModelSelectionEvent.ModelSelected.strategy].
     *
     * This is a declaration, not a derivation: nothing verifies it. It defaults to
     * `"capability"`, which is what [borg.trikeshed.agent.modelmux.CapabilityRouter] — the router [borg.trikeshed.agent.modelmux.ModelMuxBuilder] installs
     * — actually does, namely filter by capability and preserve catalog order. An owner that
     * ranks the route result through a [borg.trikeshed.agent.modelmux.RoutingStrategy] should set this to that strategy's
     * [borg.trikeshed.agent.modelmux.RoutingStrategy.strategyName]; setting it to a discipline that did not run makes the
     * event stream lie.
     */
    var strategyName: String = "capability"

    companion object {
        operator fun invoke(keyMux: borg.trikeshed.agent.keymux.KeyMux, block: borg.trikeshed.agent.modelmux.ModelMuxBuilder.() -> Unit): ModelMux =
            borg.trikeshed.agent.modelmux.ModelMuxBuilder(keyMux).apply(block).build()
    }

    /** Create a session for a specific model by ID */
    suspend fun session(modelId: String): Result<borg.trikeshed.agent.modelmux.LlmSession> {
        borg.trikeshed.kernel.KernelExecution.require(borg.trikeshed.kernel.KernelCapability.MODEL)
        runCatching { borg.trikeshed.agent.modelmux.Airgap.requireRemote() }.getOrElse { return Result.failure(it) }
        val entry = (0 until models.size).firstOrNull { models[it].a == modelId }?.let { models[it] }
            ?: return Result.failure(NoSuchElementException("need(model): model not found: $modelId"))
        val card = entry.b
        // A provider-tagged card resolves against the provider's pooled
        // credential (one key serves every model that provider hosts) before
        // falling to the per-model lookup untagged cards have always used.
        val binding = credentialMux(modelId).resolveLlm(card.id, card.providerTag,
            configuredBaseUrls[modelId] ?: "https://api.openai.com/v1", auth(modelId))
            ?: return Result.failure(IllegalStateException("need(key): no auth key for model: $modelId"))
        val session = borg.trikeshed.agent.modelmux.LlmSession(
            entry,
            binding.authKey,
            binding.baseUrl,
            keyId = binding.keyId
        )
        return Result.success(session)
    }

    /**
     * Resolve the key ID (binding path) a call for [card] meters under, through
     * the SAME fallback chain [session] uses to resolve the key VALUE:
     * provider tag → model id → default. Never returns the secret itself —
     * the returned string is the binding path (`llm.<provider>.key`), which is
     * the ledger identity quota metering keys on. Null means no binding resolves,
     * or the model explicitly uses credential-free authentication.
     */
    private suspend fun resolveKeyId(card: borg.trikeshed.agent.modelmux.acp.AcpModelCard): String? {
        return credentialMux(card.id).resolveLlm(card.id, card.providerTag,
            configuredBaseUrls[card.id] ?: "https://api.openai.com/v1", auth(card.id))?.keyId
    }

    /**
     * Route to the best model for a given action + capabilities.
     *
     * This is the selection point. The result is a ranked candidate list, and its head is the
     * selection this mux is reporting: on a non-empty route a [borg.trikeshed.agent.modelmux.ModelSelectionEvent.ModelSelected]
     * naming `result.a[0]` is recorded as [lastSelection] and handed to [selectionObserver].
     * An empty route selects nothing and emits nothing.
     *
     * A caller free to ignore the ranking and call [chat] with some other candidate will find
     * the event stream disagreeing with what it did; the event describes the head of the
     * ranking, which is the only selection this method makes.
     */
    fun route(action: borg.trikeshed.agent.modelmux.acp.AcpAction, vararg requiredCaps: String): borg.trikeshed.agent.modelmux.RouteResult {
        val result = router.route(models, action, requiredCaps.toSeries())
        if (result.a.size > 0) {
            val chosen = result.a[0]
            val event = borg.trikeshed.agent.modelmux.ModelSelectionEvent.ModelSelected(
                provider = chosen.b.providerTag ?: chosen.b.id,
                model = chosen.a,
                strategy = strategyName,
                requestId = borg.trikeshed.agent.modelmux.defaultSecureIdGenerator.generateHexId("req", 8),
                at = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
            )
            lastSelection = event
            val observer = selectionObserver
            if (observer != null) {
                // An observability hook must never fail the operation it observes: a closed
                // appender or a full queue costs the event, not the route.
                try {
                    observer(event)
                } catch (_: Throwable) {
                }
            }
        }
        return result
    }

    /** Non-streaming chat completion */
    suspend fun chat(
        modelId: String,
        messages: Series<borg.trikeshed.agent.modelmux.acp.AcpMessage>,
        tools: Series<borg.trikeshed.agent.modelmux.acp.AcpTool> = 0 j { error("no tools") },
        assessmentId: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        /**
         * Conversation identity (plan step 5): the frame-chain cid this call
         * belongs to. Stamped verbatim on the receipt's `assessmentId` slot —
         * one provenance field, two names: callers from the kanban dispatcher
         * pass a descriptor id, callers from the wire pass the contextId.
         * Receipt → frame reconciliation needs no second field.
         */
        contextId: String? = null,
        timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
        expectedBaseUrl: String? = null,
        jsonOutput: Boolean = false,
        thinking: Boolean? = null,
    ): Result<borg.trikeshed.agent.modelmux.acp.AcpResponse> = chatRequest(
        modelId, assessmentId, contextId, timeoutMs, expectedBaseUrl,
        encode = { wireModel ->
            val meta: borg.trikeshed.agent.modelmux.acp.AcpMeta = wireModel j ("chat" j emptySeriesOf())
            borg.trikeshed.agent.modelmux.acp.AcpCodec.encodeRequest(meta j (messages j tools), maxTokens, temperature, jsonOutput, thinking)
        },
        decode = borg.trikeshed.agent.modelmux.acp.AcpCodec::parseResponse,
    )

    /** Structured, non-streaming chat: preserve tool calls, history and provider metadata. */
    suspend fun completion(
        modelId: String,
        request: ConfixDoc,
        assessmentId: String? = null,
        contextId: String? = null,
        timeoutMs: Long = HtxRequest.DEFAULT_TIMEOUT_MS,
        expectedBaseUrl: String? = null,
    ): Result<ConfixDoc> {
        // Invalid protocol requests are rejected before admission. The catalog, not
        // a guest-supplied model field, owns the wire model and credential binding.
        val wireModel = models.view.firstOrNull { it.a == modelId }?.b?.wireName ?: modelId
        val json = runCatching {
            borg.trikeshed.agent.modelmux.Airgap.requireRemote()
            borg.trikeshed.agent.modelmux.acp.AcpCodec.encodeCompletion(request, wireModel)
        }
            .getOrElse { return Result.failure(it) }
        return chatRequest(modelId, assessmentId, contextId, timeoutMs, expectedBaseUrl,
            encode = { json }, decode = borg.trikeshed.agent.modelmux.acp.AcpCodec::parseCompletion)
    }

    private suspend fun <T> chatRequest(
        modelId: String,
        assessmentId: String?,
        contextId: String?,
        timeoutMs: Long,
        expectedBaseUrl: String?,
        encode: (String) -> String,
        decode: (String) -> T,
    ): Result<T> {
        val attribution = currentCoroutineContext()[borg.trikeshed.agent.modelmux.MuxCallContext]
        val sessionContext = contextId ?: attribution?.conversationId?.toString()
        val breadcrumbs = currentCoroutineContext()[borg.trikeshed.agent.modelmux.BreadcrumbElement]
        val breadcrumbId = sessionContext?.takeIf { it.isNotBlank() && it.length <= 128 }
        val admission = currentCoroutineContext()[borg.trikeshed.agent.modelmux.BreadcrumbAdmission]?.takeIf { it.owner === breadcrumbs && it.id == breadcrumbId }
        var breadcrumb: borg.trikeshed.agent.modelmux.BreadcrumbContext? = admission?.context
        val callActivity = attribution?.activity ?: activity
        val provider = models.view.firstOrNull { it.a == modelId }?.b?.providerTag ?: modelId
        val callId = callActivity.start(modelId, provider, attribution, assessmentId ?: contextId, sessionContext)
        val startedAt = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        var receipt: ModelResponseReceipt? = null
        var keyId: String? = null
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            borg.trikeshed.agent.modelmux.Airgap.requireRemote()
            if (breadcrumbs != null && breadcrumbId != null && admission == null) {
                try {
                    withContext(NonCancellable) { breadcrumb = breadcrumbs.transact(breadcrumbId) { it } }
                }
                catch (_: borg.trikeshed.agent.modelmux.BreadcrumbCapacityException) { /* Observation cannot block an admitted model call. */ }
            }
            currentCoroutineContext().ensureActive()
            val result = chatCall(modelId, assessmentId ?: contextId, sessionContext, breadcrumb, timeoutMs, expectedBaseUrl, encode, decode) { r, k ->
                receipt = r
                keyId = k
            }
            failure = result.exceptionOrNull()
            // Transport timeouts cancel their own scope, not the caller's work queue.
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            return result
        } catch (t: Throwable) {
            failure = t
            if (t is CancellationException) currentCoroutineContext().ensureActive()
            return Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                // Admission failures have no session or HTTP status, but still
                // retain the exact proposed request and its failure provenance.
                val completed = receipt ?: run {
                    val wireModel = models.view.firstOrNull { it.a == modelId }?.b?.wireName ?: modelId
                    val json = encode(wireModel)
                    ModelResponseReceipt.mint(
                        modelId = modelId, providerId = modelId,
                        requestHash = cacheCascade.primary(json), action = "chat", httpStatus = 0,
                        latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - startedAt,
                        assessmentId = assessmentId ?: contextId, error = failure,
                    )
                }
                receipt = completed
                lastReceipt = completed
                // Receipt delivery is observational; it cannot suppress activity completion.
                runCatching { attribution?.onReceipt?.invoke(completed) }
                callActivity.finish(callId, receipt, keyId, failure)?.let { finished ->
                    runCatching { attribution?.onFinished?.invoke(finished) }
                    if (breadcrumbs != null && breadcrumbId != null && breadcrumb != null) {
                        try {
                            breadcrumbs.transact(breadcrumbId, create = false) {
                                if (it === breadcrumb) it.record(finished)
                            }
                        } catch (_: borg.trikeshed.agent.modelmux.BreadcrumbContextNotFoundException) { /* Deleted while the provider was answering. */ }
                    }
                }
            }
        }
    }

    private suspend fun <T> chatCall(
        modelId: String,
        assessmentId: String?,
        contextId: String?,
        breadcrumb: borg.trikeshed.agent.modelmux.BreadcrumbContext?,
        timeoutMs: Long,
        expectedBaseUrl: String?,
        encode: (String) -> String,
        decode: (String) -> T,
        capture: (ModelResponseReceipt, String?) -> Unit,
    ): Result<T> {
        val receiptAssessment = assessmentId ?: contextId
        if (modelId.isEmpty()) return Result.failure(IllegalArgumentException("need(model): modelId must be non-empty"))
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) return Result.failure(sessionResult.exceptionOrNull()!!)
        val session = sessionResult.getOrThrow()
        if (expectedBaseUrl != null && session.baseUrl.trimEnd('/') != expectedBaseUrl.trimEnd('/')) {
            return Result.failure(IllegalStateException("Model destination changed; review the current destination before sending source material"))
        }
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Meter under the resolved key ID (the binding path), not the key VALUE
        // — the value is the secret, and it bypassed the providerTag chain
        // session() honours, so tagged providers metered under null.
        val keyId = session.keyId
        // The reactor's roster is the keys this process actually used: record the
        // resolved key on dispatch so the quota legion's standings can project it
        // (delta 2026-09-04 — before this only the daemon's boot-time
        // `<provider>-default` rows were ever on the roster, and every metered
        // receipt landed on a key the standings could not show).
        if (reactor != null && keyId != null) {
            reactor.recordAccess(keyId = keyId, provider = session.model.b.providerTag ?: session.model.b.id, label = keyId)
        }
        val t0 = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        var httpStatus = 0
        var cachedHit = false
        var inputTokens = 0
        var outputTokens = 0
        var requestHash = ""
        var responseHeaders = emptyHtxHeaders()
        try {
            val card = session.model.b
            val json = encode(card.wireName)
            // Content-address the canonical request bytes. String.hashCode() is a
            // 32-bit truncation: two distinct requests colliding on it returned each
            // other's cached payload verbatim.
            //
            // The receipt is ALWAYS attributed to the exact identity, whichever
            // strategy produced the hit: a ledger keyed by a relaxed bucket could
            // not be reconciled against the bytes actually sent.
            requestHash = cacheCascade.primary(json)
            // Every identity this mux is willing to answer from, exact first.
            // EXACT_ONLY (the default) makes this a one-element list and the
            // behaviour byte-identical to before strategies existed.
            val cacheIds = cacheIdentities(session, json)

            if (reactor != null) {
                // First hit wins, and exact is always tried first — the cheapest
                // correct answer outranks the most permissive one.
                var hit: CacheLookup.Hit? = null
                for ((_, id) in cacheIds.view) {
                    val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000)
                    if (lookup is CacheLookup.Hit) { hit = lookup; break }
                }
                if (hit != null && runCatching { completedResponse(hit.entry.payload, session.baseUrl) }.isSuccess) {
                    cachedHit = true
                    httpStatus = 200
                    val cached = decode(hit.entry.payload)
                    val usage = AcpCodec.parseResponse(hit.entry.payload).b
                    inputTokens = usage.a
                    outputTokens = usage.b
                    session.recordReceipt(
                        ModelResponseReceipt.mint(
                            modelId = modelId, providerId = card.id, requestHash = requestHash,
                            action = "chat", httpStatus = 200, latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                            inputTokens = inputTokens, outputTokens = outputTokens, cachedHit = true,
                            assessmentId = receiptAssessment, sessionId = session.sessionId,
                            // cachedHit=true records OUR reactor-cache hit; cacheRead/Write stay
                            // provider-measured only — this request never reached a provider.
                            cacheReadTokens = 0, cacheWriteTokens = 0,
                        )
                    )
                    return Result.success(cached)
                }
            }

            requireQuota(keyId)
            val htx = currentCoroutineContext()[HtxKey] ?: throw IllegalStateException("No HtxKey found in coroutine context")
            val url = "${session.baseUrl.trimEnd('/')}/chat/completions"
            val htxHeaders = htxHeaders(*session.authHeaders().toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders, timeoutMs = timeoutMs)

            Airgap.requireRemote()
            val breadcrumbs = currentCoroutineContext()[BreadcrumbElement]
            if (breadcrumbs != null && contextId != null && breadcrumb != null) {
                try {
                    breadcrumbs.transact(contextId, create = false) {
                        if (it === breadcrumb) {
                            it.submit(BreadcrumbModelHost.submission(modelId, confixDoc(json), BreadcrumbTimestamp(it.clock())))
                        }
                    }
                } catch (_: BreadcrumbContextNotFoundException) { /* This session was released after admission. */ }
            }
            val resp = htx.request(htxReq)
            val respBody = resp.body.toArray().decodeToString()
            httpStatus = resp.status
            responseHeaders = resp.headers
            if (loomEndpoint(session.baseUrl) && resp.status in 200..299) {
                val envelope = runCatching { confixDoc(respBody) }.getOrNull()
                if (envelope != null) {
                    loomStatus(envelope, session.baseUrl)?.let { httpStatus = it }
                    val retry = (envelope.scalar("loom", "retry_after_seconds") as? Number)?.toLong()
                    if (retry != null && retry >= 0) {
                        responseHeaders = (responseHeaders.view + ("Retry-After" j retry.toString())).toSeries()
                    }
                }
            }

            if (reactor != null && (httpStatus !in 200..299 || httpStatus == 202)) {
                reactor.recordProviderHealth(
                    provider = card.id,
                    success = false,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0
                )
            }

            if (httpStatus !in 200..299 || httpStatus == 202) {
                // Record the failure receipt BEFORE returning: the finally-block legion
                // metering reads session.lastReceipt, and a 429 that left no receipt
                // never exhausted its key — the provider's "no" fell on the floor.
                session.recordReceipt(
                    ModelResponseReceipt.mint(
                        modelId = modelId, providerId = card.id, requestHash = requestHash,
                        action = "chat", httpStatus = httpStatus,
                        latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                        assessmentId = receiptAssessment, sessionId = session.sessionId,
                        error = IllegalStateException("Provider status $httpStatus"),
                    )
                )
                // 429: the credential is rate-limited; the next session() resolves the pool's next key.
                if (httpStatus == 429 && keyId != null) {
                    val quota = HttpQuota.parse(httpStatus, responseHeaders, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
                    runCatching { credentialMux(modelId).rotate(keyId, quota.resetAtMs) }
                        .onFailure { println("[MODELMUX] rotate($keyId) failed: ${it.message}") }
                }
                return Result.failure(ModelHttpFailure(httpStatus, "ModelMux chat returned provider status $httpStatus"))
            }

            // HTTP acceptance and cold-start status payloads are not completed model work.
            completedResponse(respBody, session.baseUrl)
            val parsed = decode(respBody)
            reactor?.recordProviderHealth(provider = card.id, success = true,
                latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0)
            val usage = AcpCodec.parseResponse(respBody).b
            inputTokens = usage.a
            outputTokens = usage.b
            // Provider-measured cache economics only — never synthesized from inputTokens.
            val cacheUse = providerCacheUsage(respBody)

            if (reactor != null) {
                // Warm EVERY identity from this one paid call: the exact entry, plus
                // each relaxed bucket. A later request differing only in formatting
                // then hits without a second purchase — which is the whole return on
                // running a cascade at all.
                for ((_, id) in cacheIds.view) {
                    reactor.cacheApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000, payload = respBody)
                }
            }
            session.recordReceipt(
                ModelResponseReceipt.mint(
                    modelId = modelId, providerId = card.id, requestHash = requestHash,
                    action = "chat", httpStatus = httpStatus, latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                    inputTokens = inputTokens, outputTokens = outputTokens, cachedHit = false,
                    assessmentId = receiptAssessment, sessionId = session.sessionId,
                    cacheReadTokens = cacheUse.a, cacheWriteTokens = cacheUse.b,
                )
            )
            return Result.success(parsed)
        } catch (t: Throwable) {
            session.recordReceipt(
                ModelResponseReceipt.mint(
                    modelId = modelId, providerId = session.model.b.id, requestHash = requestHash,
                    action = "chat", httpStatus = httpStatus,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                    assessmentId = receiptAssessment, sessionId = session.sessionId,
                    error = t,
                )
            )
            return Result.failure(t)
        } finally {
            // Quota legion metering: only real (non-cached) calls consume provider
            // quota. A 429 receipt exhausts the key inside applyReceipt.
            session.lastReceipt?.let { receipt ->
                capture(receipt, keyId)
                lastReceipt = receipt
                if (keyId != null && !receipt.cachedHit) {
                    val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                    val provider = session.model.b.providerTag ?: session.model.b.id
                    quotaLegion?.applyReceipt(
                        keyId, provider, receipt, now,
                    )
                    if (httpStatus > 0) quotaLegion?.observeHttp(keyId, provider, httpStatus, responseHeaders, now)
                }
            }
            if (reactor != null && keyId != null) {
                val leasedTo = reactor.flowState.value.leases.firstOrNull { it.keyId == keyId }?.leasedTo
                if (leasedTo != null) {
                    reactor.releaseLease(keyId, leasedTo)
                }
            }
        }
    }

    /** Streaming chat completion via SSE */
    fun stream(
        modelId: String,
        messages: Series<AcpMessage>,
        tools: Series<AcpTool> = 0 j { error("no tools") }
    ): Flow<AcpChunk> = flow {
        Airgap.requireRemote()
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) throw sessionResult.exceptionOrNull()!!
        val session = sessionResult.getOrThrow()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Lease identity is the binding PATH (llm.<provider>.key), never the secret
        // value — keyMux.get(...) returns the credential, which matched no lease and
        // leaked it. Same providerTag fallback chain session()/chat() honour.
        val keyId = session.keyId
        // The reactor's roster is the keys this process actually used: record the
        // resolved key on dispatch so the quota legion's standings can project it
        // (delta 2026-09-04 — before this only the daemon's boot-time
        // `<provider>-default` rows were ever on the roster, and every metered
        // receipt landed on a key the standings could not show).
        if (reactor != null && keyId != null) {
            reactor.recordAccess(keyId = keyId, provider = session.model.b.providerTag ?: session.model.b.id, label = keyId)
        }
        try {
            requireQuota(keyId)
            val card = models.let { ms -> (0 until ms.size).first { ms[it].a == modelId }.let { ms[it] } }.b
            val meta: AcpMeta = card.wireName j ("stream" j session.authHeaders())
            val body: AcpRequestBody = messages j tools
            val req: AcpRequest = meta j body

            val json = AcpCodec.encodeRequest(req)
            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl.trimEnd('/')}/chat/completions"
            val htxHeaders = htxHeaders(*meta.b.b.toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            Airgap.requireRemote()
            val resp = htx.request(htxReq)
            val text = resp.body.toArray().decodeToString()
            val envelope = if (loomEndpoint(session.baseUrl) && text.trimStart().startsWith('{')) confixDoc(text) else null
            val status = observeQuota(session, resp.status, resp.headers, envelope)
            if (status !in 200..299 || status == 202) {
                throw ModelHttpFailure(status, "ModelMux stream returned provider status $status")
            }
            var frames = false
            var finished = false
            text.lineSequence().forEach { line ->
                if (line.startsWith("data:")) {
                    frames = true
                    val data = line.removePrefix("data:").trim()
                    if (data != "[DONE]") {
                        val doc = confixDoc(data)
                        if (doc.getAt("error") != null) {
                            val status = loomStatus(doc, session.baseUrl) ?: 502
                            observeQuota(session, status, resp.headers, doc)
                            throw ModelHttpFailure(status, "Provider stream returned an error envelope")
                        }
                        if (doc.scalar("choices", 0, "finish_reason") != null) finished = true
                        AcpCodec.parseChunk(line)?.let {
                            if (it.a.isNotEmpty() || it.b.a > 0 || it.b.b > 0) emit(it)
                        }
                    }
                }
            }
            if (frames) {
                check(finished) { "Model stream ended before a completion; provider heartbeats are not model output" }
            } else {
                completedResponse(text, session.baseUrl)
                val parsed = AcpCodec.parseResponse(text)
                emit(parsed.a j parsed.b)
            }
        } finally {
            if (reactor != null && keyId != null) {
                val leasedTo = reactor.flowState.value.leases.firstOrNull { it.keyId == keyId }?.leasedTo
                if (leasedTo != null) {
                    reactor.releaseLease(keyId, leasedTo)
                }
            }
        }
    }

    /** Embed — routes to an embedding-capable model */
    suspend fun embed(modelId: String, texts: Series<String>): Series<Join<String, Series<Double>>> {
        Airgap.requireRemote()
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) throw sessionResult.exceptionOrNull()!!
        val session = sessionResult.getOrThrow()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Lease identity is the binding PATH, never the secret value (stream() fix,
        // same defect class): keyMux.get returns the credential, which matched no
        // lease. providerTag fallback chain via resolveKeyId.
        val keyId = session.keyId
        // The reactor's roster is the keys this process actually used: record the
        // resolved key on dispatch so the quota legion's standings can project it
        // (delta 2026-09-04 — before this only the daemon's boot-time
        // `<provider>-default` rows were ever on the roster, and every metered
        // receipt landed on a key the standings could not show).
        if (reactor != null && keyId != null) {
            reactor.recordAccess(keyId = keyId, provider = session.model.b.providerTag ?: session.model.b.id, label = keyId)
        }
        try {
            val card = models.let { ms -> (0 until ms.size).first { ms[it].a == modelId }.let { ms[it] } }.b
            val meta: AcpMeta = card.wireName j ("embed" j session.authHeaders())
            val textsJson = (0 until texts.size).joinToString(",") { jsonStr(texts[it]) }
            val json = "{\"model\":\"${card.wireName}\",\"input\":[$textsJson]}"

            // Same cascade as chat(): embeddings of a re-wrapped paragraph are the
            // same embeddings, and paying twice for that was the same defect.
            val requestHash = cacheCascade.primary(json)
            val cacheIds = cacheIdentities(session, json)
            if (reactor != null) {
                for ((_, id) in cacheIds.view) {
                    val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000)
                    if (lookup is CacheLookup.Hit) {
                        return parseEmbeddings(lookup.entry.payload, texts)
                    }
                }
            }

            requireQuota(keyId)
            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl.trimEnd('/')}/embeddings"
            val htxHeaders = htxHeaders(*meta.b.b.toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val t0 = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            Airgap.requireRemote()
            val resp = htx.request(htxReq)
            observeQuota(session, resp.status, resp.headers)
            val respBody = resp.body.toArray().decodeToString()

            if (reactor != null) {
                reactor.recordProviderHealth(
                    provider = card.id,
                    success = resp.status in 200..299,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0
                )
                if (resp.status in 200..299) {
                    for ((_, id) in cacheIds.view) {
                        reactor.cacheApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000, payload = respBody)
                    }
                }
            }
            if (resp.status !in 200..299) {
                throw IllegalStateException("ModelMux embed failed with HTTP ${resp.status}: ${respBody.take(500)}")
            }
            return parseEmbeddings(respBody, texts)
        } finally {
            if (reactor != null && keyId != null) {
                val leasedTo = reactor.flowState.value.leases.firstOrNull { it.keyId == keyId }?.leasedTo
                if (leasedTo != null) {
                    reactor.releaseLease(keyId, leasedTo)
                }
            }
        }
    }

    /** List available model cards, optionally filtered by capability */
    suspend fun modelKeyId(modelId: String): String? =
        models.view.firstOrNull { it.a == modelId }?.b?.let { resolveKeyId(it) }

    /**
     * Resolve the same binding as [session], including credential cooldowns,
     * then consult known HTTP quota. Unknown quota remains admitted; this
     * projection sends no request to the model or provider. Null means the
     * model or its required credential cannot resolve.
     */
    suspend fun modelAdmission(
        modelId: String,
        nowMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
    ): ModelAdmission? {
        val card = models.view.firstOrNull { it.a == modelId }?.b ?: return null
        val binding = credentialMux(modelId).resolveLlm(
            card.id, card.providerTag, configuredBaseUrls[modelId] ?: "https://api.openai.com/v1", auth(modelId),
        ) ?: return null
        val keyId = binding.keyId
        return keyId j (keyId == null || quotaLegion?.isAvailable(keyId, nowMs) != false)
    }

    fun configuredBaseUrl(modelId: String): String? = configuredBaseUrls[modelId]

    fun contextWindow(modelId: String): Int? = models.view.firstOrNull { it.a == modelId }?.b
        ?.meta?.headers?.view?.firstOrNull { it.a == "context_window" }?.b?.toIntOrNull()?.takeIf { it > 0 }

    fun auth(modelId: String): LlmAuth = configuredAuth[modelId] ?: LlmAuth.REQUIRED

    private fun credentialMux(modelId: String): KeyMux = configuredKeys[modelId] ?: keyMux

    private fun requireQuota(keyId: String?) {
        check(keyId == null || quotaLegion?.isAvailable(keyId, kotlinx.datetime.Clock.System.now().toEpochMilliseconds()) != false) {
            "Provider quota exhausted; awaiting its reported reset"
        }
    }

    /** Streaming and embedding calls share HTTP admission evidence even without chat receipts. */
    private suspend fun observeQuota(session: LlmSession, status: Int, headers: HtxHeaders, envelope: ConfixDoc? = null): Int {
        val reportedStatus = envelope?.let { loomStatus(it, session.baseUrl) } ?: status
        val keyId = session.keyId ?: return reportedStatus
        val retry = if (envelope != null && loomEndpoint(session.baseUrl))
            (envelope.scalar("loom", "retry_after_seconds") as? Number)?.toLong()?.takeIf { it >= 0 } else null
        val reportedHeaders = if (retry != null) (headers.view + ("Retry-After" j retry.toString())).toSeries() else headers
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val provider = session.model.b.providerTag ?: session.model.b.id
        val observation = quotaLegion?.observeHttp(keyId, provider, reportedStatus, reportedHeaders, now)
            ?: HttpQuota.parse(reportedStatus, reportedHeaders, now)
        if (reportedStatus == 429) runCatching { credentialMux(session.model.a).rotate(keyId, observation.resetAtMs) }
        return reportedStatus
    }

    /** Cache scope includes the resolved destination; receipts continue to identify the exact request bytes. */
    private fun cacheIdentities(session: LlmSession, json: String): Series<Twin<String>> {
        val destination = session.baseUrl.trimEnd('/')
        return cacheCascade.identities(json).toSeries() α { (strategy, id) ->
            strategy j ContentId.of("$destination\u0000$id".encodeToByteArray()).value
        }
    }

    /** Extend a catalog, isolating new credentials while preserving inherited bindings, quota and cache policy. */
    fun withModels(keyMux: KeyMux = this.keyMux, block: ModelMuxBuilder.() -> Unit): ModelMux {
        val added = ModelMuxBuilder(keyMux).apply(block).build()
        require(added.models.view.none { entry -> models.view.any { it.a == entry.a } }) { "Duplicate model id" }
        val combined: Series<ModelEntry> = (models.size + added.models.size) j { index ->
            if (index < models.size) models[index] else added.models[index - models.size]
        }
        return ModelMux(combined j router, this.keyMux, configuredBaseUrls + added.configuredBaseUrls,
            quotaLegion, cacheCascade, defaultModel, configuredAuth + added.configuredAuth,
            configuredKeys + added.models.view.associate { it.a to keyMux }, activity)
    }

    fun listModels(vararg cap: String): Series<AcpModelCard> {
        val cards = models.α { it.b }
        if (cap.isEmpty()) return cards
        val capSeries = cap.toSeries()
        return filtered(cards, capSeries)
    }

    private fun filtered(cards: Series<AcpModelCard>, caps: Series<String>): Series<AcpModelCard> {
        val match = (0 until cards.size).filter { i ->
            caps.size == 0 || (0 until caps.size).all { c ->
                (0 until cards[i].caps.size).any { cards[i].caps[it] == caps[c] }
            }
        }
        return match.size j { i -> cards[match[i]] }
    }

    private fun jsonStr(s: String): String = "\"" +
        s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n") + "\""

    private fun parseEmbeddings(json: String, texts: Series<String>): Series<Join<String, Series<Double>>> {
        val results = mutableListOf<Join<String, Series<Double>>>()
        var idx = 0
        var searchFrom = 0
        while (true) {
            val ei = json.indexOf("\"embedding\"", searchFrom)
            if (ei < 0) break
            val arrStart = json.indexOf('[', ei)
            val arrEnd = json.indexOf(']', arrStart)
            val nums = json.substring(arrStart + 1, arrEnd)
                .split(',').map { it.trim().toDouble() }
            results.add(texts[idx] j nums.toSeries())
            idx++; searchFrom = arrEnd + 1
        }
        return results.toSeries()
    }

    /**
     * The legion's standings projected against the live reactor key roster —
     * usable-first, most-remaining next. Empty when no legion is attached or
     * no [MuxReactorElement] rides the calling context (the roster is the
     * reactor's; the ledger alone names no keys).
     */
    suspend fun quotaStandings(nowMs: Long): List<QuotaStanding> {
        val legion = quotaLegion ?: return emptyList()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key] ?: return emptyList()
        return legion.standings(reactor.flowState.value, nowMs).toList()
    }
}

class ModelMuxBuilder(private val keyMux: KeyMux) {
    private val models = mutableListOf<ModelEntry>()
    private var quotaLegion: QuotaLegion? = null
    private var cascade: CacheCascade = CacheCascade.EXACT_ONLY
    private var defaultModel: String? = null

    fun defaultModel(id: String): ModelMuxBuilder = apply { defaultModel = id }

    init {
        // The legion is constructed by default: receipts metered into it on every
        // chat. An explicit `.quota(...)` replaces it; `.noQuota()` restores the
        // standalone behaviour of dropping receipts. Before this default, the
        // legion was built in tests only — every production receipt fell on the
        // floor and quota metering never ran.
        quotaLegion = QuotaLegion()
    }

    /** Attach a quota legion to meter every non-cached chat receipt. */
    fun quota(legion: QuotaLegion): ModelMuxBuilder = apply { quotaLegion = legion }

    /** Detach metering — receipts are produced but never applied to a ledger. */
    fun noQuota(): ModelMuxBuilder = apply { quotaLegion = null }

    /**
     * Opt into additional cache identities beyond the byte-exact default.
     *
     *     ModelMux(keyMux) {
     *         model(id = "gpt-4o-mini", caps = setOf("chat"), provider = "openai")
     *         cacheStrategy(CacheCascade.RELAXED)          // prose-shaped traffic
     *         // or: cacheStrategy(CacheCascade.RELAXED_INDENT_SAFE)
     *         // or: cacheStrategy(listOf(WhitespaceRelaxed(myPolicy)))
     *     }
     *
     * Exact is always consulted first regardless of what is passed, so this can
     * only ADD hits, never redirect one that would have been exact.
     */
    fun cacheStrategy(cascade: CacheCascade): ModelMuxBuilder = apply { this.cascade = cascade }

    /** Convenience: build a cascade from strategies, exact implicitly first. */
    fun cacheStrategy(strategies: List<CacheKeyStrategy>): ModelMuxBuilder =
        apply { this.cascade = CacheCascade(strategies) }

    fun model(
        id: String,
        caps: Set<String>,
        baseUrl: String? = null,
        version: String = "1.0",
        /** Tags this card so [ModelMux.session] resolves keys by provider, not model id. */
        provider: String? = null,
        /**
         * What the PROVIDER calls this model, when [id] had to be qualified to stay
         * unique in the catalog. Null means [id] is already the provider's name.
         * See [modelmux.acp.wireModel] — without this, a disambiguated id like
         * `openrouter/z-ai/glm-5.2` goes out on the wire and earns a 400.
         */
        wireModel: String? = null,
        /** Credential-free hosts must have an explicit destination; default keys never reach them. */
        auth: LlmAuth = LlmAuth.REQUIRED,
        /** Advertised input/output context ceiling, in tokens; zero keeps it unknown. */
        contextWindow: Int = 0,
        /** Observed model revision, independent of the ACP protocol [version]. */
        modelVersion: String? = null,
    ): ModelMuxBuilder = apply {
        require(auth != LlmAuth.NONE || !baseUrl.isNullOrBlank()) {
            "Credential-free models require an explicit baseUrl"
        }
        val capSeries = caps.toSeries()
        val action = if ("chat" in caps) "chat" else if ("embed" in caps) "embed" else "complete"
        val tags = buildList {
            if (provider != null) add("provider" j provider)
            if (wireModel != null && wireModel != id) add("wire_model" j wireModel)
            if (contextWindow > 0) add("context_window" j contextWindow.toString())
            if (!modelVersion.isNullOrBlank()) add("model_version" j modelVersion)
        }
        val headers: Series<Join<String, String>> = if (tags.isNotEmpty()) {
            tags.toSeries()
        } else {
            0 j { _: Int -> error("no headers") }
        }
        val meta: AcpMeta = version j (action j headers)
        val card: AcpModelCard = id j (capSeries j meta)
        models.add(id j card)
        pendingAuth[id] = auth
        if (baseUrl != null) {
            pendingUrls[id] = baseUrl
        }
    }

    private val pendingUrls = mutableMapOf<String, String>()
    private val pendingAuth = mutableMapOf<String, LlmAuth>()

    internal fun build(): ModelMux {
        require(defaultModel == null || models.any { it.a == defaultModel }) { "Default model is not in the catalog" }
        val core: ModelMuxCore = models.toSeries() j CapabilityRouter
        return ModelMux(core, keyMux, pendingUrls.toMap(), quotaLegion, cascade, defaultModel, pendingAuth.toMap())
    }
}

/**
 * Provider-reported prompt-cache token counts, (readTokens j writeTokens); zero when
 * unreported. Receipts carry measured values only — never synthesized. The keys are
 * the wire fields providers emit (`cached_tokens`, `cache_read_input_tokens`,
 * `cache_creation_input_tokens`) — whoever sends them. Private to the mux receipt
 * path on purpose: receipt repair, not protocol surface.
 */
private fun providerCacheUsage(json: String): Join<Int, Int> {
    fun intAfter(key: String): Int {
        val i = json.indexOf(key)
        if (i < 0) return 0
        return json.substring(i).substringAfter(':').substringBefore(',').substringBefore('}')
            .trim().toIntOrNull() ?: 0
    }
    val read = maxOf(intAfter("\"cached_tokens\""), intAfter("\"cache_read_input_tokens\""))
    val write = intAfter("\"cache_creation_input_tokens\"")
    return read j write
}
