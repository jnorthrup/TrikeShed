package borg.trikeshed.ccek

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import modelmux.ModelCatalogEntry
import modelmux.ModelEntry
import modelmux.ModelMux
import modelmux.RouteResult
import modelmux.RoutingStrategy
import modelmux.acp.AcpAction
import modelmux.acp.providerTag

data class Seat(
    val laneId: String,
    val role: String,
    val contextId: String,
    val selection: RoutingStrategy<ModelCatalogEntry, Unit>,
    val quorumWeight: Double = 1.0,
) {
    fun select(mux: ModelMux, action: AcpAction): RouteResult {
        mux.strategyName = "capability"
        val capabilityFiltered = mux.route(action)
        val candidates = capabilityFiltered.a
        val rankedCatalog = selection(candidates.toCatalog(), Unit)
        val byModel = candidates.toModelMap()
        val rankedEntries = (0 until rankedCatalog.size).mapNotNull { index ->
            byModel[rankedCatalog[index].model]
        }
        mux.strategyName = "capability+${selection.strategyName}"
        return rankedEntries.toSeries() j action
    }

    private fun Series<ModelEntry>.toCatalog(): Series<ModelCatalogEntry> =
        (0 until size).map { index ->
            val entry = this[index]
            val provider = entry.b.providerTag ?: entry.a
            ModelCatalogEntry(
                provider = provider,
                model = entry.a,
                freeTier = false,
                quotaRemaining = 1,
                latencyEstimateMs = 0,
            )
        }.toSeries()

    private fun Series<ModelEntry>.toModelMap(): Map<String, ModelEntry> =
        (0 until size).associate { index -> this[index].a to this[index] }
}
