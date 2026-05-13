package borg.trikeshed.htx.client

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

class JvmHtxCoinmarketcapRsiLiveTest {

    @Test
    fun `htx https fetch coinmarketcap rsi page`() = runBlocking(SupervisorJob()) {
        val elem = HtxElement()
        elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())

        val response = withTimeout(30_000L) {
            elem.request(
                method = "GET",
                path = "https://coinmarketcap.com/charts/rsi/",
            )
        }

        println("cmc-rsi status=${response.status} bodyLen=${response.body.length}")
        assertTrue(response.status in 200..499, "Unexpected status ${response.status}")
        assertTrue(response.body.isNotEmpty(), "Expected non-empty body")
    }
}
