package borg.trikeshed.userspace.reactor

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import modelmux.ProviderHealth
import modelmux.QuotaStanding

/**
 * The mux's working set as REDACTED blackboard entries — the open home for
 * what today only hermes' state.db and the reactor's in-memory flowState can
 * answer: which keys the daemon works with, which models they serve, and how
 * the quota windows stand.
 *
 * Redaction rule (the whole reason this projection exists): a key's entry is
 * keyed by the FINGERPRINT of its keyId, never the keyId itself — the
 * ReactorSource lane treats keyId as credential material, so the board gets
 * `sha256(keyId)` truncated to 16 hex and only operator-facing metadata
 * (provider, label, status, counters). No secret, no raw keyId, no
 * conversation affinity ever crosses into a board value.
 *
 * Pure: the daemon collects [MuxReactorElement.flowState], the quota
 * legion's standings, and provider health on its tick and lands the entries;
 * tests drive it directly.
 */
object MuxBlackboardProjection {

    const val SOURCE = "mux.reactor"

    fun fingerprint(keyId: String): String = ContentId.of(keyId.encodeToByteArray()).hex.take(16)

    fun keyEntry(key: MuxKeyEntry): Join<String, Map<String, Any?>> =
        "mux/key/${fingerprint(key.keyId)}" j mapOf<String, Any?>(
            "provider" to key.provider,
            "label" to key.label,
            "status" to key.status.name,
            "lastModel" to key.lastModel,
            "lastUsedMs" to key.lastUsedMs,
            "accessCount" to key.accessCount,
            "leasedTo" to key.leasedTo,
            "leaseExpiresAt" to key.leaseExpiresAt,
        )

    fun modelEntry(model: MuxModelEntry): Join<String, Map<String, Any?>> =
        "mux/model/${model.modelId}" j mapOf<String, Any?>(
            "provider" to model.provider,
            "contextWindow" to model.contextWindow,
            "available" to model.available,
        )

    fun quotaEntry(standing: QuotaStanding): Join<String, Map<String, Any?>> =
        "mux/quota/${standing.provider}" j mapOf<String, Any?>(
            "limit" to standing.limit,
            "spent" to standing.spent,
            "remaining" to standing.remaining,
            "exhausted" to standing.exhausted,
            "windowStartMs" to standing.windowStartMs,
            "windowMs" to standing.windowMs,
        )

    fun healthEntry(health: ProviderHealth): Join<String, Map<String, Any?>> =
        "mux/health/${health.provider}" j mapOf<String, Any?>(
            "successRate" to health.successRate,
            "recentLatencyMs" to health.recentLatencyMs,
            "updatedAt" to health.updatedAt,
        )

    fun entries(
        state: MuxReactorState,
        standings: Series<QuotaStanding>,
        health: Series<ProviderHealth>,
    ): Series<Join<String, Map<String, Any?>>> {
        val out = ArrayList<Join<String, Map<String, Any?>>>(
            state.keys.size + state.models.size + standings.size + health.size + 1
        )
        out.add("mux/state" j mapOf<String, Any?>(
            "keys" to state.keys.size,
            "activeKeys" to state.keys.count { it.status == MuxKeyStatus.ACTIVE },
            "availableKeys" to state.availableKeys,
            "models" to state.models.size,
            "running" to state.currentlyRunning,
            "queueDepth" to state.queueDepth,
            "tickSequence" to state.tickSequence,
            "lastTickMs" to state.lastTickMs,
        ))
        for (key in state.keys) out.add(keyEntry(key))
        for (model in state.models) out.add(modelEntry(model))
        for (i in 0 until standings.size) out.add(quotaEntry(standings[i]))
        for (i in 0 until health.size) out.add(healthEntry(health[i]))
        return out.size j { i -> out[i] }
    }
}
