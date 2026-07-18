package modelmux

import keymux.*
import modelmux.acp.*
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.CacheLookup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

class LlmSession(
    val model: ModelEntry,
    private val authKey: String,
    val baseUrl: String
) {
    private var _state = SessionState.CREATED
    val state: SessionState get() = _state

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
    private val keyMux: KeyMux
) {
    private val models: Series<ModelEntry> get() = core.a
    private val router: ModelRouter get() = core.b

    companion object {
        operator fun invoke(keyMux: KeyMux, block: ModelMuxBuilder.() -> Unit): ModelMux =
            ModelMuxBuilder(keyMux).apply(block).build()
    }

    /** Create a session for a specific model by ID */
    suspend fun session(modelId: String): LlmSession {
        val entry = (0 until models.size).firstOrNull { models[it].a == modelId }?.let { models[it] }
            ?: error("model not found: $modelId")
        val card = entry.b
        val authKey = keyMux.get("llm.${card.id}.key")
            ?: keyMux.get("llm.default.key")
            ?: error("no auth key for model: $modelId")
        val baseUrl = keyMux.get("llm.${card.id}.base_url")
            ?: keyMux.get("llm.default.base_url")
            ?: "https://api.openai.com/v1"
        val session = LlmSession(entry, authKey, baseUrl)
        session.open()
        return session
    }

    /** Route to the best model for a given action + capabilities */
    fun route(action: AcpAction, vararg requiredCaps: String): RouteResult =
        router.route(models, action, requiredCaps.toList().toSeries())

    /** Non-streaming chat completion */
    suspend fun chat(
        modelId: String,
        messages: Series<AcpMessage>,
        tools: Series<AcpTool> = 0 j { error("no tools") }
    ): AcpResponse {
        val session = session(modelId)
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        val keyId = keyMux.get("llm.$modelId.key")
        try {
            val card = models.let { ms -> (0 until ms.size).first { ms[it].a == modelId }.let { ms[it] } }.b
            val meta: AcpMeta = card.id j ("chat" j session.authHeaders())
            val body: AcpRequestBody = messages j tools
            val req: AcpRequest = meta j body

            val json = AcpCodec.encodeRequest(req)
            val requestHash = json.hashCode().toString()

            if (reactor != null) {
                val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = requestHash, ttlMs = 3600_000)
                if (lookup is CacheLookup.Hit) {
                    return AcpCodec.parseResponse(lookup.entry.payload)
                }
            }

            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/chat/completions"
            val htxHeaders = htxHeaders(*meta.b.b.toList().map { it.a j it.b }.toTypedArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val resp = htx.request(htxReq)
            val respBody = resp.body.toArray().decodeToString()

            if (reactor != null) {
                reactor.cacheApiCall(provider = card.id, modelId = modelId, requestHash = requestHash, ttlMs = 3600_000, payload = respBody)
            }
            return AcpCodec.parseResponse(respBody)
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

    /** Streaming chat completion via SSE */
    fun stream(
        modelId: String,
        messages: Series<AcpMessage>,
        tools: Series<AcpTool> = 0 j { error("no tools") }
    ): Flow<AcpChunk> = flow {
        val session = session(modelId)
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        val keyId = keyMux.get("llm.$modelId.key")
        try {
            val card = models.let { ms -> (0 until ms.size).first { ms[it].a == modelId }.let { ms[it] } }.b
            val meta: AcpMeta = card.id j ("stream" j session.authHeaders())
            val body: AcpRequestBody = messages j tools
            val req: AcpRequest = meta j body

            val json = AcpCodec.encodeRequest(req)
            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/chat/completions"
            val htxHeaders = htxHeaders(*meta.b.b.toList().map { it.a j it.b }.toTypedArray())
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
        val session = session(modelId)
        session.activate()
        val reactor = currentCoroutineContext()[MuxReactorElement.Key]
        val keyId = keyMux.get("llm.$modelId.key")
        try {
            val card = models.let { ms -> (0 until ms.size).first { ms[it].a == modelId }.let { ms[it] } }.b
            val meta: AcpMeta = card.id j ("embed" j session.authHeaders())
            val textsJson = (0 until texts.size).joinToString(",") { jsonStr(texts[it]) }
            val json = "{\"model\":\"${card.id}\",\"input\":[$textsJson]}"
            
            val requestHash = json.hashCode().toString()
            if (reactor != null) {
                val lookup = reactor.lookupApiCall(provider = card.id, modelId = modelId, requestHash = requestHash, ttlMs = 3600_000)
                if (lookup is CacheLookup.Hit) {
                    return parseEmbeddings(lookup.entry.payload, texts)
                }
            }

            val htx = currentCoroutineContext()[HtxKey] ?: error("No HtxKey found in coroutine context")
            val url = "${session.baseUrl}/embeddings"
            val htxHeaders = htxHeaders(*meta.b.b.toList().map { it.a j it.b }.toTypedArray())
            val htxReq = parseHtxRequest(
                url = url,
                method = HtxMethod.POST,
                body = ByteSeries(json.encodeToByteArray())
            ).copy(headers = htxHeaders)

            val resp = htx.request(htxReq)
            val respBody = resp.body.toArray().decodeToString()

            if (reactor != null) {
                reactor.cacheApiCall(provider = card.id, modelId = modelId, requestHash = requestHash, ttlMs = 3600_000, payload = respBody)
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
        val capSeries = cap.toList().toSeries()
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
}

class ModelMuxBuilder(private val keyMux: KeyMux) {
    private val models = mutableListOf<ModelEntry>()

    fun model(
        id: String,
        caps: Set<String>,
        baseUrl: String? = null,
        version: String = "1.0"
    ): ModelMuxBuilder = apply {
        val capSeries = caps.toList().toSeries()
        val action = if ("chat" in caps) "chat" else if ("embed" in caps) "embed" else "complete"
        val meta: AcpMeta = version j (action j (0 j { error("no headers") }))
        val card: AcpModelCard = id j (capSeries j meta)
        models.add(id j card)
        if (baseUrl != null) {
            pendingUrls[id] = baseUrl
        }
    }

    private val pendingUrls = mutableMapOf<String, String>()

    internal fun build(): ModelMux {
        val core: ModelMuxCore = models.toSeries() j CapabilityRouter
        return ModelMux(core, keyMux)
    }
}
