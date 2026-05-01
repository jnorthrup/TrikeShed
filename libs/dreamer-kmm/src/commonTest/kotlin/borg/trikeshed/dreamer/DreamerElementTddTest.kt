package borg.trikeshed.dreamer

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TDD spec for dreamer-kmm papertrading elements.
 *
 * dreamer-kmm is a KMP module for Binance papertrading and backtesting.
 * It owns the lifecycle of:
 *   - PaperAccount: simulated account state with balances and positions
 *   - PaperOrder: simulated order lifecycle (submitted → filled → cancelled)
 *   - PaperPosition: open position tracking
 *
 * All elements are AsyncContextElement subclasses keyed by singleton companions.
 * SupervisorJob hosts the papertrading loop fanout.
 */
class DreamerElementTddTest {

    // ── PaperAccount is AsyncContextElement ─────────────────────────────────────

    @Test
    fun `PaperAccount key returns singleton`() {
        val account = PaperAccount(balance = 10_000.0)
        assertSame(PaperAccount.Key, account.key)
    }

    @Test
    fun `PaperAccount starts CREATED`() {
        val account = PaperAccount(balance = 10_000.0)
        assertEquals(ElementState.CREATED, account.state)
    }

    @Test
    fun `open transitions CREATED to OPEN`() = runTest {
        val account = PaperAccount(balance = 10_000.0)
        account.open()
        assertEquals(ElementState.OPEN, account.state)
    }

    @Test
    fun `PaperAccount balance is preserved`() {
        val account = PaperAccount(balance = 50_000.0)
        assertEquals(50_000.0, account.balance)
    }

    // ── PaperOrder ─────────────────────────────────────────────────────────────

    @Test
    fun `PaperOrder key returns singleton`() {
        val order = PaperOrder(symbol = "BTCUSDT", quantity = 0.5, price = 50_000.0)
        assertSame(PaperOrder.Key, order.key)
    }

    @Test
    fun `PaperOrder starts with pending fill status`() {
        val order = PaperOrder(symbol = "BTCUSDT", quantity = 0.5, price = 50_000.0)
        assertEquals(OrderStatus.PENDING, order.status)
    }

    // ── PaperPosition ──────────────────────────────────────────────────────────

    @Test
    fun `PaperPosition key returns singleton`() {
        val position = PaperPosition(symbol = "ETHUSDT", quantity = 2.0, entryPrice = 3000.0)
        assertSame(PaperPosition.Key, position.key)
    }

    @Test
    fun `PaperPosition unrealizedPnL is zero when entry matches current`() {
        val position = PaperPosition(symbol = "ETHUSDT", quantity = 2.0, entryPrice = 3000.0)
        assertEquals(0.0, position.unrealizedPnL(currentPrice = 3000.0))
    }

    @Test
    fun `PaperPosition unrealizedPnL is positive when price above entry`() {
        val position = PaperPosition(symbol = "ETHUSDT", quantity = 2.0, entryPrice = 3000.0)
        assertTrue(position.unrealizedPnL(currentPrice = 3100.0) > 0)
    }
}
