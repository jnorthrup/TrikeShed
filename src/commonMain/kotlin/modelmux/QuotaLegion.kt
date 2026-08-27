package modelmux

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.userspace.reactor.MuxKeyEntry
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import borg.trikeshed.userspace.reactor.MuxReactorState

/**
 * One key's quota standing in one metering window.
 *
 * `limit` is the window budget in tokens (0 = unknown/unmetered — the key is
 * ACTIVE on reputation alone). `spent` is metered from receipts; `remaining`
 * is derived, never stored. A 429 or explicit exhaustion flips [exhausted]
 * regardless of arithmetic headroom — the provider said no, that outranks
 * our ledger.
 */
data class QuotaStanding(
    val keyId: String,
    val provider: String,
    val windowStartMs: Long,
    val windowMs: Long,
    val limit: Long,
    val spent: Long,
    val exhausted: Boolean,
    val accessCount: Long,
) {
    val remaining: Long get() = if (limit <= 0L) Long.MAX_VALUE else (limit - spent).coerceAtLeast(0L)
    val isUsable: Boolean get() = !exhausted && (limit <= 0L || spent < limit)

    /** Fraction of the window consumed; 0.0 when unmetered. */
    val utilization: Double get() = if (limit <= 0L) 0.0 else (spent.toDouble() / limit).coerceIn(0.0, 1.0)
}

/**
 * QuotaLegion — the legion of keymux quota standings.
 *
 * The MuxReactorElement owns keys and leases; the legion owns METERING. It is
 * a single-writer ledger (the daemon's receipt path is the writer) folded
 * over reactor state snapshots:
 *
 *  - [applyReceipt] meters one real modelmux call's tokens against its key.
 *  - [exhaust] records a provider 429 / quota-exhausted signal.
 *  - [standings] projects reactor keys × ledger into ranked [QuotaStanding]s.
 *  - [nextKey] is the dispatch face: most-remaining usable key for a provider.
 *
 * Windows roll by wall clock: a receipt landing after `windowStart + windowMs`
 * resets that key's spend. Evidence of spend never outlives its window —
 * quota is attention, not belief.
 */
class QuotaLegion(
    /** Metering window length; provider free tiers are typically per-minute or per-day. */
    val windowMs: Long = 60_000L,
    /** Per-key window budget in tokens; 0 = unmetered (ACTIVE on reputation). */
    val defaultLimit: Long = 0L,
    /** Per-provider budget overrides, keyed by provider id. */
    val limitsByProvider: Map<String, Long> = emptyMap(),
) {
    private data class Meter(var windowStartMs: Long, var spent: Long, var exhausted: Boolean)

    private val meters = mutableMapOf<String, Meter>()

    /** Per-key budget: provider override, else the legion default. */
    fun limitFor(provider: String): Long = limitsByProvider[provider] ?: defaultLimit

    /** Meter one receipt's tokens against its key. Window rollover included. */
    fun applyReceipt(keyId: String, provider: String, receipt: ModelResponseReceipt, nowMs: Long) {
        val tokens = (receipt.inputTokens + receipt.outputTokens).toLong()
        val meter = meterFor(keyId, provider, nowMs)
        if (receipt.httpStatus == 429) {
            meter.exhausted = true
            return
        }
        if (tokens > 0L) meter.spent += tokens
    }

    /** Record an out-of-band exhaustion signal (429 surfaced by the caller). */
    fun exhaust(keyId: String, provider: String, nowMs: Long) {
        meterFor(keyId, provider, nowMs).exhausted = true
    }

    /** Clear exhaustion after backoff — the key re-enters the legion. */
    fun reinstate(keyId: String) {
        meters[keyId]?.exhausted = false
    }

    private fun meterFor(keyId: String, provider: String, nowMs: Long): Meter {
        val meter = meters.getOrPut(keyId) { Meter(nowMs, 0L, false) }
        if (meter.windowStartMs + windowMs <= nowMs) {
            meter.windowStartMs = nowMs
            meter.spent = 0L
            meter.exhausted = false // a fresh window clears provider backoff
        }
        return meter
    }

    /**
     * Project reactor keys × ledger into standings, usable-first then
     * most-remaining. Keys the reactor has benched/backoffed carry that
     * status into `exhausted` — the reactor's word outranks the ledger.
     */
    fun standings(state: MuxReactorState, nowMs: Long): Series<QuotaStanding> {
        val keys = state.keys
        if (keys.isEmpty()) return emptySeriesOf()
        val out = ArrayList<QuotaStanding>(keys.size)
        for (key in keys) {
            val meter = meters[key.keyId]
            val windowStart = meter?.windowStartMs ?: nowMs
            val inWindow = meter != null && windowStart + windowMs > nowMs
            out.add(
                QuotaStanding(
                    keyId = key.keyId,
                    provider = key.provider,
                    windowStartMs = if (inWindow) windowStart else nowMs,
                    windowMs = windowMs,
                    limit = limitFor(key.provider),
                    spent = if (inWindow) meter.spent else 0L,
                    exhausted = (inWindow && meter.exhausted) || key.status != MuxKeyStatus.ACTIVE,
                    accessCount = key.accessCount,
                ),
            )
        }
        out.sortWith(
            compareByDescending<QuotaStanding> { it.isUsable }
                .thenByDescending { it.remaining }
                .thenBy { it.keyId },
        )
        return out.toSeries()
    }

    /**
     * Dispatch face: the best usable key for [provider] (null = any provider),
     * excluding keys already tried on this request chain.
     *
     * [preferKey] is the affinity hint (plan step 6): when that key is usable
     * and not excluded, it wins REGARDLESS of remaining-quota ranking — a
     * follow-up call for the same context belongs on the lane that already
     * holds its warm KV cache. The hint never resurrects an exhausted or
     * benched key: usability still gates everything. When the hint is absent
     * or unusable, ranking proceeds exactly as before.
     */
    fun nextKey(
        state: MuxReactorState,
        nowMs: Long,
        provider: String? = null,
        excluding: Set<String> = emptySet(),
        preferKey: String? = null,
    ): QuotaStanding? {
        val ranked = standings(state, nowMs)
        if (preferKey != null && preferKey !in excluding) {
            var warm: QuotaStanding? = null
            for (i in 0 until ranked.size) {
                if (ranked[i].keyId == preferKey) { warm = ranked[i]; break }
            }
            if (warm != null && warm.isUsable && (provider == null || warm.provider == provider)) {
                return warm
            }
        }
        for (i in 0 until ranked.size) {
            val s = ranked[i]
            if (!s.isUsable) continue
            if (provider != null && s.provider != provider) continue
            if (s.keyId in excluding) continue
            return s
        }
        return null
    }

    /** Legion census: usable / exhausted / unmetered counts for the HUD. */
    fun census(state: MuxReactorState, nowMs: Long): LegionCensus {
        val ranked = standings(state, nowMs)
        var usable = 0
        var exhausted = 0
        var unmetered = 0
        for (i in 0 until ranked.size) {
            val s = ranked[i]
            if (s.isUsable) usable++ else exhausted++
            if (s.limit <= 0L) unmetered++
        }
        return LegionCensus(total = ranked.size, usable = usable, exhausted = exhausted, unmetered = unmetered)
    }
}

data class LegionCensus(
    val total: Int,
    val usable: Int,
    val exhausted: Int,
    val unmetered: Int,
)
