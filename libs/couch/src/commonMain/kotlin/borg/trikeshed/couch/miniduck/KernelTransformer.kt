package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

/** Simple KernelFeatureTransformer for MiniCursor that appends technical indicators.
 *  Expects source rows to be DocRowVec with a numeric "close" field.
 */
interface KernelFeatureTransformer {
    fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor
}

/** Example transformer: adds log_return, sma_short, sma_long, rolling_vol columns. */
class ExampleKernelTransformer : KernelFeatureTransformer {
    override fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor {
        val shortW = (params["short_ma"] as? Int) ?: 10
        val longW = (params["long_ma"] as? Int) ?: 50
        val volW = (params["vol_window"] as? Int) ?: 20

        // Extract close price series
        val closeSeries: Series<Double> = cursor.size j { i ->
            val row = cursor.at(i)
            val v = (row as? DocRowVec)?.get("close")
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

        // Widen each row with appended columns
        return cursor.size j { i ->
            val row = cursor.at(i)
            val baseDoc = row as? DocRowVec
            val baseKeys = baseDoc?.keys ?: emptyList()
            val baseCells = baseDoc?.cells ?: emptyList()

            val newKeys = baseKeys + listOf("log_return", "sma_short", "sma_long", "rolling_vol")
            val newCells = baseCells + listOf(logs[i], smaShort[i], smaLong[i], vol[i])
            DocRowVec(keys = newKeys, cells = newCells, child = baseDoc?.child)
        }
    }
}
