package modelmux

import keymux.*
import modelmux.acp.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.CacheLookup
import borg.trikeshed.modelmux.ModelResponseReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// ═══════════════════════════════════════════
// Type algebra
// ═══════════════════════════════════════════

/** Model entry: id j card */
typealias ModelEntry = Join<String, AcpModelCard>

/** Router decision: selected entries in precedence order */
typealias RouteResult = Join<Series<ModelEntry>, AcpAction>

/** The mux: models j router */
typealias ModelMuxCore = Join<Series<ModelEntry>, ModelRouter>

// ═══════════════════════════════════════════
// Model Router — capability-based selection
// ═══════════════════════════════════════════

interface ModelRouter {
    fun route(models: Series<ModelEntry>, action: AcpAction, requiredCaps: Series<String>): RouteResult
}

object CapabilityRouter : ModelRouter {
    override fun route(
        models: Series<ModelEntry>,
        action: AcpAction,
        requiredCaps: Series<String>
    ): RouteResult {
        val matching = (0 until models.size)
            .filter { i -> hasCaps(models[i].b, requiredCaps) && supportsAction(models[i].b, action) }
            .map { models[it] }
        return matching.toSeries() j action
    }

    private fun hasCaps(card: AcpModelCard, required: Series<String>): Boolean =
        (0 until required.size).all { r ->
            (0 until card.caps.size).any { card.caps[it] == required[r] }
        }

    private fun supportsAction(card: AcpModelCard, action: AcpAction): Boolean =
        action in card.caps.iterable() || card.b.b.b.a == action
}

// ═══════════════════════════════════════════
// Session lifecycle — CCEK-aligned
// ═══════════════════════════════════════════

