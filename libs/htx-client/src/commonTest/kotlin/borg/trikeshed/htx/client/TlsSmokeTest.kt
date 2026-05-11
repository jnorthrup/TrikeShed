package borg.trikeshed.htx.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TlsSmokeTest {

    // 18a — TLS connects to Coinbase
    @Test
    fun `TLS connects to api coinbase com over HTTPS`() {
        runTest {
            val elem = HtxElement()
            elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
            val resp = elem.request("GET", "https://api.coinbase.com/api/v3/brokerage/accounts")
            assertTrue(resp.status < 500)
        }
    }

    // 18b — request with unregistered transport throws
    @Test
    fun `request with unregistered transport throws`() {
        runTest {
            val elem = HtxElement()
            assertFailsWith<IllegalStateException> { elem.request("GET", "https://no-handler.example/") }
        }
    }

    // TLS proves itself on major sites
    @Test
    fun `TLS connects to google com`() {
        runTest {
            val elem = HtxElement()
            elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
            val resp = elem.request("GET", "https://www.google.com/")
            assertTrue(resp.status < 500, "Google returned ${resp.status}")
        }
    }

    @Test
    fun `TLS connects to reddit com`() {
        runTest {
            val elem = HtxElement()
            elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
            val resp = elem.request("GET", "https://www.reddit.com/")
            assertTrue(resp.status < 500, "Reddit returned ${resp.status}")
        }
    }
}
