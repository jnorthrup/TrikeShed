package borg.trikeshed.lcnc

import keymux.CouchKeyStore
import keymux.KeyMux
import modelmux.ModelMux
import modelmux.acp.AcpMessage
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
            mapOf("roster" to roster)
        },

        // ── mux.models ──────────────────────────────────────────────
        // Queries the daemon's ModelMux for available models.
        // Returns: [{id, caps, provider}] — provider lets the browser
        // map a model to its KeyMux binding for credential selection.
        "mux.models" to LcncNodeRunner { _, _ ->
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
            if (modelMux == null) return@LcncNodeRunner mapOf("meta" to emptyList<Any>())
            val meta = linkedMapOf<String, Any?>(
                "strategy" to modelMux.strategyName,
                "selection" to modelMux.lastSelection?.let { sel ->
                    linkedMapOf<String, Any?>(
                        "provider" to sel.provider,
                        "model" to sel.model,
                        "strategy" to sel.strategy,
                        "requestId" to sel.requestId,
                        "at" to sel.at,
                    )
                },
            )
            val standings = runCatching {
                modelMux.quotaStandings(kotlinx.datetime.Clock.System.now().toEpochMilliseconds())
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
            mapOf("meta" to meta)
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
            val prompt = (inputs["prompt"] as? String)
                ?: node.params["prompt"]?.takeIf { it.isNotBlank() }
                ?: ""

            val model = node.params["model"] ?: ""

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

            if (model.isBlank()) {
                return@LcncNodeRunner mapOf(
                    "content" to "",
                    "model" to model,
                    "ok" to false,
                    "error" to "no model specified",
                )
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
                val result = try {
                    val chatCt = chatContext ?: currentCoroutineContext()
                    withContext(chatCt) {
                        modelMux.chat(
                            modelId = model,
                            messages = acpMessages,
                            maxTokens = maxTokens,
                            temperature = temperature,
                        ).getOrThrow()
                    }.let { Result.success(it) }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                return@LcncNodeRunner result.fold(
                    onSuccess = { response ->
                        mapOf("content" to response.a, "model" to model, "ok" to true, "error" to "")
                    },
                    onFailure = { t ->
                        mapOf("content" to "", "model" to model, "ok" to false, "error" to (t.message ?: "unknown error"))
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
            val chatResult = if (chatContext != null) {
                withContext(chatContext) {
                    directHtxChat(baseUrl, apiKey, model, prompt, maxTokens, temperature, extraHeaders)
                }
            } else {
                directHtxChat(baseUrl, apiKey, model, prompt, maxTokens, temperature, extraHeaders)
            }
            chatResult.fold(
                onSuccess = { content ->
                    mapOf("content" to content, "model" to model, "ok" to true, "error" to "")
                },
                onFailure = { t ->
                    mapOf("content" to "", "model" to model, "ok" to false, "error" to (t.message ?: "unknown error"))
                },
            )
        },

        // ── note: passthrough, no-op ──────────────────────────────
        "note" to LcncNodeRunner { _, _ -> emptyMap() },

        // ── result.confirm ──────────────────────────────────────────
        "result.confirm" to LcncNodeRunner { _, inputs ->
            val content = (inputs["content"] as? String).orEmpty()
            val ok = inputs["ok"] == true
            val error = (inputs["error"] as? String).orEmpty()

            val html = if (ok) {
                """<div style="border:2px solid #22c55e;border-radius:8px;padding:16px;margin:8px;background:#f0fdf4">
<h3 style="color:#16a34a;margin:0 0 8px">✓ OK</h3>
<pre style="white-space:pre-wrap;font-family:monospace;font-size:13px">${escHtml(content)}</pre>
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
        "credential.enter", "prompt.chat", "result.confirm", "note",
    )

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
