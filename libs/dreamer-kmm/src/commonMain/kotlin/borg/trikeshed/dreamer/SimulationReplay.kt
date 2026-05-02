package borg.trikeshed.dreamer

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.runBlockingCommon

/**
 * Replays Binance archive CSV through the block/cursor back-test path.
 */
class SimulationReplay(
    public val genome: Genome = defaultGenome(),
    public val mode: Mode = Mode.SHADOW,
    public val initialCapital: Double,
) {
    fun replayCsv(
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
        return runBlockingCommon {
            simulateTicks(block.seal().asCursor(), engine, initialCapital,
                annualizationFactor = timespan.annualizationFactor)
        }
    }

	/** Build a [BacktestReport] from this replay's result. */
	fun toBacktestReport(csvText: String, symbol: String, timespan: TimeSpan): BacktestReport =
		replayCsv(csvText, symbol, timespan).toBacktestReport()
}
