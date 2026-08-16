package modelmux

data class ModelRequest(val modelId: String?)

class CausalRoutingRule(
    val quotaThreshold: Int,
    val latencyBoundMs: Long
) {
    fun evaluate(
        catalog: List<ModelCatalogEntry>,
        request: ModelRequest
    ): List<ModelCatalogEntry> = catalog
        .filter { it.quotaRemaining > quotaThreshold && it.latencyEstimateMs < latencyBoundMs }
        .sortedByDescending { it.quotaRemaining }
}
