package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TlsSmokeTest {
    @Test
    fun `TLS to Coinbase connects and gets HTTP response`() = runBlocking {
        val element = HtxElement()
        element.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        val resp = element.request("GET", "https://api.coinbase.com/api/v3/brokerage/accounts")
        println("TLS: ${resp.status}")
        assertTrue(resp.status < 500, "TLS OK, status=${resp.status}")
    }
}
