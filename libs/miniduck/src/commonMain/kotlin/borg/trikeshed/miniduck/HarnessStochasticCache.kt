package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.indicator.Stochastic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight harness-local draw-through stochastic cache.
 * Computes Stochastic.Result once per (symbol,timeframe,k,d) and stores in-memory
 * so transformers can read the precomputed series without recomputing.
 */
data class HarnessStochasticKey(val symbol: CharSequence, val timeframe: CharSequence, val kPeriod: Int = 14, val dPeriod: Int = 3)

object HarnessStochasticCache {
    private val mutex = Mutex()
    private val values = mutableMapOf<HarnessStochasticKey, Stochastic.Result>()

    suspend fun ensureCached(
        symbol: CharSequence,
        timeframe: CharSequence,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
        cursorSupplier: () -> Cursor,
    ) {
        val key = HarnessStochasticKey(symbol, timeframe, kPeriod, dPeriod)
        mutex.withLock {
            if (!values.containsKey(key)) {
                val cursor = cursorSupplier()
                val highs = cursor.ohlcSeries("high")
                val lows = cursor.ohlcSeries("low")
                val closes = cursor.ohlcSeries("close")
                val res = Stochastic.compute(highs, lows, closes, kPeriod, dPeriod)
                values[key] = res
            }
        }
    }

    fun get(symbol: CharSequence, timeframe: CharSequence, kPeriod: Int = 14, dPeriod: Int = 3): Stochastic.Result? {
        return values[HarnessStochasticKey(symbol, timeframe, kPeriod, dPeriod)]
    }
}

    /** Extract an OHLC column as a Double Series. */
    private fun Cursor.ohlcSeries(column: CharSequence): Series<Double> {
        val n = this.size
        return n j { index: Int ->
            val row = this.row(index)
            val value = row.getValue(column)
            when (value) {
                is Number -> value.toDouble()
                is CharSequence -> value.toString().toDouble()
                else -> throw IllegalArgumentException("row $index column $column is not numeric: $value")
            }
        }
    }
