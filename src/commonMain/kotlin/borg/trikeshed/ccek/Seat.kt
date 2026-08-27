package borg.trikeshed.ccek

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import modelmux.ModelCatalogEntry
import modelmux.ModelMux
import modelmux.QuotaLegion
import modelmux.RouteResult
import modelmux.RoutingStrategy
import modelmux.acp.AcpAction
import modelmux.acp.id
import borg.trikeshed.userspace.reactor.MuxReactorElement

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
 *
 * [legion] and [reactor] are the LIVE FACTS (plan step 6): when present,
 * the catalog the policy ranks carries real quota standings and real recent
 * latencies instead of synthesized zeros — without them every strategy was
 * an identity function on a field of lies. Facts stay neutral: provider
 * identity appears only as the join key, never as an ordering input the
 * strategy reads by name.
 */
data class Seat(
    val laneId: String,
    val role: String,
    val contextId: String,
    val selection: RoutingStrategy<ModelCatalogEntry, AcpAction>,
    /** Weight in quorum tallies (W5 panel.vote). Default one seat, one vote. */
    val quorumWeight: Double = 1.0,
    val legion: QuotaLegion? = null,
    val reactor: MuxReactorElement? = null,
) {
    /**
     * Rank the route result through this seat's policy and report the honest
     * strategy name — the plan's "strategyName can lie" risk, repaired at the
     * only point that knows what actually ran. [nowMs] is passed in (the
     * commonMain no-wall-clock rule): the caller's clock feeds the legion.
     */
    fun select(mux: ModelMux, action: AcpAction, nowMs: Long = 0L, vararg requiredCaps: String): RouteResult {
        val routed = mux.route(action, *requiredCaps)
        // Re-rank the route entries' neutral facts through the seat policy,
        // then rebuild the entry Series in the ranked order. Strategies are
        // pure orderings, so rank position i maps to the original candidate
        // whose identity sits at rank i.
        val rankedCatalog = selection.invoke(routedEntriesAsCatalog(routed, nowMs), action)
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

    /**
     * Project route entries into the neutral catalog with LIVE facts where
     * the seat can see them: quotaRemaining from the legion's standing for
     * the provider's key, latencyEstimateMs from the reactor's recent
     * provider health. Without the facts, zeros — and the catalog says so by
     * being zeros (unknown), not by pretending.
     */
    private fun routedEntriesAsCatalog(routed: RouteResult, nowMs: Long): borg.trikeshed.lib.Series<ModelCatalogEntry> {
        val n = routed.a.size
        val reactorState = reactor?.flowState?.value
        val health = reactor?.providerHealth
        return n j { i: Int ->
            val entry = routed.a[i]
            val card = entry.b
            // quota: the provider's best usable standing in the legion
            val quota = if (legion != null && reactorState != null && nowMs > 0L) {
                legion.nextKey(reactorState, nowMs, provider = card.id)
                    ?.remaining?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
            } else 0
            // latency: the reactor's last observation for this provider
            var latency = 0
            if (health != null) {
                for (h in 0 until health.size) {
                    val ph = health[h]
                    if (ph.provider == card.id) {
                        latency = ph.recentLatencyMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        break
                    }
                }
            }
            ModelCatalogEntry(
                provider = card.id,
                model = entry.a,
                freeTier = false,
                quotaRemaining = quota,
                latencyEstimateMs = latency,
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
