package borg.trikeshed.duck

import borg.trikeshed.lib.*
import kotlin.test.*

class DuckSeriesTest {

    @Test fun testInMemory() {
        val db = DuckSeries.memory()

        // Create table
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

        // Insert test data
        db.execute(
            "INSERT INTO candles VALUES (?, ?, ?, ?, ?, ?, ?)",
            "BTC/USD", "2024-01-01 00:00:00",
            100.0, 105.0, 99.0, 103.0, 1000.0
        )
        db.execute(
            "INSERT INTO candles VALUES (?, ?, ?, ?, ?, ?, ?)",
            "BTC/USD", "2024-01-01 01:00:00",
            103.0, 108.0, 102.0, 106.0, 1500.0
        )

        // Query single column
        val close = db.query("SELECT close FROM candles WHERE pair=? ORDER BY ts", "BTC/USD")
        assertEquals(2, close.a)
        assertTrue(close.b(0) != null)
        assertTrue(close.b(1) != null)

        // Query multiple columns - just verify columns exist
        val cols = db.columns("SELECT open, high, low, close FROM candles WHERE pair=? ORDER BY ts", "BTC/USD")
        assertTrue(cols.containsKey("open"))
        assertTrue(cols.containsKey("high"))
        assertEquals(2, cols["open"]!!.a)

        // Query all BTC
        val btc = db.columns("SELECT * FROM candles WHERE pair='BTC/USD' ORDER BY ts")
        assertEquals(7, btc.size) // 7 columns

        db.close()
    }

    @Test fun testQueryTypes() {
        val db = DuckSeries.memory()

        db.execute("CREATE TABLE test_types (d DOUBLE, i INT, s VARCHAR, b BOOLEAN)")
        db.execute("INSERT INTO test_types VALUES (3.14, 42, 'hello', true)")

        val d = db.query("SELECT d FROM test_types")
        assertEquals(3.14, d.b(0))

        val i = db.query("SELECT i FROM test_types")
        assertEquals(42, i.b(0))

        val s = db.query("SELECT s FROM test_types")
        assertEquals("hello", s.b(0))

        db.close()
    }
}
