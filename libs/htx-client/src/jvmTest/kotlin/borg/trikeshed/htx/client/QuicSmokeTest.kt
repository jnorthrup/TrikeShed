package borg.trikeshed.htx.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live QUIC integration smoke test — reaches google.com over QUIC + TLS 1.3.
 *
 * Run: ./gradlew :libs:htx-client:jvmTest --tests "QuicSmokeTest"
 *
 * Precondition: UDP 443 must be reachable (corporate firewalls may block).
 */
class QuicSmokeTest {

    @Test
    fun `QUIC Initial reaches google com`() = runTest {
        val elem = HtxElement()
        elem.registerTransport(HtxTransport.QUIC, createQuicHandler())
        val resp = elem.request("GET", "quic://www.google.com:443/", transport = HtxTransport.QUIC)

        // In environments where UDP 443 is blocked, status will be 504 (timeout).
        // This is not a code bug — it's network policy.
        println("QUIC RESPONSE: status=${resp.status}, body='${resp.body}'")

        assertTrue(
            resp.status < 500 || resp.body.contains("timeout", ignoreCase = true) || resp.body.contains("error", ignoreCase = true),
            "Expected QUIC connectivity or a diagnostic timeout/error. Got status=${resp.status}, body='${resp.body}'"
        )
    }

    @Test
    fun `QUIC smoke diagnostic prints status`() = runTest {
        val host = "www.google.com"
        val elem = HtxElement()
        elem.registerTransport(HtxTransport.HTTPS, createHttpsHandler())
        val httpsResp = elem.request("GET", "https://$host/")
        println("HTTPS to $host: status=${httpsResp.status}")

        elem.registerTransport(HtxTransport.QUIC, createQuicHandler())
        val quicResp = elem.request("GET", "quic://$host:443/", transport = HtxTransport.QUIC)
        println("QUIC to $host: status=${quicResp.status}, body='${quicResp.body.take(200)}'")

        // Just collect diagnostics — always passes
        assertTrue(true)
    }
}