package borg.trikeshed.jules.client

import borg.trikeshed.htx.client.HtxClientMessage
import borg.trikeshed.htx.client.HtxClientRequest
import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.HtxKey
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JulesClientTest {

    @Test
    fun testCreateSession() = runTest {
        var capturedRequest: HtxClientRequest? = null
        val mockHtxElement = HtxElement { req ->
            capturedRequest = req
            HtxClientMessage(status = 200, body = """{"id": "session-123", "name": "sessions/session-123"}""")
        }
        mockHtxElement.open()

        val context = coroutineContext + mockHtxElement
        val client = JulesClient(context, "dummy-api-key")
        val response = client.api.createSession()

        assertTrue(response.contains("session-123"))
        assertEquals("POST", capturedRequest?.method)
        assertEquals("https://jules.googleapis.com/v1alpha/sessions?key=dummy-api-key", capturedRequest?.path)
    }
}
