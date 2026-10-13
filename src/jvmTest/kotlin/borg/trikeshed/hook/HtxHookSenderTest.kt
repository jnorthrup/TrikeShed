package borg.trikeshed.hook

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HtxHookSenderTest {
    @Test fun signedWebhookUsesTheContextHtxRoute() = runTest {
        val requests = mutableListOf<HtxRequest>()
        val body = """{"text":"雪"}"""
        val signature = JvmHookSigner.sign("fixture", body)
        withHtxClientRoute({ request -> requests.add(request); HtxResponse(204) }) {
            HtxHookSender.post("https://example.test/hooks/review?case=1", body, "delivery-1", signature)
        }
        val request = requests.single()
        assertEquals(HtxMethod.POST, request.method)
        assertEquals(HtxTransportProtocol.HTTPS, request.target.transportProtocol)
        assertEquals("example.test", request.target.host)
        assertEquals("/hooks/review?case=1", request.target.requestPath)
        assertEquals("delivery-1", request.headerValue("X-Delivery-NUID"))
        assertEquals(signature, request.headerValue("X-TrikeShed-Signature"))
        assertEquals(body.encodeToByteArray().size.toString(), request.headerValue("Content-Length"))
        assertEquals(body, request.body.toArray().decodeToString())
    }
}
