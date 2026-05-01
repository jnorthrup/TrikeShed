package borg.trikeshed.dreamer

import kotlin.time.Clock

/** Epoch milliseconds, with graceful fallback when Clock is unavailable (e.g. wasm/js test env). */
private fun epochMillis(): Long = try {
    Clock.System.now().toEpochMilliseconds()
} catch (_: Throwable) {
    0L
}

class TradingEngine(
    val genome: Genome,
    val mode: Mode = Mode.SHADOW,
    initialCapital: Double = 0.0,
    initialHoldings: MutableMap<String, Holding> = mutableMapOf()
) {
    val baselines: MutableMap<String, Double> = mutableMapOf()
    val lastActionTimestamps: MutableMap<String, Long> = mutableMapOf()
    val rebalanceState: MutableMap<String, Any> = mutableMapOf()

    /** Crash protection state per symbol: stores the trough value at CP activation */
    val crashProtectionState: MutableMap<String, Double> = mutableMapOf()

    var cashBalance: Double = initialCapital
    val holdings: MutableMap<String, Holding> = initialHoldings
    var totalHarvested: Double = 0.0

    /** Check if a symbol is currently in crash protection mode */
    fun isCrashProtected(symbol: String): Boolean = symbol in crashProtectionState

    fun loadPersistedState(data: Map<String, Any?>?) {
        if (data == null) return
        @Suppress("UNCHECKED_CAST")
        (data["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { baselines[k] = it } }
        @Suppress("UNCHECKED_CAST")
        (data["crashProtectionState"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { crashProtectionState[k] = it } }
    }

    fun getStateSnapshot(): Map<String, Any?> = mapOf(
        "baselines" to baselines.toMap(),
        "holdings" to holdings.mapValues { it.value.rawQuantity },
        "cashBalance" to cashBalance,
        "crashProtectionState" to crashProtectionState.toMap(),
    )

    fun injectSimulationState(snapshot: Map<String, Any?>) {
        snapshot["cashBalance"]?.let { if (it is Number) cashBalance = it.toDouble() }
        @Suppress("UNCHECKED_CAST")
        (snapshot["holdings"] as? Map<String, Any?>)?.forEach { (k, v) -> if (v is Number) holdings[k] = Holding(v.toDouble()) }
        @Suppress("UNCHECKED_CAST")
        (snapshot["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { baselines[k] = it } }
        @Suppress("UNCHECKED_CAST")
        (snapshot["crashProtectionState"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { crashProtectionState[k] = it } }
    }

    fun getGenomicParam(param: GenomeParam, symbol: String? = null): Double {
        if (symbol != null) {
            val ov = genome.overridesFor(symbol)
            val v = ov?.get(param.storageKey) ?: ov?.get(param.name)
            if (v is Number) return v.toDouble()
            if (v is String) return v.toDoubleOrNull() ?: genome.getDouble(param)
        }
        return genome.getDouble(param)
    }

    suspend fun _placeBuy(api: ApiClient?, symbol: String, quantity: String, expectedPrice: Double? = null): Any? {
        if (mode == Mode.LIVE && api != null) return api.placeBuy(symbol, quantity)
        return mapOf("id" to "shadow_buy_${kotlin.random.Random.nextInt()}")
    }

    suspend fun _placeSell(api: ApiClient?, symbol: String, quantity: String, expectedPrice: Double? = null): Any? {
        if (mode == Mode.LIVE && api != null) return api.placeSell(symbol, quantity)
        return mapOf("id" to "shadow_sell_${kotlin.random.Random.nextInt()}")
    }

    suspend fun update(portfolioSummary: List<PortfolioRow>, api: ApiClient?, cashBalanceIn: Double, holdingDetails: Map<String, Holding>?): EngineResult {
        cashBalance = cashBalanceIn
        if (mode == Mode.LIVE && holdingDetails != null) holdingDetails.forEach { (k, v) -> holdings[k] = v }

        var anyTradesThisCycle = false
        var stateChanged = false
        var harvestedAmount = 0.0
        val tradedSymbols = mutableListOf<String>()

        var currentHoldingsValue = 0.0
        portfolioSummary.forEach { currentHoldingsValue += it.Value }
        val currentTotalValue = currentHoldingsValue + cashBalance

        portfolioSummary.forEach { row ->
            if (row.Value > 1.0 && (baselines[row.Symbol] ?: 0.0) <= 0.0) {
                baselines[row.Symbol] = row.Value
                stateChanged = true
            }
            if (!lastActionTimestamps.containsKey(row.Symbol) && (baselines[row.Symbol] ?: 0.0) > 0.0) {
                lastActionTimestamps[row.Symbol] = epochMillis()
                stateChanged = true
            }
        }

        // ── Crash protection: detect, suppress, recover ──
        // Phase 1: Check exit conditions for existing CP (partial recovery)
        val cpRecoveryPercent = getGenomicParam(GenomeParam.CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        for (row in portfolioSummary) {
            val trough = crashProtectionState[row.Symbol] ?: continue
            val baseline = baselines[row.Symbol] ?: continue
            val drop = baseline - trough
            if (drop <= 0.0) continue
            val recoveryNeeded = drop * cpRecoveryPercent
            if (row.Value >= trough + recoveryNeeded) {
                // Partial recovery achieved: exit CP, reset baseline
                val newBaseline = row.Value
                baselines[row.Symbol] = newBaseline
                crashProtectionState.remove(row.Symbol)
                stateChanged = true
            }
        }

        // Phase 2: Detect new crash entries (before harvest)
        val cpTriggerAsset = getGenomicParam(GenomeParam.CP_TRIGGER_ASSET_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        val cpMinNegDev = getGenomicParam(GenomeParam.CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        if (cpTriggerAsset > 0.0) {
            for (row in portfolioSummary) {
                if (row.Symbol in crashProtectionState) continue  // already in CP
                val baseline = baselines[row.Symbol] ?: continue
                if (baseline <= 0.0) continue
                val deviation = (row.Value - baseline) / baseline  // negative during drawdown
                if (deviation < 0.0 && -deviation >= cpTriggerAsset && -deviation >= cpMinNegDev) {
                    crashProtectionState[row.Symbol] = row.Value  // record trough
                    stateChanged = true
                }
            }
        }

        // Harvest logic
        for (row in portfolioSummary) {
            // Crash protection: suppress harvest for symbols in CP
            if (row.Symbol in crashProtectionState) continue

            val baseline = baselines[row.Symbol] ?: row.Value
            val surplus = row.Value - baseline
            val minSurplus = getGenomicParam(GenomeParam.MIN_SURPLUS_FOR_HARVEST, row.Symbol)
            val enableHarvest = genome.getBoolean("ENABLE_PORTFOLIO_HARVEST", true)

            if (enableHarvest && surplus >= minSurplus && surplus > 0.0) {
                val takePercent = getGenomicParam(GenomeParam.HARVEST_TAKE_PERCENT, row.Symbol)
                val take = surplus * takePercent
                harvestedAmount += take
                totalHarvested += take
                cashBalance += take
                // Mark harvested portion as consumed by raising baseline to avoid repeated harvesting of the same surplus
                baselines[row.Symbol] = baseline + take
                anyTradesThisCycle = true
                stateChanged = true
                tradedSymbols.add(row.Symbol)
            }
        }

        // ── Rebalance execution: act on previously scheduled rebalances ──
        // If a rebalance was scheduled on a prior cycle, execute it now:
        // reset the baseline to the current value so harvest surplus tracking
        // resumes from the rebalanced anchor.
        val toExecute = rebalanceState.keys.toList()
        for (sym in toExecute) {
            val row = portfolioSummary.find { it.Symbol == sym }
            if (row != null && row.Value > 0.0) {
                baselines[sym] = row.Value
                rebalanceState.remove(sym)
                stateChanged = true
            }
        }

        // ── Rebalance scheduling ──
        // If FLAT_REBALANCE_TRIGGER_PERCENT is small and deviation exists, schedule
        val rebalanceTrigger = getGenomicParam(GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT, portfolioSummary.firstOrNull()?.Symbol ?: "")
        if (rebalanceTrigger > 0 && portfolioSummary.isNotEmpty()) {
            for (row in portfolioSummary) {
                val baseline = baselines[row.Symbol] ?: row.Value
                if (baseline <= 0.0) continue
                val dev = (row.Value - baseline) / baseline
                if (dev > rebalanceTrigger && row.Symbol !in rebalanceState) {
                    rebalanceState[row.Symbol] = mapOf("scheduledAt" to epochMillis())
                    stateChanged = true
                }
            }
        }

        // ── Reinvest: use harvested cash to buy dip symbols ──
        var reinvestedAmount = 0.0
        val reinvestedSymbols = mutableListOf<String>()
        val reinvestPct = getGenomicParam(GenomeParam.HARVEST_ALLOC_REINVEST_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        val minNegDev = getGenomicParam(GenomeParam.MIN_NEGATIVE_DEVIATION_FOR_REINVEST,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        val minBuy = getGenomicParam(GenomeParam.MIN_REINVEST_BUY_USD,
            portfolioSummary.firstOrNull()?.Symbol ?: "")

        if (reinvestPct > 0.0 && harvestedAmount > 0.0) {
            val reinvestBudget = harvestedAmount * reinvestPct
            // Find symbols with negative deviation (below baseline, not in CP)
            val dipSymbols = portfolioSummary.filter { row ->
                row.Symbol !in crashProtectionState &&
                (baselines[row.Symbol] ?: 0.0) > 0.0 &&
                ((row.Value - (baselines[row.Symbol] ?: row.Value)) / (baselines[row.Symbol] ?: row.Value)) < -minNegDev
            }
            if (dipSymbols.isNotEmpty()) {
                val perSymbol = reinvestBudget / dipSymbols.size
                if (perSymbol >= minBuy) {
                    for (row in dipSymbols) {
                        val buyAmount = perSymbol.coerceAtMost(cashBalance)
                        if (buyAmount >= minBuy) {
                            cashBalance -= buyAmount
                            reinvestedAmount += buyAmount
                            // Add to holdings (simulated: quantity = buyAmount / price)
                            val existingQty = holdings[row.Symbol]?.rawQuantity ?: 0.0
                            holdings[row.Symbol] = Holding(existingQty + buyAmount / row.Price)
                            reinvestedSymbols.add(row.Symbol)
                            anyTradesThisCycle = true
                            stateChanged = true
                        }
                    }
                }
            }
        }

        return EngineResult(anyTradesThisCycle, harvestedAmount, tradedSymbols, emptyList(), false, stateChanged,
            reinvestedAmount = reinvestedAmount, reinvestedSymbols = reinvestedSymbols)
    }
}
