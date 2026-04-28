package borg.trikeshed.dreamer

import borg.trikeshed.common.System

class TradingEngine(
    val genome: Genome,
    val mode: Mode = Mode.SHADOW,
    initialCapital: Double = 0.0,
    initialHoldings: MutableMap<String, Holding> = mutableMapOf()
) {
    val baselines: MutableMap<String, Double> = mutableMapOf()
    val lastActionTimestamps: MutableMap<String, Long> = mutableMapOf()
    val rebalanceState: MutableMap<String, Any> = mutableMapOf()

    var cashBalance: Double = initialCapital
    val holdings: MutableMap<String, Holding> = initialHoldings
    var totalHarvested: Double = 0.0

    fun loadPersistedState(data: Map<String, Any?>?) {
        if (data == null) return
        (data["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { baselines[k] = it } }
    }

    fun getStateSnapshot(): Map<String, Any?> = mapOf(
        "baselines" to baselines.toMap(),
        "holdings" to holdings.mapValues { it.value.rawQuantity },
        "cashBalance" to cashBalance,
    )

    fun injectSimulationState(snapshot: Map<String, Any?>) {
        snapshot["cashBalance"]?.let { if (it is Number) cashBalance = it.toDouble() }
        (snapshot["holdings"] as? Map<String, Any?>)?.forEach { (k, v) -> if (v is Number) holdings[k] = Holding(v.toDouble()) }
        (snapshot["baselines"] as? Map<String, Any?>)?.forEach { (k, v) -> (v as? Number)?.toDouble()?.let { baselines[k] = it } }
    }

    fun getGenomicParam(key: String, symbol: String? = null): Double {
        if (symbol != null) {
            val ov = genome.overridesFor(symbol)
            val v = ov?.get(key)
            if (v is Number) return v.toDouble()
            if (v is String) return v.toDoubleOrNull() ?: genome.getDouble(key)
        }
        return genome.getDouble(key)
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
                lastActionTimestamps[row.Symbol] = System.currentTimeMillis()
                stateChanged = true
            }
        }

        // Harvest logic
        for (row in portfolioSummary) {
            val baseline = baselines[row.Symbol] ?: row.Value
            val surplus = row.Value - baseline
            val minSurplus = getGenomicParam("MIN_SURPLUS_FOR_HARVEST", row.Symbol)
            val enableHarvest = genome.getBoolean("ENABLE_PORTFOLIO_HARVEST", true)

            if (enableHarvest && surplus >= minSurplus && surplus > 0.0) {
                val takePercent = getGenomicParam("HARVEST_TAKE_PERCENT", row.Symbol)
                val take = surplus * takePercent
                harvestedAmount += take
                totalHarvested += take
                cashBalance += take
                anyTradesThisCycle = true
                stateChanged = true
                tradedSymbols.add(row.Symbol)
            }
        }

        // Rebalance scheduling - not implemented (left as red)
        // If FLAT_REBALANCE_TRIGGER_PERCENT is small and deviation exists, schedule
        val rebalanceTrigger = getGenomicParam("FLAT_REBALANCE_TRIGGER_PERCENT", portfolioSummary.firstOrNull()?.Symbol ?: "")
        if (rebalanceTrigger > 0 && portfolioSummary.isNotEmpty()) {
            val sym = portfolioSummary.first().Symbol
            val baseline = baselines[sym] ?: portfolioSummary.first().Value
            val dev = (portfolioSummary.first().Value - baseline) / baseline
            if (dev > rebalanceTrigger) {
                rebalanceState[sym] = mapOf("scheduledAt" to System.currentTimeMillis())
                stateChanged = true
            }
        }

        return EngineResult(anyTradesThisCycle, harvestedAmount, tradedSymbols, emptyList(), false, stateChanged)
    }
}
