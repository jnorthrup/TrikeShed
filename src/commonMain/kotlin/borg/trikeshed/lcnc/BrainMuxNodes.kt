package borg.trikeshed.lcnc

import keymux.CouchKeyStore
import keymux.KeyMux
import modelmux.ModelMux
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * BrainMuxNodes — BrainClient decomposed as LCNC graph nodes.
 *
 *   - keys.status → KeyMux roster (which providers have keys)
 *   - mux.models → ModelMux model list (routable models + caps)
 *   - credential.enter → CouchKeyStore (CouchDB persistence)
 *   - prompt.chat → HTX client (200/non-200 patchpoints) + prefill resolution
 *   - result.confirm → HTML confirmation dialog (OK/ERROR)
 *   - note → passthrough no-op
 */
object BrainMuxNodes {

    fun registry(
        keyMux: KeyMux? = null,
        modelMux: ModelMux? = null,
        credStore: CouchKeyStore? = null,
        chatFn: (suspend (
            url: String,
            apiKey: String,
            model: String,
            messages: List<Pair<String, String>>,
            maxTokens: Int,
            temperature: Double,
            headers: List<Pair<String, String>>,
        ) -> Result<String>)? = null,
    ): Map<String, LcncNodeRunner> = mapOf(

        // ── keys.status ─────────────────────────────────────────────
        // Queries the daemon's KeyMux for each HarnessRegistry provider.
        // Returns a roster: [{provider, keyPresent, baseUrl}].
        "keys.status" to LcncNodeRunner { _, _ ->
            if (keyMux == null) return@LcncNodeRunner mapOf("roster" to emptyList<Any>())
            val roster = ArrayList<Map<String, Any?>>()
            for (i in 0 until keymux.HarnessRegistry.providers.size) {
                val p = keymux.HarnessRegistry.providers[i]
                val keyPresent = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        keyMux.get("llm.${p.id}.key")
                    }
                }.getOrNull()?.isNotBlank() == true
                val baseUrl = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        keyMux.get("llm.${p.id}.base_url")
                    }
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
        // Returns: [{id, caps, baseUrl}].
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
                models.add(linkedMapOf(
                    "id" to id,
                    "caps" to capsList,
                ))
            }
            mapOf("models" to models)
        },

        // ── credential.enter ────────────────────────────────────────
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
                "key" to key,
            ))
        },

        // ── prompt.chat ─────────────────────────────────────────────
        "prompt.chat" to LcncNodeRunner { node, inputs ->
            val prompt = (inputs["prompt"] as? String)
                ?: node.params["prompt"]?.takeIf { it.isNotBlank() }
                ?: ""

            val model = node.params["model"] ?: ""

            val prefill = node.params["prefill"] ?: "(manual)"
            var apiKey = node.params["key"] ?: ""
            var baseUrl = node.params["url"] ?: ""

            if (prefill != "(manual)" && credStore != null) {
                val stored = credStore.readCredential(prefill)
                if (stored != null) {
                    if (apiKey.isBlank()) apiKey = stored["key"].orEmpty()
                    if (baseUrl.isBlank()) baseUrl = stored["base_url"].orEmpty()
                }
            }

            val extraHeaders = parseHeaders(node.params["headers"] ?: "")

            if (apiKey.isBlank()) {
                return@LcncNodeRunner mapOf(
                    "content" to "",
                    "model" to model,
                    "ok" to false,
                    "error" to "no API key — enter in the key field or select a prefill provider",
                )
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

            if (chatFn == null) {
                return@LcncNodeRunner mapOf(
                    "content" to "",
                    "model" to model,
                    "ok" to false,
                    "error" to "no chat client wired — daemon must inject chatFn",
                )
            }

            val result = chatFn(
                baseUrl, apiKey, model,
                listOf("user" to prompt),
                maxTokens, temperature,
                extraHeaders,
            )

            result.fold(
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
        "keys.status", "mux.models",
        "credential.enter", "prompt.chat", "result.confirm", "note",
    )

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
