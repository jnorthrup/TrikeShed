package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.indicator.Stochastic
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.at
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HarnessStochasticCacheTest {
    @Test
    fun ensureCachedStoresAndKernelTransformerReads() = runTest {
        val rows = listOf(
            DocRowVec(keys = listOf("high", "low", "close"), cells = listOf(100.0, 99.0, 99.5)),
            DocRowVec(keys = listOf("high", "low", "close"), cells = listOf(101.0, 100.0, 100.5)),
            DocRowVec(keys = listOf("high", "low", "close"), cells = listOf(102.0, 101.0, 101.5)),
            DocRowVec(keys = listOf("high", "low", "close"), cells = listOf(103.0, 102.0, 102.5)),
        )
        val cursor: Cursor = rows.size j { i: Int -> rows[i] }

        val symbol = "SYM"
        val timeframe = "1d"
        val k = 3
        val d = 2

        // Ensure cached (draw-through)
        HarnessStochasticCache.ensureCached(symbol, timeframe, k, d) { cursor }

        val cached = HarnessStochasticCache.get(symbol, timeframe, k, d)
        assertNotNull(cached, "HarnessStochasticCache.get returned null")

        // Build expected series directly from the cursor
        val highs: Series<Double> = cursor.size j { i: Int -> (cursor at i)["high"] as Double }
        val lows: Series<Double> = cursor.size j { i: Int -> (cursor at i)["low"] as Double }
        val closes: Series<Double> = cursor.size j { i: Int -> (cursor at i)["close"] as Double }

        val expected = Stochastic.compute(highs, lows, closes, k, d)

        // Compare cached vs expected
        for (i in 0 until cached.k.size) {
            val gotK = cached.k[i]
            val expK = expected.k[i]
            assertTrue(abs(gotK - expK) < 1e-9, "stoch k mismatch at $i: got=$gotK expected=$expK")
            val gotD = cached.d[i]
            val expD = expected.d[i]
            assertTrue(abs(gotD - expD) < 1e-9, "stoch d mismatch at $i: got=$gotD expected=$expD")
        }

        // Verify transformer attaches columns
        val transformer = ExampleKernelTransformer()
        val transformed = transformer.transform(cursor, mapOf("symbol" to symbol, "timeframe" to timeframe, "kPeriod" to k, "dPeriod" to d))
        for (i in 0 until transformed.size) {
            val row = transformed at i
            val stK = (row["stoch_k"] as? Double) ?: Double.NaN
            val stD = (row["stoch_d"] as? Double) ?: Double.NaN
            assertTrue(abs(stK - expected.k[i]) < 1e-9, "transformed stoch_k mismatch at $i: got=$stK expected=${expected.k[i]}")
            assertTrue(abs(stD - expected.d[i]) < 1e-9, "transformed stoch_d mismatch at $i: got=$stD expected=${expected.d[i]}")
        }
    }
}
