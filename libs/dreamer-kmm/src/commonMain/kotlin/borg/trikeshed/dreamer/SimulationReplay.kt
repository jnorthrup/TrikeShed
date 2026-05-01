package borg.trikeshed.dreamer

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Replays Binance archive CSV through the block/cursor back-test path.
 */
class SimulationReplay(
    public val genome: Genome = defaultGenome(),
    public val mode: Mode = Mode.SHADOW,
    public val initialCapital: Double,
) {
    suspend fun replayCsv(
        csvText: String,
        symbol: String,
        timespan: TimeSpan,
    ): BacktestResult {
        val n = csvText.length
        val chars = n j { i: Int -> csvText[i] }
        val klines = klinesFromCsv(chars, symbol, timespan)

        val block = KlineBlock.mutable(timespan)
        for (i in 0 until klines.size) {
            block.append(klines.b(i).toKline())
        }

        val engine = TradingEngine(genome, mode, initialCapital = initialCapital)
        return simulateTicks(block.seal().asCursor(), engine, initialCapital)
    }

	/** Build a [BacktestReport] from this replay's result. */
	suspend fun toBacktestReport(csvText: String, symbol: String, timespan: TimeSpan): BacktestReport =
		replayCsv(csvText, symbol, timespan).toBacktestReport()
}
