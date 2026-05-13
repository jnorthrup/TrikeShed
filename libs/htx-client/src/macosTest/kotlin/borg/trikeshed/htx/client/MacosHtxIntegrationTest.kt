package borg.trikeshed.htx.client

import borg.trikeshed.userspace.nio.channels.spi.PosixChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.PosixReactorOperations
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * macOS-only integration test: exercises the full HtxElement transport stack
 * with the platform-specific PosixChannelOperations + PosixReactorOperations.
 * This lives in macosTest (not commonTest) so it only runs on Apple targets.
 */
class MacosHtxIntegrationTest {

    private val channels = PosixChannelOperations()
    private val reactor = PosixReactorOperations(channels)
    private val handler = ringHttpsHandler(channels, reactor)

    private fun makeClient(): HtxElement {
        val elem = HtxElement()
        elem.registerTransport(HtxTransport.HTTPS, handler)
        return elem
    }

    @Test
    fun `google returns 200`() = runTest {
        val elem = makeClient()
        val resp = elem.request("GET", "https://www.google.com/")
        println("google status=${resp.status} bodyLen=${resp.body.length}")
        assertTrue(resp.status in 200..299, "google: ${resp.status}")
    }

    @Test
    fun `coinbase api returns non-5xx`() = runTest {
        val elem = makeClient()
        val resp = elem.request("GET", "https://api.coinbase.com/api/v3/brokerage/accounts")
        println("coinbase status=${resp.status} bodyLen=${resp.body.length}")
        assertTrue(resp.status < 500, "coinbase: ${resp.status}")
    }

    @Test
    fun `coinmarketcap pro api returns non-5xx`() = runTest {
        val elem = makeClient()
        // API key from environment — test is meaningful even without it (returns 401)
        val key = ""
        val resp = elem.request(
            method = "GET",
            path = "https://pro-api.coinmarketcap.com/v1/cryptocurrency/listings/latest?limit=10",
            headers = mapOf("X-CMC_PRO_API_KEY" to key),
        )
        println("cmc status=${resp.status} bodyLen=${resp.body.length}")
        assertTrue(resp.status < 500, "cmc: ${resp.status}")
    }
}
