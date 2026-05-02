@file:Suppress("TestFunctionName")

package borg.trikeshed.dreamer

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Build a sealed [KlineBlock] from a list of close prices.
 *
 * Each row uses the previous close as open; timestamps increment by 60s
 * starting from [startOpenTime]. This is the shared test helper to avoid
 * duplicating block() across test files.
 */
public fun block(
    base: String,
    prices: List<Double>,
    startOpenTime: Long = 1_704_067_200_000L,
    timespan: TimeSpan = TimeSpan.Minutes1,
): KlineBlock {
    val block = KlineBlock.mutable(timespan)
    prices.forEachIndexed { index, close ->
        val open = if (index == 0) close else prices[index - 1]
        block.append(
            Kline(
                symbol = "${base}USDT",
                timespan = timespan,
                openTime = startOpenTime + (index * timespan.seconds * 1_000L),
                open = open,
                high = maxOf(open, close) + 1.0,
                low = minOf(open, close) - 1.0,
                close = close,
                volume = 100.0 + index,
            )
        )
    }
    return block.seal()
}

/**
 * Build a [KlineSeriesSource] from a list of close prices.
 */
public fun source(
    base: String,
    prices: List<Double>,
    quote: String = "USDT",
    timespan: TimeSpan = TimeSpan.Minutes1,
): KlineSeriesSource {
    val key = klineSeriesKey(base, quote, timespan)
    return KlineSeriesSource(key, block(base, prices, timespan = timespan).asCursor())
}

public fun archiveInputs(config: StochasticTrainingConfig): List<HarnessReplayInput> {
    val feed = BinanceVisionKlineFeed()
    return config.bases.mapIndexed { index, base ->
        val key = klineSeriesKey(base, config.quote, config.timespan)
        val csv = generatedArchiveCsv(
            symbol = key.symbol,
            rows = config.rowsPerSeries,
            timespan = config.timespan,
            startOpenTime = config.startOpenTime,
            assetIndex = index,
            seed = config.seed,
        )
        val parsed = feed.parseCachedCsv(key, csv)
        HarnessReplayInput(key, parsed.block)
    }
}

public fun generatedArchiveCsv(
    symbol: String,
    rows: Int,
    timespan: TimeSpan,
    startOpenTime: Long,
    assetIndex: Int,
    seed: Int,
): String {
    require(rows > 0) { "rows must be positive" }
    val random = Random(seed + assetIndex * 65_537)
    val intervalMillis = timespan.seconds * 1_000L
    val basePrices = doubleArrayOf(69_000.0, 3_800.0, 142.0, 580.0, 0.62, 0.54, 98.0, 7.2)
    var price = basePrices[assetIndex % basePrices.size]
    val out = StringBuilder()
    out.append("open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n")
    for (row in 0 until rows) {
        val openTime = startOpenTime + row * intervalMillis
        val open = price
        val drift = 0.00005 * (assetIndex + 1)
        val wave = sin((row + assetIndex * 13).toDouble() / 17.0) * 0.0025 +
            cos((row + assetIndex * 7).toDouble() / 43.0) * 0.0012
        val noise = random.nextDouble(-0.0018, 0.0018)
        val close = max(0.000001, open * (1.0 + drift + wave + noise))
        val high = max(open, close) * (1.0 + random.nextDouble(0.0002, 0.0030))
        val low = min(open, close) * (1.0 - random.nextDouble(0.0002, 0.0030))
        val volume = 100.0 + assetIndex * 19.0 + row % 37 + random.nextDouble(0.0, 25.0)
        val quoteVolume = volume * close
        val trades = 25 + (row + assetIndex * 11) % 95
        val takerBase = volume * random.nextDouble(0.35, 0.65)
        val takerQuote = takerBase * close
        out.append(openTime).append(',')
            .append(open).append(',')
            .append(high).append(',')
            .append(low).append(',')
            .append(close).append(',')
            .append(volume).append(',')
            .append(openTime + intervalMillis - 1).append(',')
            .append(quoteVolume).append(',')
            .append(trades).append(',')
            .append(takerBase).append(',')
            .append(takerQuote).append(",0\n")
        price = close
    }
    return out.toString()
}
