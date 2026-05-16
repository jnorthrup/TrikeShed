package borg.trikeshed.integration

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.indicator.Stochastic
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.cursor.at
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

data class BinanceKlineKey(
    val symbol: CharSequence,
    val interval: CharSequence,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class BinanceStochasticKey(
    val kline: BinanceKlineKey,
    val kPeriod: Int = 14,
    val dPeriod: Int = 3,
)

data class BinanceStochasticKline(
    val key: BinanceStochasticKey,
    val cursor: Cursor,
    val stochastic: Stochastic.Result,
)

interface BinanceKlineProvider {
    suspend fun open(key: BinanceKlineKey): Cursor
}

class BinanceKlineSourceProvider(
    private val blockCapacity: Int = 500,
    private val maxConcurrentFetches: Int = 4,
) : BinanceKlineProvider {
    override suspend fun open(key: BinanceKlineKey): Cursor = BinanceKlineSource(
        symbol = key.symbol,
        interval = key.interval,
        startDate = key.startDate,
        endDate = key.endDate,
        blockCapacity = blockCapacity,
        maxConcurrentFetches = maxConcurrentFetches,
    ).fetchCursor()
}

object ProcessLocalBinanceStochasticCache {
    private val mutex = Mutex()
    private val values = LongLongSeries.build { putAll(mapOf(<BinanceStochasticKey, BinanceStochasticKline>() })

    suspend fun getOrLoad(
        key: BinanceKlineKey,
        provider: BinanceKlineProvider,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
    ): BinanceStochasticKline {
        val stochasticKey = BinanceStochasticKey(key, kPeriod, dPeriod)
        return mutex.withLock {
            values.getOrPut(stochasticKey) {
                val cursor = provider.open(key)
                BinanceStochasticKline(
                    key = stochasticKey,
                    cursor = cursor,
                    stochastic = computeStochastic(cursor, kPeriod, dPeriod),
                )
            }
        }
    }

    fun clearForTests() {
        values.clear()
    }

    fun sizeForTests(): Int = values.size
}

fun computeStochastic(
    cursor: Cursor,
    kPeriod: Int = 14,
    dPeriod: Int = 3,
): Stochastic.Result {
    val highs = cursor.ohlcSeries("high")
    val lows = cursor.ohlcSeries("low")
    val closes = cursor.ohlcSeries("close")
    return Stochastic.compute(highs, lows, closes, kPeriod, dPeriod)
}

private fun Cursor.ohlcSeries(column: CharSequence): Series<Double> {
    val n = size
    return n j { index: Int ->
        val row = at(index)
        val value = row.getValue(column)
        when (value) {
            is Number -> value.toDouble()
            is CharSequence -> value.toDouble()
            else -> throw IllegalArgumentException("row $index column $column is not numeric: $value")
        }
    }
}
