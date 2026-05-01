package borg.trikeshed.dreamer

import kotlin.time.Clock

/** Epoch milliseconds, with graceful fallback when Clock is unavailable (e.g. wasm/js test env). */
private fun epochMillis(): Long = try {
    Clock.System.now().toEpochMilliseconds()
} catch (_: Throwable) {
    0L
}

/**
 * Orchestrates backtesting and shadow trading by tracking portfolio state.
 *
 * **Concurrency Contract**: This class is not thread-safe. All access and mutations
 * must be synchronized externally or guaranteed to run sequentially within a single-threaded context.
 */
data class EngineState(
    val baselines: Map<String, Double> = emptyMap(),
    val lastActionTimestamps: Map<String, Long> = emptyMap(),
    val rebalanceState: Map<String, Any> = emptyMap(),
    /** Crash protection state per symbol: stores the trough value at CP activation */
    val crashProtectionState: Map<String, Double> = emptyMap(),
    val cashBalance: Double = 0.0,
    val holdings: Map<String, Holding> = emptyMap(),
    val totalHarvested: Double = 0.0
)

/**
 * Orchestrates backtesting and shadow trading by tracking portfolio state.
 *
 * **Concurrency Contract**: This class is not thread-safe. All access and mutations
 * must be synchronized externally or guaranteed to run sequentially within a single-threaded context.
 */
