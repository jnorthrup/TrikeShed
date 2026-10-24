package borg.trikeshed.agent.modelmux

data class ModelRequest(val modelId: String?)

class CausalRoutingRule(
    val quotaThreshold: Int,
    val latencyBoundMs: Long
) {
    fun evaluate(
        catalog: List<borg.trikeshed.agent.modelmux.ModelCatalogEntry>,
        request: borg.trikeshed.agent.modelmux.ModelRequest
    ): List<borg.trikeshed.agent.modelmux.ModelCatalogEntry> = catalog
        .filter { it.quotaRemaining > quotaThreshold && it.latencyEstimateMs < latencyBoundMs }
        .sortedByDescending { it.quotaRemaining }
}
