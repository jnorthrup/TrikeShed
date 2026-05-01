package borg.trikeshed.dreamer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PairGraphTest {

    @Test
    fun `addPair registers edges in both directions`() {
        val graph = PairGraph()
        graph.addPair("BTC", "USDT")
        graph.addPair("ETH", "BTC")

        // BTC-USDT: path from BTC to USDT is direct
        val path = graph.findShortestPathToFiat("BTC", "USDT")
        assertNotNull(path)
        assertContentEquals(listOf("BTC", "USDT"), path)
    }

    @Test
    fun `findShortestPathToFiat returns direct path for same asset`() {
        val graph = PairGraph()
        graph.addPair("BTC", "USDT")
        val path = graph.findShortestPathToFiat("USDT", "USDT")
        assertContentEquals(listOf("USDT"), path)
    }

    @Test
    fun `findShortestPathToFiat returns null when no path exists`() {
        val graph = PairGraph()
        graph.addPair("BTC", "USDT")
        // ETH is isolated — no connection to USDT
        val path = graph.findShortestPathToFiat("ETH", "USDT")
        assertNull(path)
    }

    @Test
    fun `findShortestPathToFiat finds multi-hop path`() {
        val graph = PairGraph()
        graph.addPair("ETH", "BTC")
        graph.addPair("BTC", "USDT")
        graph.addPair("AAVE", "ETH")

        // AAVE → ETH → BTC → USDT
        val path = graph.findShortestPathToFiat("AAVE", "USDT")
        assertNotNull(path)
        assertContentEquals(listOf("AAVE", "ETH", "BTC", "USDT"), path)
    }

    @Test
    fun `addPair is symmetric — reverse direction also exists`() {
        val graph = PairGraph()
        graph.addPair("SOL", "USDT")

        // Both directions should work
        val fwd = graph.findShortestPathToFiat("SOL", "USDT")
        val rev = graph.findShortestPathToFiat("USDT", "SOL")
        assertNotNull(fwd)
        assertNotNull(rev)
        assertContentEquals(listOf("SOL", "USDT"), fwd)
        assertContentEquals(listOf("USDT", "SOL"), rev)
    }
}
