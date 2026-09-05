package borg.trikeshed.lcnc

import keymux.CouchKeyStore
import keymux.KeyMux
import modelmux.ModelMux
import modelmux.acp.AcpMessage
import modelmux.acp.providerTag
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxHeader
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.htxHeaders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * BrainMuxNodes — BrainClient decomposed as LCNC graph nodes.
 *
 *   - keys.status → KeyMux roster (which providers have keys)
 *   - mux.models → ModelMux model list (routable models + caps + provider)
 *   - mux.meta → modelmux presence: strategy, last selection, quota standings
 *   - credential.enter → CouchKeyStore (CouchDB persistence)
 *   - prompt.chat → ModelMux.chat() (receipt-tracked, quota-metered)
 *   - result.confirm → HTML confirmation dialog (OK/ERROR)
 *   - display → passthrough sink (shared vocabulary)
 *   - note → passthrough no-op
 */
object BrainMuxNodes {

    fun registry(
        keyMux: KeyMux? = null,
        modelMux: ModelMux? = null,
        /**
         * LIVE mux: consulted on every call instead of [modelMux]. The daemon
         * hands one that rebuilds its cards when Hermes' state.db changes, so a
         * `/model` switch in the Hermes CLI is in the picklist on its next open.
         */
        modelMuxProvider: (suspend () -> ModelMux?)? = null,
        credStore: CouchKeyStore? = null,
        /**
         * Coroutine context the chat rides under. MUST carry [HtxElement] under
         * [HtxKey] and the MuxReactorElement — [ModelMux.chat] resolves the HTX
         * client and the reactor (receipt/cache/lease metering) from the caller's
         * context. The CCEK assembly scope rides the reactor but NOT the HTX
         * element, so without this the ModelMux path threw
         * "No HtxKey found in coroutine context" on every call.
         */
        chatContext: CoroutineContext? = null,
    ): Map<String, LcncNodeRunner> = registryWith({ modelMuxProvider?.invoke() ?: modelMux }, keyMux, credStore, chatContext)

