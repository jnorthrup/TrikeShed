package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [PaperPosition] — simulated position tracking and unrealized PnL.
 */
class PaperPositionTest {

    @Test
    fun `unrealizedPnL positive when price above entry`() {
        val pos = PaperPosition(symbol = "BTCUSDT", quantity = 0.5, entryPrice = 100_000.0)
        val pnl = pos.unrealizedPnL(currentPrice = 110_000.0)
        // (110000 - 100000) * 0.5 = 5000
        assertEquals(5_000.0, pnl, 0.01)
    }

    @Test
    fun `unrealizedPnL negative when price below entry`() {
        val pos = PaperPosition(symbol = "BTCUSDT", quantity = 2.0, entryPrice = 50_000.0)
        val pnl = pos.unrealizedPnL(currentPrice = 45_000.0)
        // (45000 - 50000) * 2 = -10000
        assertEquals(-10_000.0, pnl, 0.01)
    }

    @Test
    fun `unrealizedPnL zero when price equals entry`() {
        val pos = PaperPosition(symbol = "ETHUSDT", quantity = 10.0, entryPrice = 3_000.0)
        val pnl = pos.unrealizedPnL(currentPrice = 3_000.0)
        assertEquals(0.0, pnl, 0.001)
    }

    @Test
    fun `unrealizedPnL zero for zero quantity`() {
        val pos = PaperPosition(symbol = "SOLUSDT", quantity = 0.0, entryPrice = 150.0)
        val pnl = pos.unrealizedPnL(currentPrice = 200.0)
        assertEquals(0.0, pnl, 0.001)
    }

    @Test
    fun `unrealizedPnL scales linearly with quantity`() {
        val pos1 = PaperPosition(symbol = "BTCUSDT", quantity = 1.0, entryPrice = 100_000.0)
        val pos2 = PaperPosition(symbol = "BTCUSDT", quantity = 3.0, entryPrice = 100_000.0)
        val pnl1 = pos1.unrealizedPnL(120_000.0)
        val pnl2 = pos2.unrealizedPnL(120_000.0)
        assertEquals(20_000.0, pnl1, 0.01)
        assertEquals(60_000.0, pnl2, 0.01)
        assertEquals(3.0, pnl2 / pnl1, 0.01)
    }
}
