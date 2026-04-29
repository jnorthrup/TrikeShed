package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.indicator.Stochastic
import borg.trikeshed.miniduck.HarnessStochasticCache

/**
 * Simple KernelFeatureTransformer for MiniCursor that appends technical indicators.
 *  Expects source rows to be DocRowVec with a numeric "close" field.
 */
interface KernelFeatureTransformer {
    fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor
}

/**
 * Example transformer: adds log_return, sma_short, sma_long, rolling_vol columns.
 */
class ExampleKernelTransformer : KernelFeatureTransformer {
    override fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor {
        val shortW = (params["short_ma"] as? Int) ?: 10
        val longW = (params["long_ma"] as? Int) ?: 50
        val volW = (params["vol_window"] as? Int) ?: 20

        // Extract close price series
        val closeSeries: Series<Double> = cursor.size j { i ->
            val row = cursor.row(i)
            val v = row.getValue("close")
            when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: Double.NaN
                else -> Double.NaN
            }
        }

        val logs = logReturn(closeSeries)
        val smaShort = sma(closeSeries, shortW)
        val smaLong = sma(closeSeries, longW)
        val vol = rollingStd(logs, volW)

        // Widen each row with appended columns (include stochastic if available)
        return cursor.size j { i -> 
            val row = cursor.row(i)
            val baseKeys = row.keys()
            val baseCells = row.cells()

            val symbol = params["symbol"] as? String ?: params["sym"] as? String
            val timeframe = params["timeframe"] as? String ?: params["tf"] as? String
            val kP = (params["kPeriod"] as? Int) ?: 14
            val dP = (params["dPeriod"] as? Int) ?: 3
            val stoch = if (symbol != null && timeframe != null) HarnessStochasticCache.get(symbol, timeframe, kP, dP) else null
            val stochK = stoch?.k?.let { s -> if (i < s.size) s[i] else Double.NaN } ?: Double.NaN
            val stochD = stoch?.d?.let { s -> if (i < s.size) s[i] else Double.NaN } ?: Double.NaN

            val newKeys = baseKeys + listOf("log_return", "sma_short", "sma_long", "rolling_vol", "stoch_k", "stoch_d")
            val newCells = baseCells + listOf(logs[i], smaShort[i], smaLong[i], vol[i], stochK, stochD)
            val widenedDoc = DocRowVec(keys = newKeys, cells = newCells)
            widenedDoc.toRowVec()
        }
    }
}

private fun borg.trikeshed.cursor.RowVec.keys(): List<String> = List(size) { index -> this[index].b().name }

private fun borg.trikeshed.cursor.RowVec.cells(): List<Any?> = List(size) { index -> this[index].a }