    private fun registryWith(
        mux: suspend () -> ModelMux?,
        keyMux: KeyMux?,
        credStore: CouchKeyStore?,
        chatContext: CoroutineContext?,
    ): Map<String, LcncNodeRunner> = mapOf(

        // ── keys.status ─────────────────────────────────────────────
        // Queries the daemon's KeyMux for each HarnessRegistry provider.
        // Returns a roster: [{provider, keyPresent, baseUrl}].
        // NOTE: KeyMux.get() is suspend and must run in the caller's
        // coroutine context — wrapping in withContext(Dispatchers.Default)
        // drops MuxReactorElement, defeating lease/quota tracking.
        "keys.status" to LcncNodeRunner { _, _ ->
            if (keyMux == null) return@LcncNodeRunner mapOf("roster" to emptyList<Any>())
            val roster = ArrayList<Map<String, Any?>>()
            for (i in 0 until keymux.HarnessRegistry.providers.size) {
                val p = keymux.HarnessRegistry.providers[i]
                val keyPresent = runCatching {
                    keyMux.get("llm.${p.id}.key")
                }.getOrNull()?.isNotBlank() == true
                val baseUrl = runCatching {
                    keyMux.get("llm.${p.id}.base_url")
                }.getOrNull() ?: p.defaultBaseUrl ?: ""
                roster.add(linkedMapOf(
                    "provider" to p.id,
                    "keyPresent" to keyPresent,
                    "baseUrl" to baseUrl,
                ))
            }
            // The two lists a person actually asks for: which providers can
            // answer with nothing typed, and which cannot.
            mapOf(
                "roster" to roster,
                "have" to roster.filter { it["keyPresent"] == true }.map { it["provider"] },
                "missing" to roster.filter { it["keyPresent"] != true }.map { it["provider"] },
            )
        },

        // ── mux.models ──────────────────────────────────────────────
        // Queries the daemon's ModelMux for available models.
        // Returns: [{id, caps, provider}] — provider lets the browser
        // map a model to its KeyMux binding for credential selection.
        "mux.models" to LcncNodeRunner { _, _ ->
            val modelMux = mux()
            if (modelMux == null) return@LcncNodeRunner mapOf("models" to emptyList<Any>())
            val cards = modelMux.listModels()
            val models = ArrayList<Map<String, Any?>>()
            for (i in 0 until cards.size) {
                val card = cards[i] // AcpModelCard = Join<String, Join<Series<AcpCapability>, AcpMeta>>
                val id = card.a
                val capsList = ArrayList<String>()
                val caps = card.b.a
                for (c in 0 until caps.size) capsList += caps[c]
                // providerTag is stamped as a header by ModelMuxBuilder —
                // the card's meta carries the action+headers Join; extract
                // the "provider" header value when present.
                val providerTag = runCatching {
                    val headers = card.b.b.b // AcpMeta.b = action j headers
                    val hdrs = headers.b // Series<Join<String, String>>
                    var found: String? = null
                    for (h in 0 until hdrs.size) {
                        if (hdrs[h].a == "provider") { found = hdrs[h].b; break }
                    }
                    found
                }.getOrNull()
                models.add(linkedMapOf(
                    "id" to id,
                    "caps" to capsList,
                    "provider" to providerTag,
                ))
            }
            mapOf("models" to models)
        },

        // ── mux.meta ────────────────────────────────────────────────
        // Modelmux presence: the ranking discipline in force, the most recent
        // selection this mux made, and the quota legion's standings (usable-
        // first). Empty lists/absent fields when the reactor ledger is not yet
        // warm — meta is a live projection, never a guess.
        "mux.meta" to LcncNodeRunner { _, _ ->
            val modelMux = mux()
            if (modelMux == null) return@LcncNodeRunner mapOf("meta" to emptyList<Any>())
            // lastSelection is written by route() — capability ranking. A
            // prompt.chat names its model outright and never routes, so after
            // the one call a person just made this read `selection: null`,
            // which looks broken. The receipt is the record of that call.
            val answered = modelMux.lastReceipt?.let { r ->
                // The receipt's providerId is the CARD id; the provider a person
                // means is the card's provider tag (zai, nvidia, …).
                val providerTag = runCatching {
                    val cards = modelMux.listModels()
                    var tag: String? = null
                    for (i in 0 until cards.size) if (cards[i].a == r.providerId) { tag = cards[i].providerTag; break }
                    tag
                }.getOrNull()
                linkedMapOf<String, Any?>(
                    "model" to r.modelId,
                    "provider" to (providerTag ?: r.providerId),
                    "ok" to (r.httpStatus in 200..299),
                    "httpStatus" to r.httpStatus,
                    "latencyMs" to r.latencyMs,
                    "inputTokens" to r.inputTokens,
                    "outputTokens" to r.outputTokens,
                    "fromCache" to r.cachedHit,
                )
            }
            val meta = linkedMapOf<String, Any?>(
                "lastAnswer" to answered,
                "strategy" to modelMux.strategyName,
            )
            // Only when a route() ranking happened: a null here read as "broken".
            modelMux.lastSelection?.let { sel ->
                meta["selection"] = linkedMapOf<String, Any?>(
                    "provider" to sel.provider,
                    "model" to sel.model,
                    "strategy" to sel.strategy,
                    "requestId" to sel.requestId,
                    "at" to sel.at,
                )
            }
            // Standings live in the MuxReactor; without its context the roster
            // is unknowable and the list is empty. Ride chatContext like chat does.
            val standings = runCatching {
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                if (chatContext != null) withContext(chatContext) { modelMux.quotaStandings(now) }
                else modelMux.quotaStandings(now)
            }.getOrDefault(emptyList())
            meta["quota"] = standings.map { s ->
                linkedMapOf<String, Any?>(
                    "keyId" to s.keyId,
                    "provider" to s.provider,
                    "limit" to s.limit,
                    "spent" to s.spent,
                    "remaining" to s.remaining,
                    "exhausted" to s.exhausted,
                    "utilization" to s.utilization,
                )
            }
            mapOf("meta" to meta, "lastAnswer" to answered)
        },

        // ── credential.enter ────────────────────────────────────────
        // Output carries manualKey/manualUrl so the panel's prefill lane
        // can read the manual entry back without re-typing.
        "credential.enter" to LcncNodeRunner { node, _ ->
            val keyType = node.params["key_type"] ?: ""
            val url = node.params["url"] ?: ""
            val apiType = node.params["api_type"] ?: "openai"
            val key = node.params["key"] ?: ""

            if (credStore != null && keyType.isNotBlank() && key.isNotBlank()) {
                credStore.storeCredential(keyType, key, url, apiType)
            }

            mapOf("credential" to linkedMapOf(
                "key_type" to keyType,
                "url" to url,
                "api_type" to apiType,
                "manualKey" to key,
                "manualUrl" to url,
            ))
        },

        // ── prompt.chat ─────────────────────────────────────────────
        // Routes through ModelMux.chat() when the model is registered —
        // gains MuxReactor receipt tracking, content-addressed cache, and
        // provider health recording. Falls back to direct HTX for manual
        // key+url entries that don't match any registered model.
        "prompt.chat" to LcncNodeRunner { node, inputs ->
            val modelMux = mux()
            val prompt = (inputs["prompt"] as? String)
                ?: node.params["prompt"]?.takeIf { it.isNotBlank() }
                ?: ""

            var model = node.params["model"] ?: ""
            if (model.isBlank()) {
                // Ensure using last hermes model as a crutch example for keymux and modelmux to hit the ground running
                val firstCardId = modelMux?.listModels()?.let { if (it.size > 0) it[0].a else null }
                model = modelMux?.lastReceipt?.modelId?.takeIf { it.isNotBlank() }
                    ?: firstCardId?.takeIf { it.isNotBlank() }
                    ?: "nousresearch/hermes-3-llama-3.1-405b"
            }

            // Prefill sentinel: the env-first default — resolve through the
            // ModelMux/KeyMux chain (env → dotenv → harness stores) and only
            // fall to manual fields when no provider key resolves.
            val prefill = node.params["prefill"] ?: "(none — use env/harness keys)"
            var apiKey = node.params["key"] ?: ""
            var baseUrl = node.params["url"] ?: ""

            if (prefill.isNotBlank() && !prefill.startsWith("(none") && credStore != null) {
                val stored = credStore.readCredential(prefill)
                if (stored != null) {
                    if (apiKey.isBlank()) apiKey = stored["key"].orEmpty()
                    if (baseUrl.isBlank()) baseUrl = stored["base_url"].orEmpty()
                }
            }

            if (prompt.isBlank()) {
                return@LcncNodeRunner mapOf(
                    "content" to "",
                    "model" to model,
                    "ok" to false,
                    "error" to "no prompt wired or in params",
                )
            }


            val maxTokens = (node.params["maxTokens"] ?: "256").toIntOrNull() ?: 256
            val temperature = (node.params["temperature"] ?: "0.2").toDoubleOrNull() ?: 0.2

            // Primary path: route through ModelMux when available —
            // receipt-tracked, quota-metered, cache-backed. Rides [chatContext]
            // because ModelMux.chat resolves HtxKey + MuxReactorElement from the
            // caller's context, and the CCEK assembly scope lacks HtxKey.
            if (modelMux != null) {
                val acpMessages: Series<AcpMessage> = 1 j { _: Int -> "user" j prompt }
                // Delta 2026-09-05 (receipt timing): wall clock around the mux call, measured
                // HERE — not read back from lastReceipt, which a cache hit or a failed call
                // may leave stale or absent. The kanban claim receipt copies it as-is.
                val startedAtMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                val result = try {
                    val chatCt = chatContext ?: currentCoroutineContext()
                    withContext(chatCt) {
                        modelMux.chat(
                            modelId = model,
                            messages = acpMessages,
                            maxTokens = maxTokens,
                            temperature = temperature,
                            // Stamped on the mux receipt's assessmentId slot, so THIS call's
                            // receipt can be told from a concurrent call's below.
                            contextId = node.id,
                        ).getOrThrow()
                    }.let { Result.success(it) }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                val latencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - startedAtMs
                // `lastReceipt` is ONE var on the mux, overwritten by every call's finally;
                // under concurrent claims it can be another call's. Only a receipt stamped
                // with this node's id is this call's: `cachedHit` is reported from it, and
                // omitted (not guessed false) when the receipt cannot be attributed.
                val attributed = modelMux.lastReceipt?.takeIf { it.assessmentId == node.id }
                val cachedHitKnown: Boolean? = attributed?.cachedHit
                val cachedHit = cachedHitKnown == true
                val cachedHitEntry: Map<String, Any?> = if (cachedHitKnown != null) mapOf("cachedHit" to cachedHitKnown) else emptyMap()
                return@LcncNodeRunner result.fold(
                    onSuccess = { response ->
                        val content = response.a
                        val inTok = response.b.a
                        val outTok = response.b.b
                        // An empty answer is NOT a success. A 2xx that carries no
                        // content rendered as a green OK with a blank body, which is
                        // the single worst state this panel can be in: the operator
                        // cannot tell a mute model from a broken parser from a wrong
                        // key, because all three look like "it worked". The token
                        // counts separate them — they come from the provider's own
                        // usage block, so they are evidence, not inference.
                        // Delta 2026-09-05: the counts are also OUTPUT now (inputTokens /
                        // outputTokens), with latencyMs and cachedHit, on the ok and the
                        // error map alike — the claim receipt and the board page show them.
                        if (content.isBlank()) {
                            mapOf(
                                "content" to "",
                                "model" to model,
                                "ok" to false,
                                "error" to if (outTok > 0) {
                                    "provider billed $outTok completion tokens but returned no content " +
                                        "(in=$inTok) — the text is somewhere this parser did not look: " +
                                        "a reasoning-only reply, a refusal field, or a non-OpenAI response shape"
                                } else {
                                    "provider returned no content and billed 0 completion tokens (in=$inTok) — " +
                                        "the model emitted nothing: raise maxTokens, or the request was refused upstream"
                                },
                                "cached" to cachedHit,
                                "latencyMs" to latencyMs,
                                "inputTokens" to inTok,
                                "outputTokens" to outTok,
                            ) + cachedHitEntry
                        } else {
                            mapOf(
                                "content" to content, "model" to model, "ok" to true, "error" to "",
                                "cached" to cachedHit,
                                "latencyMs" to latencyMs,
                                "inputTokens" to inTok,
                                "outputTokens" to outTok,
                            ) + cachedHitEntry
                        }
                    },
                    onFailure = { t ->
                        // No usage block reached us: the token counts are NOT reported, so the
                        // keys are absent — never a 0 a receipt could mistake for a count. The
                        // latency is still real — it is what the caller waited.
                        mapOf(
                            "content" to "", "model" to model, "ok" to false, "error" to (t.message ?: "unknown error"),
                            "cached" to cachedHit,
                            "latencyMs" to latencyMs,
                        ) + cachedHitEntry
                    },
                )
            }

            // Fallback: direct HTX (standalone mode, no modelMux wired).
            if (apiKey.isBlank()) {
                return@LcncNodeRunner mapOf(
                    "content" to "",
                    "model" to model,
                    "ok" to false,
                    "error" to "no API key — enter in the key field or select a prefill provider",
                )
            }
            val extraHeaders = parseHeaders(node.params["headers"] ?: "")
            val directStartedAtMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            val chatResult = if (chatContext != null) {
                withContext(chatContext) {
                    directHtxChat(baseUrl, apiKey, model, prompt, maxTokens, temperature, extraHeaders)
                }
            } else {
                directHtxChat(baseUrl, apiKey, model, prompt, maxTokens, temperature, extraHeaders)
            }
            val directLatencyMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - directStartedAtMs
            // Same output shape as the mux path; this path has no cache (cachedHit is an honest
            // false) and reads no usage block, so the token keys are absent = not reported.
            chatResult.fold(
                onSuccess = { content ->
                    mapOf(
                        "content" to content, "model" to model, "ok" to true, "error" to "",
                        "cached" to false, "cachedHit" to false, "latencyMs" to directLatencyMs,
                    )
                },
                onFailure = { t ->
                    mapOf(
                        "content" to "", "model" to model, "ok" to false, "error" to (t.message ?: "unknown error"),
                        "cached" to false, "cachedHit" to false, "latencyMs" to directLatencyMs,
                    )
                },
            )
        },

        // ── note: passthrough, no-op ──────────────────────────────
        "note" to LcncNodeRunner { _, _ -> emptyMap() },

        // ── result.confirm ──────────────────────────────────────────
        // ── credential.list ─────────────────────────────────────────
        // The prefill picklist's HONEST source: the credentials credential.enter
        // actually stored (CouchKeyStore), led by the "none" entry — blank is
        // not offerable through a live list, and prompt.chat already reads a
        // leading "(none" as "let the mux key chain answer".
        "credential.list" to LcncNodeRunner { _, _ ->
            val stored = credStore?.let { runCatching { it.listProviders() }.getOrDefault(emptyList()) }.orEmpty()
            mapOf("names" to listOf(PREFILL_NONE) + stored, "stored" to stored)
        },

        "result.confirm" to LcncNodeRunner { _, inputs ->
            val content = (inputs["content"] as? String).orEmpty()
            val cached = (inputs["cached"] ?: inputs["cached?"]).let { it == true || it == "true" }
            val error = ((inputs["error"] ?: inputs["error?"]) as? String).orEmpty()
            // A plain text producer has completed successfully. Producers that
            // expose an explicit verdict may override that default; an error
            // payload is also a failure even when no boolean is supplied.
            val explicitOk = inputs["ok"] ?: inputs["ok?"]
            val ok = when (explicitOk) {
                null -> error.isEmpty()
                true, "true" -> true
                else -> false
            }

            val html = if (ok) {
                """<div style="border:2px solid #22c55e;border-radius:8px;padding:16px;margin:8px;background:#f0fdf4">
<h3 style="color:#16a34a;margin:0 0 8px">✓ OK</h3>
<pre style="white-space:pre-wrap;font-family:monospace;font-size:13px">${escHtml(content)}</pre>
${if (cached) """<div style="color:#6b7280;font-size:12px;margin-top:6px">served from cache — the same question was asked before; change the prompt for a fresh answer</div>""" else ""}
</div>"""
            } else {
                """<div style="border:2px solid #ef4444;border-radius:8px;padding:16px;margin:8px;background:#fef2f2">
<h3 style="color:#dc2626;margin:0 0 8px">✗ ERROR</h3>
<pre style="white-space:pre-wrap;font-family:monospace;font-size:13px">${escHtml(error)}</pre>
</div>"""
            }

            mapOf("x" to html)
        },
    )

    fun servedTypes(): Set<String> = setOf(
        "keys.status", "mux.models", "mux.meta",
        "credential.enter", "credential.list", "prompt.chat", "result.confirm", "note",
    )

    /** The prefill option meaning "no stored credential — the mux key chain answers". */
    const val PREFILL_NONE = "(none — env/harness keys)"

    // ── Direct HTX fallback for unregistered models ─────────────────
    // Used when no ModelMux is wired (standalone mode) or the model ID
    // doesn't match any registered card. Requires HtxKey in context.
    private suspend fun directHtxChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double,
        headers: List<Pair<String, String>>,
    ): Result<String> = try {
        val bodyMap = linkedMapOf<String, Any?>(
            "model" to model,
            "max_tokens" to maxTokens,
            "temperature" to temperature,
            "messages" to listOf(linkedMapOf("role" to "user", "content" to prompt)),
        )
        val json = JsonSupport.stringify(bodyMap)
        fun hdr(k: String, v: String): HtxHeader =
            object : HtxHeader { override val a = k; override val b = v }
        val allHdrs = arrayOf(
            hdr("Authorization", "Bearer $apiKey"),
            hdr("Content-Type", "application/json"),
        ) + headers.filter { it.first.isNotBlank() }.map { (k, v) -> hdr(k, v) }.toTypedArray()
        val htxReq = parseHtxRequest(
            url = "$baseUrl/chat/completions",
            method = HtxMethod.POST,
            body = ByteSeries(json.encodeToByteArray()),
        ).copy(headers = htxHeaders(*allHdrs))
        val htx = currentCoroutineContext()[HtxKey]
            ?: error("No HtxKey found in coroutine context for directHtxChat")
        val resp = htx.requestResult(htxReq).getOrThrow()
        val respBody = resp.body.toArray().decodeToString()
        val parsed = JsonSupport.parse(respBody) as? Map<*, *>
            ?: error("non-JSON response: ${respBody.take(200)}")
        val choices = parsed["choices"] as? List<*>
            ?: error("no choices in response")
        val first = choices.firstOrNull() as? Map<*, *>
            ?: error("empty choices")
        val message = first["message"] as? Map<*, *>
            ?: error("no message in choice")
        (message["content"] as? String)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no content in message"))
    } catch (t: kotlinx.coroutines.CancellationException) {
        throw t
    } catch (t: Throwable) {
        Result.failure(t)
    }

    private fun parseHeaders(json: String): List<Pair<String, String>> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val parsed = JsonSupport.parse(json)
            @Suppress("UNCHECKED_CAST")
            val arr = parsed as? List<Map<String, Any?>> ?: return emptyList()
            arr.mapNotNull { row ->
                val name = row["name"] as? String ?: return@mapNotNull null
                val value = row["value"] as? String ?: ""
                if (name.isNotBlank()) name to value else null
            }
        }.getOrDefault(emptyList())
    }

    private fun escHtml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}
