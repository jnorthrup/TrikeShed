package borg.trikeshed.agent.modelmux

import borg.trikeshed.parse.reify

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.LlmAuth
import borg.trikeshed.agent.keymux.HermesModelConfig
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.emptyHtxHeaders
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.*
import kotlinx.coroutines.currentCoroutineContext

/**
 * One discovered model as neutral facts: identity + context window + tier.
 * Quota is NOT a discovery fact — it is metered by [borg.trikeshed.agent.modelmux.QuotaLegion]; discovery
 * never fabricates headroom it cannot see.
 */
data class DiscoveredModel(
    val provider: String,
    val modelId: String,
    val contextWindow: Int = 0,
    val freeTier: Boolean = false,
    val latencyEstimateMs: Int = 0,
    /** Provider-quoted prompt price per token, or null when unquoted. */
    val pricePerPromptToken: Double? = null,
)

/**
 * ModelDiscovery — provider model-listing slurp into the neutral catalog.
 *
 * Two wire shapes are honoured (both are `{"data":[…]}` envelopes):
 *  - OpenAI-shaped:   `{"data":[{"id":"gpt-4"}]}`
 *  - OpenRouter-shaped: `{"data":[{"id":"…","context_length":N,"pricing":{"prompt":"0"}}]}`
 *
 * Free-tier detection is mechanical: a zero prompt price, or "free" in the
 * model id. Anything else is paid. Unknown fields stay unknown (0 / false) —
 * discovery reports what the provider said, nothing more.
 */
object ModelDiscovery {
    /** Config and observed Hermes models retain their provider identity without assuming quota. */
    fun fromHermes(config: borg.trikeshed.agent.keymux.HermesModelConfig?, rows: Series<borg.trikeshed.agent.modelmux.LedgerRow>): Series<borg.trikeshed.agent.modelmux.DiscoveredModel> {
        val models = linkedMapOf<borg.trikeshed.lib.Join<String, String>, borg.trikeshed.agent.modelmux.DiscoveredModel>()
        if (config != null) models[config.provider j config.model] =
            borg.trikeshed.agent.modelmux.DiscoveredModel(
                config.provider,
                config.model,
                config.contextWindow
            )
        for (row in rows.view) {
            if (row.provider.isBlank() || row.model.isBlank()) continue
            models.getOrPut(row.provider j row.model) {
                borg.trikeshed.agent.modelmux.DiscoveredModel(
                    row.provider,
                    row.model
                )
            }
        }
        return models.values.toSeries()
    }

    /** Parse one provider `/models` payload into discovered models. */
    fun parseModels(provider: String, json: String): Series<borg.trikeshed.agent.modelmux.DiscoveredModel> {
        val root = runCatching { reify(json) }.getOrNull() as? Map<*, *>
            ?: return emptySeriesOf()
        val data = root["data"] as? List<*> ?: return emptySeriesOf()
        val out = ArrayList<borg.trikeshed.agent.modelmux.DiscoveredModel>()
        for (item in data) {
            val m = item as? Map<*, *> ?: continue
            val id = m["id"]?.toString()?.trim() ?: continue
            if (id.isEmpty()) continue
            val contextWindow = (m["context_length"] as? Number)?.toInt()
                ?: (m["context_window"] as? Number)?.toInt()
                ?: (m["max_model_len"] as? Number)?.toInt()
                ?: 0
            val pricing = m["pricing"] as? Map<*, *>
            val promptPrice = pricing?.get("prompt")?.toString()?.toDoubleOrNull()
            out.add(
                borg.trikeshed.agent.modelmux.DiscoveredModel(
                    provider, id, contextWindow, isFree(id, pricing),
                    pricePerPromptToken = promptPrice,
                )
            )
        }
        return out.toSeries()
    }

    private fun isFree(id: String, pricing: Any?): Boolean {
        if ("free" in id.lowercase()) return true
        val p = pricing as? Map<*, *> ?: return false
        val prompt = p["prompt"]?.toString()?.toDoubleOrNull() ?: return false
        return prompt == 0.0
    }

    /**
     * Live discovery: GET `{baseUrl}/models` through the coroutine context's
     * HtxElement (userspace.nio only — never a JDK client), bearer key
     * resolved from [keyMux] at `llm.<provider>.key` falling back to
     * `llm.default.key`. No key still discovers — public listings need none.
     * NONE suppresses all credential lookup for explicitly credential-free hosts.
     */
    suspend fun discover(
        provider: String,
        baseUrl: String,
        keyMux: borg.trikeshed.agent.keymux.KeyMux? = null,
        auth: borg.trikeshed.agent.keymux.LlmAuth = borg.trikeshed.agent.keymux.LlmAuth.REQUIRED,
    ): Result<Series<borg.trikeshed.agent.modelmux.DiscoveredModel>> {
        runCatching { borg.trikeshed.agent.modelmux.Airgap.requireRemote() }.getOrElse { return Result.failure(it) }
        val htx = currentCoroutineContext()[HtxKey]
            ?: return Result.failure(IllegalStateException("No HtxKey found in coroutine context"))
        val key = if (auth == borg.trikeshed.agent.keymux.LlmAuth.NONE) null
            else keyMux?.get("llm.$provider.key") ?: keyMux?.get("llm.default.key")
        val headers = if (key != null) htxHeaders("Authorization" j "Bearer $key") else emptyHtxHeaders()
        val req = parseHtxRequest(url = "${baseUrl.trimEnd('/')}/models", method = HtxMethod.GET).copy(headers = headers)
        return try {
            borg.trikeshed.agent.modelmux.Airgap.requireRemote()
            val resp = htx.request(req)
            if (resp.status !in 200..299) {
                Result.failure(IllegalStateException("ModelDiscovery failed with HTTP ${resp.status}"))
            } else {
                Result.success(parseModels(provider, resp.body.toArray().decodeToString()))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Bridge discoveries into the neutral catalog the routing strategies rank.
     * [quotaOf] supplies quota (default: none known) — discovery refuses to
     * invent headroom; wire a [borg.trikeshed.agent.modelmux.QuotaLegion] standing here when one exists.
     */
    fun toCatalog(
        models: Series<borg.trikeshed.agent.modelmux.DiscoveredModel>,
        quotaOf: (borg.trikeshed.agent.modelmux.DiscoveredModel) -> Int = { -1 },
    ): Series<borg.trikeshed.agent.modelmux.ModelCatalogEntry> = models α { d ->
        borg.trikeshed.agent.modelmux.ModelCatalogEntry(
            provider = d.provider,
            model = d.modelId,
            freeTier = d.freeTier,
            quotaRemaining = quotaOf(d),
            latencyEstimateMs = d.latencyEstimateMs,
            pricePerMillionPromptTokens = d.pricePerPromptToken?.let { it * 1_000_000.0 },
        )
    }
}
