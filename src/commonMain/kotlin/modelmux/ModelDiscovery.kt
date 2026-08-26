package modelmux

import keymux.KeyMux
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.emptyHtxHeaders
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.α
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.currentCoroutineContext

/**
 * One discovered model as neutral facts: identity + context window + tier.
 * Quota is NOT a discovery fact — it is metered by [QuotaLegion]; discovery
 * never fabricates headroom it cannot see.
 */
data class DiscoveredModel(
    val provider: String,
    val modelId: String,
    val contextWindow: Int = 0,
    val freeTier: Boolean = false,
    val latencyEstimateMs: Int = 0,
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

    /** Parse one provider `/models` payload into discovered models. */
    fun parseModels(provider: String, json: String): Series<DiscoveredModel> {
        val root = runCatching { JsonSupport.parse(json) }.getOrNull() as? Map<*, *>
            ?: return emptySeriesOf()
        val data = root["data"] as? List<*> ?: return emptySeriesOf()
        val out = ArrayList<DiscoveredModel>()
        for (item in data) {
            val m = item as? Map<*, *> ?: continue
            val id = m["id"]?.toString()?.trim() ?: continue
            if (id.isEmpty()) continue
            val contextWindow = (m["context_length"] as? Number)?.toInt()
                ?: (m["context_window"] as? Number)?.toInt()
                ?: 0
            out.add(DiscoveredModel(provider, id, contextWindow, isFree(id, m["pricing"])))
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
     */
    suspend fun discover(
        provider: String,
        baseUrl: String,
        keyMux: KeyMux? = null,
    ): Result<Series<DiscoveredModel>> {
        val htx = currentCoroutineContext()[HtxKey]
            ?: return Result.failure(IllegalStateException("No HtxKey found in coroutine context"))
        val key = keyMux?.get("llm.$provider.key") ?: keyMux?.get("llm.default.key")
        val headers = if (key != null) htxHeaders("Authorization" j "Bearer $key") else emptyHtxHeaders()
        val req = parseHtxRequest(url = "$baseUrl/models", method = HtxMethod.GET).copy(headers = headers)
        return try {
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
     * invent headroom; wire a [QuotaLegion] standing here when one exists.
     */
    fun toCatalog(
        models: Series<DiscoveredModel>,
        quotaOf: (DiscoveredModel) -> Int = { 0 },
    ): Series<ModelCatalogEntry> = models α { d ->
        ModelCatalogEntry(
            provider = d.provider,
            model = d.modelId,
            freeTier = d.freeTier,
            quotaRemaining = quotaOf(d),
            latencyEstimateMs = d.latencyEstimateMs,
        )
    }
}
