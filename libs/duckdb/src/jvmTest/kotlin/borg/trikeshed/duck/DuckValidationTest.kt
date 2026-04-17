package borg.trikeshed.duck

import borg.trikeshed.indicator.FeatureExtractor
import borg.trikeshed.lib.*
import kotlin.test.*

class DuckValidationTest {

    @Test
    fun testFullPipeline() {
        val db = DuckSeries.memory()

        // Create and populate candles table
        db.execute("""
            CREATE TABLE candles (
                pair VARCHAR,
                ts TIMESTAMP,
                open DOUBLE,
                high DOUBLE,
                low DOUBLE,
                close DOUBLE,
                volume DOUBLE
            )
        """)

        // Insert 1000 candles
        for (i in 0 until 1000) {
            val c = 100.0 + i
            db.execute(
                "INSERT INTO candles VALUES (?, ?, ?, ?, ?, ?, ?)",
                "BTC/USD", "2024-01-01 00:00:00",
                c - 1, c + 2, c - 2, c, Math.random() * 1000
            )
        }

        // Query OHLCV from DuckDB
        val startQuery = System.nanoTime()
        val cols = db.columns(
            "SELECT open, high, low, close, volume FROM candles WHERE pair=? ORDER BY ts",
            "BTC/USD"
        )
        val queryTime = System.nanoTime() - startQuery

        // Extract columns to Double arrays then Series
        val openSeries = toDoubleSeries(cols["open"]!!)
        val highSeries = toDoubleSeries(cols["high"]!!)
        val lowSeries = toDoubleSeries(cols["low"]!!)
        val closeSeries = toDoubleSeries(cols["close"]!!)
        val volumeSeries = toDoubleSeries(cols["volume"]!!)

        assertEquals(1000, closeSeries.a)

        // Compute indicators
        val startInd = System.nanoTime()
        val indicators = FeatureExtractor.compute(closeSeries, highSeries, lowSeries, volumeSeries)
        val indTime = System.nanoTime() - startInd

        // Verify
        assertTrue(indicators.containsKey("rsi_14"))
        assertTrue(indicators.containsKey("bb_upper"))
        assertTrue(indicators.containsKey("atr_14"))

        println("=== Pipeline Performance ===")
        println("Query: ${queryTime / 1_000_000.0} ms")
        println("Indicators: ${indTime / 1_000_000.0} ms")
        println("Total: ${(queryTime + indTime) / 1_000_000.0} ms")
        println("Indicators computed: ${indicators.size}")

        db.close()
    }

    @Test
    fun testSeriesIsLazy() {
        val db = DuckSeries.memory()
        db.execute("CREATE TABLE test (x DOUBLE)")
        for (i in 0 until 100) {
            db.execute("INSERT INTO test VALUES (?)", i.toDouble())
        }

        val s = db.query("SELECT x FROM test")
        assertEquals(100, s.a) // Size materialized

        val t0 = System.nanoTime()
        val v = s.b(50)
        val t1 = System.nanoTime()

        println("Lazy access (1 element): ${(t1 - t0) / 1_000.0} µs")
        assertTrue(v != null)

        db.close()
    }

    private fun toDoubleSeries(s: Series<Any?>): Series<Double> {
        val arr = DoubleArray(s.a) { i: Int -> s.b(i) as Double }
        return arr.toSeries()
    }
}
