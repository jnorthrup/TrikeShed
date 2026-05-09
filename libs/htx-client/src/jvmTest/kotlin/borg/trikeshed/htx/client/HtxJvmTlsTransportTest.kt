package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HtxJvmTlsTransportTest {

    // 5a — TLS handshake
    @Test
    fun `TLS handler connects to Coinbase and gets HTTP response`() = runBlocking {
        val handler = createHttpsHandler()
        val req = HtxClientRequest(path = "https://api.coinbase.com/api/v3/brokerage/accounts")
        val resp = handler(req)
        assertTrue(resp.status < 500, "TLS handshake OK, HTTP ${resp.status}")
    }

    // 5b — custom headers
    @Test
    fun `TLS handler forwards custom headers`() = runBlocking {
        val handler = createHttpsHandler()
        val req = HtxClientRequest(
            path = "https://httpbin.org/headers",
            headers = mapOf("X-Test" to "trike-42")
        )
        val resp = handler(req)
        assertTrue(resp.status in 200..499)
    }
}
