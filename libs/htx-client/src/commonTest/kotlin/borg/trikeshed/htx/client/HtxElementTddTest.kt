package borg.trikeshed.htx.client

import kotlinx.coroutines.test.runTest as runBlocking
import kotlin.test.*
import kotlin.test.assertEquals

class HtxElementTddTest {

    // 3a — handler dispatch
    @Test
    fun `registerTransport and request dispatches to handler`() = runBlocking {
        val element = HtxElement()
        element.registerTransport(HtxTransport.HTTPS) { req: HtxClientRequest ->
            HtxClientMessage(
                status = 201,
                body = "custom",
            )
        }
        val resp = element.request("GET", "https://test.example/")
        assertEquals(201, resp.status)
        assertEquals("custom", resp.body)
    }

    // 3b — scheme routing
    @Test
    fun `request with https scheme selects HTTPS transport`() = runBlocking {
        assertEquals(HtxTransport.HTTPS, selectTransport("https://api.example.com/v1"))
    }
}
