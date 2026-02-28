package borg.trikeshed.duck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.BeforeTest

class DuckFFITest {

    @BeforeTest
    fun setup() {
        DuckMuxer.clear()
    }

    @Test
    fun testIngestionAndMuxer() {
        val sym = "BTC/USDT"
        DuckMuxer.ingestOHLCV(sym, 1709116800L, 50000.0, 51000.0, 49000.0, 50500.0, 100.0)
        
        val candles = DuckMuxer.getOHLCV(sym)
        assertEquals(1, candles.size)
        assertEquals(50500.0, candles[0].close)
        
        DuckMuxer.ingestWallet(sym, 1000.0, 500.0, 100.0)
        val wallet = DuckMuxer.getWallet(sym)
        assertNotNull(wallet)
        assertEquals(1000.0, wallet.balance)
        
        DuckMuxer.ingestTrade(sym, 50000.0, 0.01, false, 5.0)
        val trade = DuckMuxer.getTrade(sym)
        assertNotNull(trade)
        assertEquals(50000.0, trade.entryPrice)
    }
}
