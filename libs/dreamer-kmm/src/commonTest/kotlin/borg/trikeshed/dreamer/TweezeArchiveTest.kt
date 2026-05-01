package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TweezeArchiveTest {

    @Test
    fun `parseSymbol splits USDT quote correctly`() {
        val result = TweezeArchive.parseSymbol("BTCUSDT")
        assertEquals(Pair("BTC", "USDT"), result)
    }

    @Test
    fun `parseSymbol splits BUSD quote correctly`() {
        val result = TweezeArchive.parseSymbol("ETHBUSD")
        assertEquals(Pair("ETH", "BUSD"), result)
    }

    @Test
    fun `parseSymbol splits BTC quote correctly`() {
        val result = TweezeArchive.parseSymbol("ETHBTC")
        assertEquals(Pair("ETH", "BTC"), result)
    }

    @Test
    fun `parseSymbol splits multi-char quote correctly`() {
        val result = TweezeArchive.parseSymbol("SOLUSDT")
        assertEquals(Pair("SOL", "USDT"), result)
    }

    @Test
    fun `parseSymbol returns null for unknown quote`() {
        val result = TweezeArchive.parseSymbol("FOOBAR")
        assertNull(result)
    }

    @Test
    fun `parseSymbol returns null when symbol equals quote`() {
        val result = TweezeArchive.parseSymbol("USDT")
        assertNull(result)
    }

    @Test
    fun `parseSymbol handles all known quote assets`() {
        val pairs = listOf(
            "BTCUSDT" to Pair("BTC", "USDT"),
            "ETHBUSD" to Pair("ETH", "BUSD"),
            "SOLUSDC" to Pair("SOL", "USDC"),
            "BNBTUSD" to Pair("BNB", "TUSD"),
            "XRPFDUSD" to Pair("XRP", "FDUSD"),
            "ETHBTC" to Pair("ETH", "BTC"),
            "BNBETH" to Pair("BNB", "ETH"),
            "XRPBNB" to Pair("XRP", "BNB"),
            "ADATRY" to Pair("ADA", "TRY"),
            "BTCEUR" to Pair("BTC", "EUR"),
            "ETHRUB" to Pair("ETH", "RUB"),
        )
        for ((symbol, expected) in pairs) {
            assertEquals(expected, TweezeArchive.parseSymbol(symbol), "Failed for $symbol")
        }
    }

    @Test
    fun `formatCurated produces TRADE-COUNTER naming`() {
        assertEquals("TRADE-BTC/COUNTER-USDT", TweezeArchive.formatCurated("BTC", "USDT"))
        assertEquals("TRADE-ETH/COUNTER-USDT", TweezeArchive.formatCurated("ETH", "USDT"))
    }
}
