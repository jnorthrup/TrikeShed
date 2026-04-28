package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Replays Binance archive CSV through the block/cursor back-test path.
 */
class SimulationReplay(
    private val genome: Genome = defaultGenome(),
    private val mode: Mode = Mode.SHADOW,
    private val initialCapital: Double,
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
}