enum class SessionState { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

@OptIn(ExperimentalUuidApi::class)
class LlmSession(
    val model: ModelEntry,
    private val authKey: String,
    val baseUrl: String,
    /** Session id — stamped on every [ModelResponseReceipt] minted from this session. */
    val sessionId: String = defaultSecureIdGenerator.generateHexId("sess", 16),
) {
    private var _state = SessionState.CREATED
    val state: SessionState get() = _state

    /** Most recent receipt produced by this session (chat/stream/embed). */
    var lastReceipt: ModelResponseReceipt? = null
        private set

    fun recordReceipt(receipt: ModelResponseReceipt) { lastReceipt = receipt }

    fun open() { if (_state == SessionState.CREATED) _state = SessionState.OPEN }
    fun activate() { if (_state == SessionState.OPEN) _state = SessionState.ACTIVE }
    fun drain() { _state = SessionState.DRAINING }
    fun close() { _state = SessionState.CLOSED }

    fun isUsable(): Boolean = _state in listOf(SessionState.OPEN, SessionState.ACTIVE)

    /** Build HTTP headers with auth */
    fun authHeaders(): Series<Join<String, String>> {
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
    private val core: ModelMuxCore,
    private val keyMux: KeyMux,
    private val configuredBaseUrls: Map<String, String>,
    /**
     * Optional quota legion metering every chat receipt. Null = standalone
     * mode (no metering). When present, each chat's receipt is applied to the
     * legion under the resolved key id, and a 429 exhausts the key — the
     * provider's word outranks the ledger.
     */
    private val quotaLegion: QuotaLegion? = null,
    /**
     * Which cache identities this mux will answer from, in order.
     *
     * Defaults to [CacheCascade.EXACT_ONLY] — byte-exact sha256, the M3
     * invariant and the only identity that can never return a wrong reply. An
     * owner opts into relaxation explicitly via [ModelMuxBuilder.cacheStrategy],
     * because every relaxed strategy trades a correctness guarantee for a hit
     * rate and that trade belongs to whoever owns the traffic.
     */
    private val cacheCascade: CacheCascade = CacheCascade.EXACT_ONLY,
) {
    private val models: Series<ModelEntry> get() = core.a
    private val router: ModelRouter get() = core.b

    /**
     * Observer sink for [ModelSelectionEvent]. Null by default. Single-writer: set once, before
     * routing. The sink is called inline on the routing path but is isolated from it — a sink
     * that throws must not fail the route it is merely observing.
     */
    var selectionObserver: ((ModelSelectionEvent) -> Unit)? = null

    /**
     * The most recent [ModelSelectionEvent.ModelSelected] this mux emitted, or null before the
     * first non-empty route. Recorded because [route] does not return the event's `requestId`
     * — a caller that wants to reconcile a downstream [ModelResponseReceipt] against the
     * selection that produced it reads the id from here.
     */
    var lastSelection: ModelSelectionEvent.ModelSelected? = null
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
     * [ModelSelectionEvent.ModelSelected.strategy].
     *
     * This is a declaration, not a derivation: nothing verifies it. It defaults to
     * `"capability"`, which is what [CapabilityRouter] — the router [ModelMuxBuilder] installs
     * — actually does, namely filter by capability and preserve catalog order. An owner that
     * ranks the route result through a [RoutingStrategy] should set this to that strategy's
     * [RoutingStrategy.strategyName]; setting it to a discipline that did not run makes the
     * event stream lie.
     */
    var strategyName: String = "capability"

    companion object {
        operator fun invoke(keyMux: KeyMux, block: ModelMuxBuilder.() -> Unit): ModelMux =
            ModelMuxBuilder(keyMux).apply(block).build()
    }

    /** Create a session for a specific model by ID */
    suspend fun session(modelId: String): Result<LlmSession> {
        val entry = (0 until models.size).firstOrNull { models[it].a == modelId }?.let { models[it] }
            ?: return Result.failure(NoSuchElementException("Model not found: $modelId"))
        val card = entry.b
        // A provider-tagged card resolves against the provider's pooled
        // credential (one key serves every model that provider hosts) before
        // falling to the per-model lookup untagged cards have always used.
        val providerTag = card.providerTag
        val authKey = providerTag?.let { keyMux.get("llm.$it.key") }
            ?: keyMux.get("llm.${card.id}.key")
            ?: keyMux.get("llm.default.key")
            ?: return Result.failure(IllegalStateException("no auth key for model: $modelId"))
        val baseUrl = providerTag?.let { keyMux.get("llm.$it.base_url") }
            ?: keyMux.get("llm.${card.id}.base_url")
            ?: keyMux.get("llm.default.base_url")
            ?: configuredBaseUrls[modelId]
            ?: "https://api.openai.com/v1"
        val session = LlmSession(entry, authKey, baseUrl)
        session.open()
        return Result.success(session)
    }

    /**
     * Resolve the key ID (binding path) a call for [card] meters under, through
     * the SAME fallback chain [session] uses to resolve the key VALUE:
     * provider tag → model id → default. Never returns the secret itself —
     * the returned string is the binding path (`llm.<provider>.key`), which is
     * the ledger identity quota metering keys on. Null = no binding resolves,
     * i.e. the same condition under which [session] refuses to open.
     */
    private suspend fun resolveKeyId(card: AcpModelCard): String? {
        val providerTag = card.providerTag
        if (providerTag != null && keyMux.get("llm.$providerTag.key") != null) return "llm.$providerTag.key"
        if (keyMux.get("llm.${card.id}.key") != null) return "llm.${card.id}.key"
        if (keyMux.get("llm.default.key") != null) return "llm.default.key"
        return null
    }

    /**
     * Route to the best model for a given action + capabilities.
     *
     * This is the selection point. The result is a ranked candidate list, and its head is the
     * selection this mux is reporting: on a non-empty route a [ModelSelectionEvent.ModelSelected]
     * naming `result.a[0]` is recorded as [lastSelection] and handed to [selectionObserver].
     * An empty route selects nothing and emits nothing.
     *
     * A caller free to ignore the ranking and call [chat] with some other candidate will find
     * the event stream disagreeing with what it did; the event describes the head of the
     * ranking, which is the only selection this method makes.
     */
    fun route(action: AcpAction, vararg requiredCaps: String): RouteResult {
        val result = router.route(models, action, requiredCaps.toSeries())
        if (result.a.size > 0) {
            val chosen = result.a[0]
            val event = ModelSelectionEvent.ModelSelected(
                // A card carries one identity; provider and model coincide until cards split them.
                provider = chosen.b.id,
                model = chosen.a,
                strategy = strategyName,
                requestId = defaultSecureIdGenerator.generateHexId("req", 8),
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
        messages: Series<AcpMessage>,
        tools: Series<AcpTool> = 0 j { error("no tools") },
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
    ): Result<AcpResponse> {
        val receiptAssessment = assessmentId ?: contextId
        if (modelId.isEmpty()) return Result.failure(IllegalArgumentException("modelId must be non-empty"))
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) return Result.failure(sessionResult.exceptionOrNull()!!)
        val session = sessionResult.getOrThrow()
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Meter under the resolved key ID (the binding path), not the key VALUE
        // — the value is the secret, and it bypassed the providerTag chain
        // session() honours, so tagged providers metered under null.
        val keyId = resolveKeyId(session.model.b)
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
        try {
            val card = session.model.b
            val meta: AcpMeta = card.wireName j ("chat" j session.authHeaders())
            val body: AcpRequestBody = messages j tools
            val req: AcpRequest = meta j body

            val json = AcpCodec.encodeRequest(req, maxTokens = maxTokens, temperature = temperature)
            // Content-address the canonical request bytes. String.hashCode() is a
            // 32-bit truncation: two distinct requests colliding on it returned each
            // other's cached payload verbatim.
            //
            // The receipt is ALWAYS attributed to the exact identity, whichever
            // strategy produced the hit: a ledger keyed by a relaxed bucket could
            // not be reconciled against the bytes actually sent.
            val requestHash = cacheCascade.primary(json)
            // Every identity this mux is willing to answer from, exact first.
            // EXACT_ONLY (the default) makes this a one-element list and the
            // behaviour byte-identical to before strategies existed.
            val cacheIds = cacheCascade.identities(json)

            if (reactor != null) {
                // First hit wins, and exact is always tried first — the cheapest
                // correct answer outranks the most permissive one.
                var hit: CacheLookup.Hit? = null
                for ((_, id) in cacheIds) {
                    val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000)
                    if (lookup is CacheLookup.Hit) { hit = lookup; break }
                }
                if (hit != null) {
                    cachedHit = true
                    httpStatus = 200
                    val cached = AcpCodec.parseResponse(hit.entry.payload)
                    inputTokens = cached.b.a
                    outputTokens = cached.b.b
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

            val htx = currentCoroutineContext()[HtxKey] ?: throw IllegalStateException("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/chat/completions"
            val htxHeaders = htxHeaders(*meta.b.b.toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val resp = htx.request(htxReq)
            val respBody = resp.body.toArray().decodeToString()
            httpStatus = resp.status

            if (reactor != null) {
                reactor.recordProviderHealth(
                    provider = card.id,
                    success = resp.status in 200..299,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0
                )
            }

            if (resp.status !in 200..299) {
                // Record the failure receipt BEFORE returning: the finally-block legion
                // metering reads session.lastReceipt, and a 429 that left no receipt
                // never exhausted its key — the provider's "no" fell on the floor.
                session.recordReceipt(
                    ModelResponseReceipt.mint(
                        modelId = modelId, providerId = card.id, requestHash = requestHash,
                        action = "chat", httpStatus = httpStatus,
                        latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                        assessmentId = receiptAssessment, sessionId = session.sessionId,
                        error = IllegalStateException("HTTP ${resp.status}"),
                    )
                )
                // 429 → rotate: the current credential is rate-limited; the next
                // session() call must resolve a different key from the persist pool.
                // rotate() cycles through PersistSource entries and invalidates
                // the CachedKeySource so the fresh value is read, not the stale one.
                if (resp.status == 429 && keyId != null) {
                    runCatching { keyMux.rotate(keyId) }
                        .onFailure { println("[MODELMUX] rotate($keyId) failed: ${it.message}") }
                }
                return Result.failure(IllegalStateException("ModelMux chat failed with HTTP ${resp.status}: ${respBody.take(500)}"))
            }

            val parsed = AcpCodec.parseResponse(respBody)
            inputTokens = parsed.b.a
            outputTokens = parsed.b.b
            // Provider-measured cache economics only — never synthesized from inputTokens.
            val cacheUse = providerCacheUsage(respBody)

            if (reactor != null) {
                // Warm EVERY identity from this one paid call: the exact entry, plus
                // each relaxed bucket. A later request differing only in formatting
                // then hits without a second purchase — which is the whole return on
                // running a cascade at all.
                for ((_, id) in cacheIds) {
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
                    modelId = modelId, providerId = modelId, requestHash = "0",
                    action = "chat", httpStatus = httpStatus,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0,
                    assessmentId = receiptAssessment, sessionId = session.sessionId,
                    error = t,
                )
            )
            return Result.failure(t)
        } finally {
            session.drain(); session.close()
            // Quota legion metering: only real (non-cached) calls consume provider
            // quota. A 429 receipt exhausts the key inside applyReceipt.
            session.lastReceipt?.let { receipt ->
                lastReceipt = receipt
                if (keyId != null && !receipt.cachedHit) {
                    quotaLegion?.applyReceipt(
                        keyId, receipt.providerId, receipt,
                        kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                    )
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
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) throw sessionResult.exceptionOrNull()!!
        val session = sessionResult.getOrThrow()
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Lease identity is the binding PATH (llm.<provider>.key), never the secret
        // value — keyMux.get(...) returns the credential, which matched no lease and
        // leaked it. Same providerTag fallback chain session()/chat() honour.
        val keyId = resolveKeyId(session.model.b)
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
            val meta: AcpMeta = card.wireName j ("stream" j session.authHeaders())
            val body: AcpRequestBody = messages j tools
            val req: AcpRequest = meta j body

            val json = AcpCodec.encodeRequest(req)
            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/chat/completions"
            val htxHeaders = htxHeaders(*meta.b.b.toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val resp = htx.request(htxReq)
            val text = resp.body.toArray().decodeToString()
            var emitted = false
            text.lineSequence().forEach { line ->
                if (line.startsWith("data:")) {
                    AcpCodec.parseChunk(line)?.let { emit(it); emitted = true }
                }
            }
            if (!emitted && text.isNotBlank()) {
                val parsed = AcpCodec.parseResponse(text)
                emit(parsed.a j parsed.b)
            }
        } finally {
            session.drain(); session.close()
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
        val sessionResult = session(modelId)
        if (sessionResult.isFailure) throw sessionResult.exceptionOrNull()!!
        val session = sessionResult.getOrThrow()
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        // Lease identity is the binding PATH, never the secret value (stream() fix,
        // same defect class): keyMux.get returns the credential, which matched no
        // lease. providerTag fallback chain via resolveKeyId.
        val keyId = resolveKeyId(session.model.b)
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
            val cacheIds = cacheCascade.identities(json)
            if (reactor != null) {
                for ((_, id) in cacheIds) {
                    val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000)
                    if (lookup is CacheLookup.Hit) {
                        return parseEmbeddings(lookup.entry.payload, texts)
                    }
                }
            }

            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/embeddings"
            val htxHeaders = htxHeaders(*meta.b.b.toArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val t0 = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            val resp = htx.request(htxReq)
            val respBody = resp.body.toArray().decodeToString()

            if (reactor != null) {
                reactor.recordProviderHealth(
                    provider = card.id,
                    success = resp.status in 200..299,
                    latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - t0
                )
                if (resp.status in 200..299) {
                    for ((_, id) in cacheIds) {
                        reactor.cacheApiCall(provider = card.id, modelId = modelId, requestHash = id, ttlMs = 3600_000, payload = respBody)
                    }
                }
            }
            if (resp.status !in 200..299) {
                throw IllegalStateException("ModelMux embed failed with HTTP ${resp.status}: ${respBody.take(500)}")
            }
            return parseEmbeddings(respBody, texts)
        } finally {
            session.drain(); session.close()
            if (reactor != null && keyId != null) {
                val leasedTo = reactor.flowState.value.leases.firstOrNull { it.keyId == keyId }?.leasedTo
                if (leasedTo != null) {
                    reactor.releaseLease(keyId, leasedTo)
                }
            }
        }
    }

    /** List available model cards, optionally filtered by capability */
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
    ): ModelMuxBuilder = apply {
        val capSeries = caps.toSeries()
        val action = if ("chat" in caps) "chat" else if ("embed" in caps) "embed" else "complete"
        val tags = buildList {
            if (provider != null) add("provider" j provider)
            if (wireModel != null && wireModel != id) add("wire_model" j wireModel)
        }
        val headers: Series<Join<String, String>> = if (tags.isNotEmpty()) {
            tags.toSeries()
        } else {
            0 j { _: Int -> error("no headers") }
        }
        val meta: AcpMeta = version j (action j headers)
        val card: AcpModelCard = id j (capSeries j meta)
        models.add(id j card)
        if (baseUrl != null) {
            pendingUrls[id] = baseUrl
        }
    }

    private val pendingUrls = mutableMapOf<String, String>()

    internal fun build(): ModelMux {
        val core: ModelMuxCore = models.toSeries() j CapabilityRouter
        return ModelMux(core, keyMux, pendingUrls.toMap(), quotaLegion, cascade)
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
