package borg.trikeshed.dreamer

import borg.trikeshed.cursor.doubleValue
import borg.trikeshed.cursor.longValue
import borg.trikeshed.cursor.stringValue
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.size
import borg.trikeshed.lib.isNotEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Dedicated unit tests for [KlineBlock] lifecycle and cursor production.
 *
 * KlineBlock is the sealed-block adapter between parsed klines and the cursor
 * algebra. These tests pin the block's append/seal/state/rowCount contract
 * and verify that both [asCursor] and [asColumnarCursor] project correct
 * field values at each column position.
 *
 * Links in the chain tested here:
 *   Kline → KlineBlock.append → seal() → asCursor()/asColumnarCursor() → RowVec field access
 */
class KlineBlockTest {

    // ── Lifecycle: append, rowCount, state ────────────────────────────────

    @Test
    fun `mutable block starts empty in MUTABLE state`() {
        val block = KlineBlock.mutable()
        assertEquals(0, block.rowCount)
        assertEquals(KlineBlock.State.MUTABLE, block.state)
    }

    @Test
    fun `append adds rows and increments rowCount`() {
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0))
        assertEquals(1, block.rowCount)
        block.append(kline("BTCUSDT", 103.0, 108.0, 101.0, 107.0, 60.0))
        assertEquals(2, block.rowCount)
    }

    @Test
    fun `seal transitions to SEALED state`() {
        val block = KlineBlock.mutable()
        block.append(kline("ETHUSDT", 200.0, 205.0, 198.0, 203.0, 100.0))
        val sealed = block.seal()
        assertEquals(KlineBlock.State.SEALED, sealed.state)
        // seal() returns the same block
        assertTrue(sealed === block)
    }

    @Test
    fun `append to sealed block throws`() {
        val block = KlineBlock.mutable()
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0))
        block.seal()
        assertFailsWith<IllegalStateException> {
            block.append(kline("BTCUSDT", 103.0, 108.0, 101.0, 107.0, 60.0))
        }
    }

    @Test
    fun `seal on already sealed block throws`() {
        val block = KlineBlock.mutable()
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0))
        block.seal()
        assertFailsWith<IllegalStateException> {
            block.seal()
        }
    }

    @Test
    fun `mixed timespan append throws`() {
        val block = KlineBlock.mutable(TimeSpan.Hours1)
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0)) // Hours1
        assertFailsWith<IllegalArgumentException> {
            block.append(
                Kline("BTCUSDT", TimeSpan.Minutes5, 1704067200000L, 103.0, 108.0, 101.0, 107.0, 60.0)
            )
        }
    }

    @Test
    fun `null timespan block accepts any timespan`() {
        val block = KlineBlock.mutable() // no timespan constraint
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0))
        block.append(
            Kline("BTCUSDT", TimeSpan.Minutes5, 1704067200000L, 103.0, 108.0, 101.0, 107.0, 60.0)
        )
        assertEquals(2, block.rowCount)
    }

    // ── asCursor — field mapping verification ─────────────────────────────

    @Test
    fun `asCursor returns cursor with correct row count`() {
        val block = KlineBlock.mutable()
        (0 until 5).forEach { i ->
            block.append(kline("BTCUSDT", 100.0 + i, 105.0 + i, 99.0 + i, 103.0 + i, 50.0))
        }
        val cursor = block.seal().asCursor()
        assertEquals(5, cursor.size)
    }

    @Test
    fun `asCursor fields map correctly to Kline columns`() {
        val k = Kline(
            symbol = "BTCUSDT",
            timespan = TimeSpan.Hours1,
            openTime = 1704067200000L,
            open = 42000.0,
            high = 42500.0,
            low = 41800.0,
            close = 42300.0,
            volume = 150.0,
        )
        val block = KlineBlock.mutable()
        block.append(k)
        val cursor = block.seal().asCursor()
        val row = cursor.at(0)

        assertEquals("BTCUSDT", row.stringValue("symbol", ""))
        assertEquals(1704067200000L, row.longValue("openTime"))
        assertEquals(42000.0, row.doubleValue("open"), 0.001)
        assertEquals(42500.0, row.doubleValue("high"), 0.001)
        assertEquals(41800.0, row.doubleValue("low"), 0.001)
        assertEquals(42300.0, row.doubleValue("close"), 0.001)
        assertEquals(150.0, row.doubleValue("volume"), 0.001)
    }

    @Test
    fun `asCursor preserves row order`() {
        val block = KlineBlock.mutable()
        val prices = listOf(100.0, 200.0, 300.0, 400.0, 500.0)
        prices.forEachIndexed { i, price ->
            block.append(kline("TEST", price, price + 5, price - 1, price + 3, 10.0 + i))
        }
        val cursor = block.seal().asCursor()
        prices.forEachIndexed { i, expectedOpen ->
            assertEquals(expectedOpen, cursor.at(i).doubleValue("open"), 0.001)
        }
    }

    // ── asColumnarCursor — field mapping verification ─────────────────────

    @Test
    fun `asColumnarCursor returns cursor with correct row count`() {
        val block = KlineBlock.mutable()
        (0 until 3).forEach { i ->
            block.append(kline("ETHUSDT", 100.0 + i, 105.0 + i, 99.0 + i, 103.0 + i, 50.0))
        }
        val cursor = block.seal().asColumnarCursor()
        assertEquals(3, cursor.size)
    }

    @Test
    fun `asColumnarCursor fields map correctly to Kline columns`() {
        val k = Kline(
            symbol = "ETHUSDT",
            timespan = TimeSpan.Hours1,
            openTime = 1704067200000L,
            open = 2500.0,
            high = 2550.0,
            low = 2490.0,
            close = 2530.0,
            volume = 500.0,
        )
        val block = KlineBlock.mutable()
        block.append(k)
        val cursor = block.seal().asColumnarCursor()
        val row = cursor.at(0)

        assertEquals("ETHUSDT", row.stringValue("symbol", ""))
        assertEquals(1704067200000L, row.longValue("openTime"))
        assertEquals(2500.0, row.doubleValue("open"), 0.001)
        assertEquals(2550.0, row.doubleValue("high"), 0.001)
        assertEquals(2490.0, row.doubleValue("low"), 0.001)
        assertEquals(2530.0, row.doubleValue("close"), 0.001)
        assertEquals(500.0, row.doubleValue("volume"), 0.001)
    }

    @Test
    fun `asColumnarCursor produces same field values as asCursor`() {
        val klines = listOf(
            Kline("BTCUSDT", TimeSpan.Hours1, 1704067200000L, 42000.0, 42500.0, 41800.0, 42300.0, 150.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 1704070800000L, 42300.0, 43100.0, 42100.0, 42900.0, 180.0),
            Kline("BTCUSDT", TimeSpan.Hours1, 1704074400000L, 42900.0, 43200.0, 42500.0, 42800.0, 140.0),
        )
        val blockRow = KlineBlock.mutable(TimeSpan.Hours1)
        val blockCol = KlineBlock.mutable(TimeSpan.Hours1)
        klines.forEach { blockRow.append(it); blockCol.append(it) }
        val rowCursor = blockRow.seal().asCursor()
        val colCursor = blockCol.seal().asColumnarCursor()

        assertEquals(rowCursor.size, colCursor.size)
        for (i in 0 until rowCursor.size) {
            val rr = rowCursor.at(i)
            val cr = colCursor.at(i)
            assertEquals(rr.stringValue("symbol", ""), cr.stringValue("symbol", ""), "symbol mismatch at row $i")
            assertEquals(rr.longValue("openTime"), cr.longValue("openTime"), "openTime mismatch at row $i")
            assertEquals(rr.doubleValue("open"), cr.doubleValue("open"), 0.001, "open mismatch at row $i")
            assertEquals(rr.doubleValue("high"), cr.doubleValue("high"), 0.001, "high mismatch at row $i")
            assertEquals(rr.doubleValue("low"), cr.doubleValue("low"), 0.001, "low mismatch at row $i")
            assertEquals(rr.doubleValue("close"), cr.doubleValue("close"), 0.001, "close mismatch at row $i")
            assertEquals(rr.doubleValue("volume"), cr.doubleValue("volume"), 0.001, "volume mismatch at row $i")
        }
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    fun `empty sealed block produces zero-size cursor`() {
        val block = KlineBlock.mutable()
        block.seal()
        val cursor = block.asCursor()
        assertEquals(0, cursor.size)
    }

    @Test
    fun `single-row block cursor has exactly one row`() {
        val block = KlineBlock.mutable()
        block.append(kline("SOLUSDT", 142.0, 145.0, 140.0, 143.0, 1000.0))
        val cursor = block.seal().asCursor()
        assertEquals(1, cursor.size)
    }

    @Test
    fun `rows property returns snapshot`() {
        val block = KlineBlock.mutable()
        block.append(kline("BTCUSDT", 100.0, 105.0, 99.0, 103.0, 50.0))
        val rows = block.rows
        assertEquals(1, rows.size)
        // rows is a snapshot; further appends don't affect it
        block.append(kline("BTCUSDT", 103.0, 108.0, 101.0, 107.0, 60.0))
        assertEquals(1, rows.size, "rows snapshot should not reflect subsequent appends")
        assertEquals(2, block.rowCount, "block.rowCount should reflect both appends")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun kline(
        symbol: String,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
        openTime: Long = 1704067200000L,
        timespan: TimeSpan = TimeSpan.Hours1,
    ): Kline = Kline(symbol, timespan, openTime, open, high, low, close, volume)
}
