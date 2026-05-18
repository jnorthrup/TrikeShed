package cbadvanced

import dreamer.exchange.ExchangeClient
import dreamer.exchange.RobinhoodBalance
import dreamer.exchange.RobinhoodHolding
import dreamer.exchange.RobinhoodOrder

/**
 * Mock Coinbase client for testing without real API credentials.
 * This simulates successful API responses for development/testing.
 */
class MockCoinbaseClient : ExchangeClient {
    private var shouldReturn401 = false

    fun set401Mode(enabled: Boolean) {
        shouldReturn401 = enabled
    }

    override suspend fun getBalance(): RobinhoodBalance? {
        if (shouldReturn401) return null
        return RobinhoodBalance(
            buyingPower = 10000.0,
            cashBalance = 10000.0,
            cryptoBuyingPower = 10000.0
        )
    }

    override suspend fun getHoldings(): List<RobinhoodHolding>? {
        if (shouldReturn401) return null
        return listOf(
            RobinhoodHolding(
                assetCode = "BTC",
                quantity = 0.5,
                costBasis = 25000.0
            ),
            RobinhoodHolding(
                assetCode = "ETH",
                quantity = 10.0,
                costBasis = 3000.0
            )
        )
    }

    override suspend fun getQuotes(assetCodes: List<CharSequence>): Map<CharSequence, Double>? {
        if (shouldReturn401) return null
        return assetCodes.associateWith { code ->
            when (code.toString().uppercase()) {
                "BTC" -> 65000.0
                "ETH" -> 3500.0
                "SOL" -> 150.0
                else -> 100.0
            }
        }
    }

    override suspend fun placeBuy(symbol: CharSequence, quantityStr: CharSequence): RobinhoodOrder? {
        if (shouldReturn401) return null
        return RobinhoodOrder(
            id = "mock-order-${System.currentTimeMillis()}",
            symbol = symbol.toString(),
            side = "BUY",
            quantity = quantityStr.toString(),
            price = "100.0",
            state = "FILLED",
            createdAt = System.currentTimeMillis().toString()
        )
    }

    override suspend fun placeSell(symbol: CharSequence, quantityStr: CharSequence): RobinhoodOrder? {
        if (shouldReturn401) return null
        return RobinhoodOrder(
            id = "mock-order-${System.currentTimeMillis()}",
            symbol = symbol.toString(),
            side = "SELL",
            quantity = quantityStr.toString(),
            price = "100.0",
            state = "FILLED",
            createdAt = System.currentTimeMillis().toString()
        )
    }
}
