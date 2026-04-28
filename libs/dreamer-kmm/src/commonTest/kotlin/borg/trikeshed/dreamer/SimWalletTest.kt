package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals

class SimWalletTest {

    @Test
    fun `initial balance is recorded correctly`() {
        val wallet = SimWallet()
        wallet.record("USDT", 1000.0)
        assertEquals(1000.0, wallet.freeBalance("USDT"))
    }

    @Test
    fun `placeOrder adds to pendingOrders`() {
        val wallet = SimWallet()
        wallet.record("USDT", 1000.0)
        val order = wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)
        assertEquals(1, wallet.pendingOrders().size)
        assertEquals(order, wallet.pendingOrders()[0])
    }

    @Test
    fun `processBar fills pending limit buy order when price crosses low`() {
        val wallet = SimWallet()
        wallet.record("USDT", 1000.0)
        wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)

        // Price stays above 50000
        val fills1 = wallet.processBar("BTCUSDT", 52000.0, 51000.0, 51500.0)
        assertEquals(0, fills1.size)
        assertEquals(1, wallet.pendingOrders().size)

        // Price crosses 50000
        val fills2 = wallet.processBar("BTCUSDT", 51000.0, 49000.0, 49500.0)
        assertEquals(1, fills2.size)
        assertEquals(0, wallet.pendingOrders().size)
        assertEquals(50000.0, fills2[0].fillPrice)
        assertEquals(0.01, fills2[0].fillQuantity)
    }

    @Test
    fun `processBar filters by symbol correctly`() {
        val wallet = SimWallet()
        wallet.record("USDT", 2000.0)
        wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)
        wallet.placeOrder("ETH", "USDT", OrderSide.BUY, OrderType.LIMIT, 3000.0, 0.1)

        // Price crosses for ETH but bar is for BTC
        val fills = wallet.processBar("BTCUSDT", 51000.0, 50500.0, 50800.0)
        // BTC didn't cross 50000, ETH did (if we were using ETH price), but bar is for BTC.
        // Even if we passed price that crosses for ETH, BTC processBar shouldn't touch ETH orders.
        assertEquals(0, fills.size)
        assertEquals(2, wallet.pendingOrders().size)

        // Cross for ETH specifically
        val ethFills = wallet.processBar("ETHUSDT", 3100.0, 2900.0, 2950.0)
        assertEquals(1, ethFills.size)
        assertEquals("ETH", ethFills[0].order.base)
        assertEquals(1, wallet.pendingOrders().size)
    }

    @Test
    fun `placeOrder locks funds`() {
        val wallet = SimWallet()
        wallet.record("USDT", 1000.0)

        // Buy 0.01 BTC at 50000. Cost = 500
        wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)

        assertEquals(500.0, wallet.freeBalance("USDT"))
        assertEquals(500.0, wallet.lockedBalance("USDT"))
    }

    @Test
    fun `placeOrder fails if insufficient funds`() {
        val wallet = SimWallet()
        wallet.record("USDT", 100.0)

        // Buy 0.01 BTC at 50000. Cost = 500. Only 100 available.
        val order = wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)

        assertEquals(null, order)
        assertEquals(100.0, wallet.freeBalance("USDT"))
        assertEquals(0.0, wallet.lockedBalance("USDT"))
        assertEquals(0, wallet.pendingOrders().size)
    }

    @Test
    fun `netQuantity and realizedPnl updates correctly after fill`() {
        val wallet = SimWallet()
        wallet.record("USDT", 1000.0)

        // Buy 0.01 BTC at 50000. Cost = 500
        wallet.placeOrder("BTC", "USDT", OrderSide.BUY, OrderType.LIMIT, 50000.0, 0.01)
        wallet.processBar("BTCUSDT", 51000.0, 49000.0, 49500.0)

        assertEquals(0.01, wallet.netQuantity("BTC"))
        assertEquals(0.0, wallet.realizedPnl("BTC")) // No realized PnL on buy

        // Sell 0.01 BTC at 60000. Revenue = 600. PnL = 100.
        wallet.placeOrder("BTC", "USDT", OrderSide.SELL, OrderType.LIMIT, 60000.0, 0.01)
        wallet.processBar("BTCUSDT", 61000.0, 59000.0, 60500.0)

        assertEquals(0.0, wallet.netQuantity("BTC"))
        assertEquals(100.0, wallet.realizedPnl("BTC"))
    }
}
