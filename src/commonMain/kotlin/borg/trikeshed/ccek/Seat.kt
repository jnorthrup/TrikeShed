package borg.trikeshed.ccek

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import modelmux.ModelCatalogEntry
import modelmux.ModelMux
import modelmux.RouteResult
import modelmux.RoutingStrategy
import modelmux.acp.AcpAction
import modelmux.acp.id

/**
 * W3.2: where a context meets a lane, WITHOUT naming a key.
 *
 * A seat binds a role to a context and a selection policy. Credentials are
 * structurally absent: permission to CALL comes from the reactor lease/quota
 * plane (MuxReactorElement via CcekReactorBinding), never from anything a
 * seat or context carries.
 *
 * [selection] instantiates the previously dormant RoutingStrategy family —
 * build it with the entry-typed factories (`priorityOf`, `weightedOf`,
 * `costOptimizedOf<AcpAction>()`, `roundRobinOf`, `autoOf`).
 */
data class Seat(
    val laneId: String,
    val role: String,
    val contextId: String,
    val selection: RoutingStrategy<ModelCatalogEntry, AcpAction>,
    /** Weight in quorum tallies (W5 panel.vote). Default one seat, one vote. */
    val quorumWeight: Double = 1.0,
) {
    /**
     * Rank the route result through this seat's policy and report the honest
     * strategy name — the plan's "strategyName can lie" risk, repaired at the
     * only point that knows what actually ran.
     */
    fun select(mux: ModelMux, action: AcpAction, vararg requiredCaps: String): RouteResult {
        val routed = mux.route(action, *requiredCaps)
        // Re-rank the route entries' neutral facts through the seat policy,
        // then rebuild the entry Series in the ranked order. Strategies are
        // pure orderings, so rank position i maps to the original candidate
        // whose identity sits at rank i.
        val rankedCatalog = selection.invoke(routedEntriesAsCatalog(routed), action)
        val n = rankedCatalog.size
        val ranked = n j { i: Int -> routed.a[rankedCatalog[i].ordinalIn(routed)] }
        // Trust repair: declare the discipline that DID run (capability filter
        // followed by this seat's ranking), not a declaration nobody verified.
        mux.strategyName = "capability+${selection.strategyName}"
        return ranked j routed.b
    }

    private fun ModelCatalogEntry.ordinalIn(routed: RouteResult): Int {
        for (i in 0 until routed.a.size) {
            val e = routed.a[i]
            if (e.b.id == model) return i
        }
        return 0
    }

    private fun routedEntriesAsCatalog(routed: RouteResult): borg.trikeshed.lib.Series<ModelCatalogEntry> {
        val n = routed.a.size
        return n j { i: Int ->
            val card = routed.a[i].b
            ModelCatalogEntry(
                provider = card.id,
                model = routed.a[i].a,
                freeTier = false,
                quotaRemaining = 0,
                latencyEstimateMs = 0,
            )
        }
    }
}

/**
 * W3.5: the Venn as data — three-set membership plus intersections computed
 * from presence-only facts. No key material ever enters this structure:
 * key links are COUNTED as paths (`llm.<provider>.key` resolves or not),
 * models are named, providers named.
 */
data class MuxVenn(
    /** Providers whose `llm.<provider>.key` path resolves in KeyMux (presence only). */
    val keyLinkedProviders: Set<String>,
    /** Models known to the composed mux roster, each mapped to its provider. */
    val discoverableModels: Map<String, String>,
    /** Providers represented by at least one roster entry. */
    val muxableProviders: Set<String>,
) {
    /**
     * The intersection set: models whose provider is key-linked AND
     * roster-present — "what can this seat run right now".
     */
    val runnableNow: Set<String>
        get() = discoverableModels.filterValues { provider ->
            provider in keyLinkedProviders && provider in muxableProviders
        }.keys

    fun document(): Map<String, Any?> = linkedMapOf(
        "keys" to keyLinkedProviders.toList(),
        "models" to discoverableModels.keys.toList(),
        "providers" to muxableProviders.toList(),
        "runnableCount" to runnableNow.size,
    )
}