class TradingEngine(
    val genome: Genome,
    val mode: Mode = Mode.SHADOW,
    initialCapital: Double = 0.0,
    initialHoldings: Map<String, Holding> = emptyMap()
) {
    var state = EngineState(
        cashBalance = initialCapital,
        holdings = initialHoldings.toMap()
    )

    val baselines get() = state.baselines
    val lastActionTimestamps get() = state.lastActionTimestamps
    val rebalanceState get() = state.rebalanceState
    val crashProtectionState get() = state.crashProtectionState
    var cashBalance: Double
        get() = state.cashBalance
        set(value) { state = state.copy(cashBalance = value) }
    val holdings get() = state.holdings
    val totalHarvested get() = state.totalHarvested

    /** Check if a symbol is currently in crash protection mode */
    fun isCrashProtected(symbol: String): Boolean = symbol in state.crashProtectionState

    fun loadPersistedState(data: Map<String, Any?>?) {
        if (data == null) return
        val newBaselines = state.baselines.toMutableMap()
        (data["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { newBaselines[k] = it } }
        val newCpState = state.crashProtectionState.toMutableMap()
        (data["crashProtectionState"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { newCpState[k] = it } }
        state = state.copy(baselines = newBaselines, crashProtectionState = newCpState)
    }

    fun getStateSnapshot(): Map<String, Any?> = mapOf(
        "baselines" to state.baselines.toMap(),
        "holdings" to state.holdings.mapValues { it.value.rawQuantity },
        "cashBalance" to state.cashBalance,
        "crashProtectionState" to state.crashProtectionState.toMap(),
    )

    fun injectSimulationState(snapshot: Map<String, Any?>) {
        var newCash = state.cashBalance
        snapshot["cashBalance"]?.let { if (it is Number) newCash = it.toDouble() }
        val newHoldings = state.holdings.toMutableMap()
        (snapshot["holdings"] as? Map<String, Any?>)?.forEach { (k, v) -> if (v is Number) newHoldings[k] = Holding(v.toDouble()) }
        val newBaselines = state.baselines.toMutableMap()
        (snapshot["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { newBaselines[k] = it } }
        val newCpState = state.crashProtectionState.toMutableMap()
        (snapshot["crashProtectionState"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { newCpState[k] = it } }

        state = state.copy(
            cashBalance = newCash,
            holdings = newHoldings,
            baselines = newBaselines,
            crashProtectionState = newCpState
        )
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
        // BacktestModels.simulateTicks relies on `cashBalanceIn` to override the cash balance
        // to avoid double counting the initial holdings. We MUST respect cashBalanceIn.
        var newCash = cashBalanceIn
        val newHoldings = state.holdings.toMutableMap()
        val newBaselines = state.baselines.toMutableMap()
        val newLastAction = state.lastActionTimestamps.toMutableMap()
        val newRebalance = state.rebalanceState.toMutableMap()
        val newCpState = state.crashProtectionState.toMutableMap()
        var newTotalHarvested = state.totalHarvested

        if (mode == Mode.LIVE && holdingDetails != null) holdingDetails.forEach { (k, v) -> newHoldings[k] = v }

        var anyTradesThisCycle = false
        var stateChanged = false
        var harvestedAmount = 0.0
        val tradedSymbols = mutableListOf<String>()

        var currentHoldingsValue = 0.0
        portfolioSummary.forEach { currentHoldingsValue += it.Value }
        val currentTotalValue = currentHoldingsValue + newCash

        portfolioSummary.forEach { row ->
            if (row.Value > 1.0 && (newBaselines[row.Symbol] ?: 0.0) <= 0.0) {
                newBaselines[row.Symbol] = row.Value
                stateChanged = true
            }
            if (!newLastAction.containsKey(row.Symbol) && (newBaselines[row.Symbol] ?: 0.0) > 0.0) {
                newLastAction[row.Symbol] = epochMillis()
                stateChanged = true
            }
        }

        // ── Crash protection: detect, suppress, recover ──
        val cpRecoveryPercent = getGenomicParam(GenomeParam.CRASH_PROTECTION_PARTIAL_RECOVERY_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        for (row in portfolioSummary) {
            val trough = newCpState[row.Symbol] ?: continue
            val baseline = newBaselines[row.Symbol] ?: continue
            val drop = baseline - trough
            if (drop <= 0.0) continue
            val recoveryNeeded = drop * cpRecoveryPercent
            if (row.Value >= trough + recoveryNeeded) {
                val newBaseline = row.Value
                newBaselines[row.Symbol] = newBaseline
                newCpState.remove(row.Symbol)
                stateChanged = true
            }
        }

        val cpTriggerAsset = getGenomicParam(GenomeParam.CP_TRIGGER_ASSET_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        val cpMinNegDev = getGenomicParam(GenomeParam.CP_TRIGGER_MIN_NEGATIVE_DEV_PERCENT,
            portfolioSummary.firstOrNull()?.Symbol ?: "")
        if (cpTriggerAsset > 0.0) {
            for (row in portfolioSummary) {
                if (row.Symbol in newCpState) continue
                val baseline = newBaselines[row.Symbol] ?: continue
                if (baseline <= 0.0) continue
                val deviation = (row.Value - baseline) / baseline
                if (deviation < 0.0 && -deviation >= cpTriggerAsset && -deviation >= cpMinNegDev) {
                    newCpState[row.Symbol] = row.Value
                    stateChanged = true
                }
            }
        }

        // Harvest logic
        for (row in portfolioSummary) {
            if (row.Symbol in newCpState) continue

            val baseline = newBaselines[row.Symbol] ?: row.Value
            val surplus = row.Value - baseline
            val minSurplus = getGenomicParam(GenomeParam.MIN_SURPLUS_FOR_HARVEST, row.Symbol)
            val enableHarvest = genome.getBoolean("ENABLE_PORTFOLIO_HARVEST", true)

            if (enableHarvest && surplus >= minSurplus && surplus > 0.0) {
                val takePercent = getGenomicParam(GenomeParam.HARVEST_TAKE_PERCENT, row.Symbol)
                val take = surplus * takePercent
                harvestedAmount += take
                newTotalHarvested += take
                newCash += take
                newBaselines[row.Symbol] = baseline + take
                anyTradesThisCycle = true
                stateChanged = true
                tradedSymbols.add(row.Symbol)
            }
        }

        val toExecute = newRebalance.keys.toList()
        for (sym in toExecute) {
            val row = portfolioSummary.find { it.Symbol == sym }
            if (row != null && row.Value > 0.0) {
                newBaselines[sym] = row.Value
                newRebalance.remove(sym)
                stateChanged = true
            }
        }

        val rebalanceTrigger = getGenomicParam(GenomeParam.FLAT_REBALANCE_TRIGGER_PERCENT, portfolioSummary.firstOrNull()?.Symbol ?: "")
        if (rebalanceTrigger > 0 && portfolioSummary.isNotEmpty()) {
            for (row in portfolioSummary) {
                val baseline = newBaselines[row.Symbol] ?: row.Value
                if (baseline <= 0.0) continue
                val dev = (row.Value - baseline) / baseline
                if (dev > rebalanceTrigger && row.Symbol !in newRebalance) {
                    newRebalance[row.Symbol] = mapOf("scheduledAt" to epochMillis())
                    stateChanged = true
                }
            }
        }

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
            val dipSymbols = portfolioSummary.filter { row ->
                row.Symbol !in newCpState &&
                (newBaselines[row.Symbol] ?: 0.0) > 0.0 &&
                ((row.Value - (newBaselines[row.Symbol] ?: row.Value)) / (newBaselines[row.Symbol] ?: row.Value)) < -minNegDev
            }
            if (dipSymbols.isNotEmpty()) {
                val perSymbol = reinvestBudget / dipSymbols.size
                if (perSymbol >= minBuy) {
                    for (row in dipSymbols) {
                        val buyAmount = perSymbol.coerceAtMost(newCash)
                        if (buyAmount >= minBuy) {
                            newCash -= buyAmount
                            reinvestedAmount += buyAmount
                            val existingQty = newHoldings[row.Symbol]?.rawQuantity ?: 0.0
                            newHoldings[row.Symbol] = Holding(existingQty + buyAmount / row.Price)
                            reinvestedSymbols.add(row.Symbol)
                            anyTradesThisCycle = true
                            stateChanged = true
                        }
                    }
                }
            }
        }

        state = state.copy(
            cashBalance = newCash,
            holdings = newHoldings,
            baselines = newBaselines,
            lastActionTimestamps = newLastAction,
            rebalanceState = newRebalance,
            crashProtectionState = newCpState,
            totalHarvested = newTotalHarvested
        )


        return EngineResult(anyTradesThisCycle, harvestedAmount, tradedSymbols, emptyList(), false, stateChanged,
            reinvestedAmount = reinvestedAmount, reinvestedSymbols = reinvestedSymbols)
    }
}
