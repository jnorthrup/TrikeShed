package borg.trikeshed.dreamer

import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.size

data class HarnessReplayInput(
    val key: KlineSeriesKey,
    val block: KlineBlock,
)

data class HarnessFrame(
    val tick: Int,
    val openTime: Long,
    val rows: List<PortfolioInput>,
    val bag: StochasticBagSelection,
)

data class HarnessCycle(
    val frame: HarnessFrame,
    val result: EngineResult,
    val totalValue: Double,
)

data class HarnessRunResult(
    val cycles: List<HarnessCycle>,
    val finalCash: Double,
    val finalTotalValue: Double,
    val walletJournal: List<WalletJournalEntry> = emptyList(),
)

class DreamerAgent(
    private val engine: TradingEngine,
) {
    suspend fun run(rows: List<PortfolioInput>): EngineResult =
        engine.update(
            portfolioSummary = rows.flatMap(::portfolioInputToRows),
            api = null,
            cashBalanceIn = engine.cashBalance,
            holdingDetails = null,
        )
}

class RealtimeHarness(
    private val genome: Genome,
    private val initialCapital: Double,
    private val mode: Mode = Mode.SHADOW,
    private val stochasticSeed: Int = 1,
    private val stochasticSpanLength: Int = 16,
) {
    suspend fun replay(inputs: List<HarnessReplayInput>): HarnessRunResult {
        require(inputs.isNotEmpty()) { "RealtimeHarness requires at least one kline block" }
        val sources = inputs.map {
            check(it.block.state == KlineBlock.State.SEALED) { "KlineBlock for ${it.key.symbol} must be sealed" }
            KlineSeriesSource(it.key, it.block.asCursor())
        }
        val tickCount = sources.minOf { it.cursor.size }
        if (tickCount == 0) return HarnessRunResult(emptyList(), initialCapital, initialCapital)

        val engine = TradingEngine(genome, mode, initialCapital = initialCapital)
        val agent = DreamerAgent(engine)
        val wallet = SimWallet()
        wallet.record("USDT", initialCapital)
        val bag = StochasticBag(sources, stochasticSeed)
        val quantities = initialQuantities(sources)
        val cycles = mutableListOf<HarnessCycle>()

        for (tick in 0 until tickCount) {
            val rows = sources.map { source ->
                source.portfolioInputAt(tick, quantities[source.key.symbol] ?: 0.0)
            }
            val selection = bag.select(maxWindows = sources.size, spanLength = stochasticSpanLength)
            val openTime = rows.minOfOrNull { it.openTime } ?: 0L
            val frame = HarnessFrame(tick, openTime, rows, selection)
            val result = agent.run(rows)
            val totalValue = engine.cashBalance + rows.sumOf { it.value }
            val prices = rows.associate { baseAsset(it.symbol) to it.price } + ("USDT" to 1.0)
            wallet.markToMarket(prices, note = "tick=$tick openTime=$openTime total=$totalValue")
            result.tradedSymbols.forEach { symbol ->
                val row = rows.firstOrNull { it.symbol == symbol }
                wallet.recordSignal(
                    symbol = symbol,
                    note = "Dreamer cycle emitted trade signal at tick=$tick",
                    price = row?.price ?: 0.0,
                    quantity = row?.quantity ?: 0.0,
                )
            }
            cycles += HarnessCycle(frame, result, totalValue)
        }

        return HarnessRunResult(
            cycles = cycles,
            finalCash = engine.cashBalance,
            finalTotalValue = cycles.lastOrNull()?.totalValue ?: initialCapital,
            walletJournal = wallet.journal(),
        )
    }

    private fun initialQuantities(sources: List<KlineSeriesSource>): Map<String, Double> {
        val allocation = initialCapital / sources.size
        return sources.associate { source ->
            val first = source.cursor.rowAt(0)
            val price = first.doubleValue("close").takeIf { it > 0.0 } ?: first.doubleValue("open").coerceAtLeast(1.0)
            source.key.symbol to allocation / price
        }
    }

    private fun KlineSeriesSource.portfolioInputAt(tick: Int, quantity: Double): PortfolioInput {
        val row = cursor.rowAt(tick)
        val price = row.doubleValue("close").takeIf { it > 0.0 } ?: row.doubleValue("open")
        return PortfolioInput(
            symbol = key.symbol,
            openTime = row.longValue("openTime"),
            quantity = quantity,
            price = price,
            value = quantity * price,
        )
    }
}

private fun MiniCursor.rowAt(index: Int): RowVec = at(index)

private fun RowVec.doubleValue(key: String): Double {
    val value = getValue(key)
    return when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDouble()
        else -> error("$key must be numeric, got $value")
    }
}

private fun RowVec.longValue(key: String): Long {
    val value = getValue(key)
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> error("$key must be numeric, got $value")
    }
}

private fun baseAsset(symbol: String): String =
    if (symbol.endsWith("USDT") && symbol.length > 4) symbol.substring(0, symbol.length - 4) else symbol
