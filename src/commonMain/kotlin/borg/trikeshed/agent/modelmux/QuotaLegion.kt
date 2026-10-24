package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.htx.HtxHeaders
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.userspace.reactor.MuxKeyEntry
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import borg.trikeshed.userspace.reactor.MuxReactorState

/**
 * One key's quota standing in one metering window.
 *
 * `limit` is a configured estimate or HTTP-reported token window (0 = unknown).
 * Receipt arithmetic remains telemetry. Only HTTP observations and credential
 * status close admission; historical usage never establishes a quota ceiling.
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
    /** Provider HTTP evidence, when present, supersedes local receipt arithmetic. */
    val observedRemaining: Long? = null,
    val resetAtMs: Long? = null,
    val dispatchable: Boolean = true,
) {
    val remaining: Long get() = observedRemaining ?: if (limit <= 0L) Long.MAX_VALUE else (limit - spent).coerceAtLeast(0L)
    val isUsable: Boolean get() = dispatchable && !exhausted

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
    limitsByProvider: Map<String, Long> = emptyMap(),
    /**
     * Hermes usage rows observed in this window. Session aggregates are not exact
     * per-call spend and provider-level attribution cannot identify individual keys.
     * Kept for telemetry only; HTTP remaining/reset observations govern admission.
     */
    ledgerSpentByProvider: Map<String, Long> = emptyMap(),
    ledgerWindowStartMs: Long = 0L,
) {
    init {
        require(windowMs > 0L) { "Quota window must be positive" }
    }
    private val meterGate = Any()
    var limitsByProvider: Map<String, Long> = limitsByProvider
        private set
    var ledgerSpentByProvider: Map<String, Long> = ledgerSpentByProvider
        private set
    var ledgerWindowStartMs: Long = ledgerWindowStartMs
        private set
    var ledgerUsage: Map<String, LedgerQuota> = emptyMap()
        private set

    /**
     * Re-walk the ledger into this legion without losing this process's own meters —
     * the daemon calls it when Hermes' state.db changes, so ONE legion (one pool) serves
     * the brain's mux and the LCNC mux across rebuilds.
     */
    fun refresh(rows: List<LedgerRow>, nowMs: Long): Unit = synchronizedLock(meterGate) {
        val walked = walkBack(rows, nowMs, windowMs)
        // Historical throughput is usage evidence, never an advertised ceiling.
        ledgerUsage = walked
        ledgerSpentByProvider = walked.mapValues { it.value.spentThisWindow }
        ledgerWindowStartMs = nowMs - (nowMs % windowMs)
    }

    /** The ledger pre-charge still in force for [provider] at [nowMs] (0 once its window rolled). */
    fun ledgerSpentFor(provider: String, nowMs: Long): Long =
        synchronizedLock(meterGate) {
            if (nowMs < ledgerWindowStartMs + windowMs) ledgerSpentByProvider[provider] ?: 0L else 0L
        }

    companion object {
        const val DAY_MS: Long = 86_400_000L

        /**
         * Summarize observed usage rows by provider and time bucket. `provenLimit`
         * retains its legacy field name but is an observed peak, never a ceiling.
         */
        fun walkBack(rows: List<LedgerRow>, nowMs: Long, windowMs: Long = DAY_MS): Map<String, LedgerQuota> {
            require(windowMs > 0L)
            val buckets = HashMap<String, HashMap<Long, Long>>()
            val spent = HashMap<String, Long>()
            val rowsBy = HashMap<String, Int>()
            val lastSeen = HashMap<String, Long>()
            for (r in rows) {
                if (r.provider.isEmpty()) continue
                val tokens = r.inputTokens + r.outputTokens
                if (tokens <= 0L) continue
                val bucket = r.lastSeenMs / windowMs
                val per = buckets.getOrPut(r.provider) { HashMap() }
                per[bucket] = (per[bucket] ?: 0L) + tokens
                if (r.lastSeenMs > nowMs - windowMs && r.lastSeenMs <= nowMs) spent[r.provider] = (spent[r.provider] ?: 0L) + tokens
                rowsBy[r.provider] = (rowsBy[r.provider] ?: 0) + 1
                lastSeen[r.provider] = maxOf(lastSeen[r.provider] ?: 0L, r.lastSeenMs)
            }
            val out = LinkedHashMap<String, LedgerQuota>()
            for ((provider, per) in buckets.entries.sortedBy { it.key }) {
                out[provider] = LedgerQuota(
                    provider = provider,
                    provenLimit = per.values.max(),
                    spentThisWindow = spent[provider] ?: 0L,
                    rows = rowsBy[provider] ?: 0,
                    lastSeenMs = lastSeen[provider] ?: 0L,
                )
            }
            return out
        }

        /** Import historical spend without inventing a provider quota ceiling. */
        fun fromLedger(rows: List<LedgerRow>, nowMs: Long, windowMs: Long = DAY_MS): QuotaLegion {
            val walked = walkBack(rows, nowMs, windowMs)
            return QuotaLegion(
                windowMs = windowMs,
                defaultLimit = 0L,
                ledgerSpentByProvider = walked.mapValues { it.value.spentThisWindow },
                ledgerWindowStartMs = nowMs - (nowMs % windowMs),
            ).also { it.ledgerUsage = walked }
        }
    }

    private data class Meter(
        var windowStartMs: Long,
        var spent: Long,
        var exhausted: Boolean,
        var blockedUntilMs: Long = 0L,
        var observation: HttpQuota? = null,
        var observationSpent: Long = 0L,
    )

    private val meters = mutableMapOf<String, Meter>()

    /** Unknown capacity stays admitted. Only unexpired HTTP observations can close admission. */
    fun isAvailable(keyId: String, nowMs: Long): Boolean = synchronizedLock(meterGate) {
        val meter = meters[keyId] ?: return true
        !(meter.exhausted && meter.blockedUntilMs > nowMs)
    }

    /** Per-key budget: provider override, else the legion default. */
    fun limitFor(provider: String): Long = synchronizedLock(meterGate) { limitsByProvider[provider] ?: defaultLimit }

    /** Meter one receipt's tokens against its key. Window rollover included. */
    fun applyReceipt(keyId: String, provider: String, receipt: ModelResponseReceipt, nowMs: Long): Unit = synchronizedLock(meterGate) {
        val tokens = receipt.inputTokens.toLong() + receipt.outputTokens.toLong()
        val meter = meterFor(keyId, provider, nowMs)
        if (receipt.httpStatus == 429) {
            meter.exhausted = true
            if (meter.blockedUntilMs <= nowMs) meter.blockedUntilMs = nowMs + minOf(windowMs, 60_000L)
            return
        }
        if (tokens > 0L) meter.spent += tokens
    }

    /** Record an out-of-band exhaustion signal (429 surfaced by the caller). */
    fun exhaust(keyId: String, provider: String, nowMs: Long): Unit = synchronizedLock(meterGate) {
        meterFor(keyId, provider, nowMs).also {
            it.exhausted = true
            it.blockedUntilMs = nowMs + minOf(windowMs, 60_000L)
        }
    }

    /** Fold real provider headers after charging the response receipt. */
    fun observeHttp(keyId: String, provider: String, status: Int, headers: HtxHeaders, nowMs: Long): HttpQuota =
        synchronizedLock(meterGate) {
            val observed = HttpQuota.parse(status, headers, nowMs, minOf(windowMs, 60_000L))
            val meter = meterFor(keyId, provider, nowMs)
            if (observed.limit != null || observed.remaining != null) {
                meter.observation = observed
                meter.observationSpent = meter.spent
            }
            if (observed.exhausted) {
                meter.exhausted = true
                meter.blockedUntilMs = observed.resetAtMs
            } else if (status in 200..299) {
                meter.exhausted = false
                meter.blockedUntilMs = 0L
            }
            observed
        }

    /** Clear exhaustion after backoff — the key re-enters the legion. */
    fun reinstate(keyId: String): Unit = synchronizedLock(meterGate) {
        meters[keyId]?.also { it.exhausted = false; it.blockedUntilMs = 0L }
    }

    private fun meterFor(keyId: String, provider: String, nowMs: Long): Meter {
        val meter = meters.getOrPut(keyId) { Meter(nowMs, 0L, false) }
        if (meter.windowStartMs + windowMs <= nowMs) {
            meter.windowStartMs = nowMs
            meter.observationSpent -= meter.spent
            meter.spent = 0L
        }
        if (meter.blockedUntilMs <= nowMs) meter.exhausted = false
        if ((meter.observation?.resetAtMs ?: Long.MAX_VALUE) <= nowMs) meter.observation = null
        return meter
    }

    /**
     * Project reactor keys × ledger into standings, usable-first then
     * most-remaining. Keys the reactor has benched/backoffed carry that
     * status into `exhausted` — the reactor's word outranks the ledger.
     */
    fun standings(state: MuxReactorState, nowMs: Long): Series<QuotaStanding> = synchronizedLock(meterGate) {
        val keys = state.keys
        val out = ArrayList<QuotaStanding>(keys.size + limitsByProvider.size)
        // Providers the ledger proved but no key has touched yet: shown as
        // `ledger:<provider>` rows (accessCount 0) so the walk-back is visible before
        // the first dispatch. They are not dispatchable keys — the reactor's are.
        val seenProviders = HashSet<String>()
        for (key in keys) seenProviders.add(key.provider)
        for (provider in (limitsByProvider.keys + ledgerSpentByProvider.keys)) if (provider !in seenProviders) {
            val limit = limitFor(provider)
            val spent = ledgerSpentFor(provider, nowMs)
            out.add(
                QuotaStanding(
                    keyId = "ledger:$provider", provider = provider,
                    windowStartMs = if (nowMs < ledgerWindowStartMs + windowMs) ledgerWindowStartMs else nowMs,
                    windowMs = windowMs, limit = limit, spent = spent, exhausted = false, accessCount = 0L,
                    dispatchable = false,
                ),
            )
        }
        if (keys.isEmpty() && out.isEmpty()) return emptySeriesOf()
        for (key in keys) {
            val meter = meters[key.keyId]
            val windowStart = meter?.windowStartMs ?: nowMs
            val inWindow = meter != null && windowStart + windowMs > nowMs
            val observed = meter?.observation?.takeIf { it.resetAtMs > nowMs }
            val observedRemaining = observed?.remaining?.let {
                (it - ((meter?.spent ?: 0L) - (meter?.observationSpent ?: 0L)).coerceAtLeast(0L)).coerceAtLeast(0L)
            }
            out.add(
                QuotaStanding(
                    keyId = key.keyId,
                    provider = key.provider,
                    windowStartMs = if (inWindow) windowStart else nowMs,
                    windowMs = windowMs,
                    limit = observed?.limit ?: limitFor(key.provider),
                    spent = (if (inWindow) meter.spent else 0L) + ledgerSpentFor(key.provider, nowMs),
                    exhausted = (meter?.exhausted == true && meter.blockedUntilMs > nowMs) ||
                        key.status == MuxKeyStatus.BENCHED ||
                        key.status == MuxKeyStatus.BACKOFF && (key.leaseExpiresAt <= 0L || key.leaseExpiresAt > nowMs),
                    accessCount = key.accessCount,
                    observedRemaining = observedRemaining,
                    resetAtMs = maxOf(observed?.resetAtMs ?: 0L, meter?.blockedUntilMs ?: 0L).takeIf { it > nowMs },
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
            if (!s.isUsable || !s.dispatchable) continue
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

/** One success-ledger row: what a provider was seen to bear, when. (`HermesModelUsage.ledgerRows` on the JVM.) */
data class LedgerRow(
    val provider: String,
    val model: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val lastSeenMs: Long,
    val calls: Int = 0,
)

/** A provider's quota as walked back from the ledger by [QuotaLegion.walkBack]. */
data class LedgerQuota(
    val provider: String,
    /** Most tokens the ledger shows burned in one window — the proven floor. */
    val provenLimit: Long,
    /** Tokens the ledger shows burned inside the window ending now — already gone from the shared pool. */
    val spentThisWindow: Long,
    val rows: Int,
    val lastSeenMs: Long,
)
