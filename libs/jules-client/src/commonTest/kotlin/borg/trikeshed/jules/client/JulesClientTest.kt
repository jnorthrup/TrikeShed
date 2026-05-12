package borg.trikeshed.jules.client

import borg.trikeshed.htx.client.HtxClientMessage
import borg.trikeshed.htx.client.HtxClientRequest
import borg.trikeshed.htx.client.openHtxElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JulesClientTest {

    @Test
    fun testCreateSession() = runTest {
        var capturedRequest: HtxClientRequest? = null

        val htxElement = openHtxElement { request ->
            capturedRequest = request
            HtxClientMessage(status = 200, body = """{"name": "sessions/test-session-123", "state": "ACTIVE"}""")
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "test-api-key")
        val result = client.api.createSession()

        assertNotNull(result)
        assertTrue(result.contains("test-session-123"))
        assertNotNull(capturedRequest)
        assertTrue(capturedRequest!!.path.contains("jules.googleapis.com"))
        assertTrue(capturedRequest!!.path.contains("key=test-api-key"))
    }

    @Test
    fun testListSessions() = runTest {
        var capturedRequest: HtxClientRequest? = null

        val htxElement = openHtxElement { request ->
            capturedRequest = request
            HtxClientMessage(status = 200, body = """{"sessions": [{"name": "sessions/s1"}, {"name": "sessions/s2"}]}""")
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "test-api-key")
        val result = client.api.listSessions()

        assertNotNull(result)
        assertTrue(result.contains("sessions/s1"))
        assertTrue(capturedRequest!!.path.contains("/v1alpha/sessions"))
    }

    @Test
    fun testGetSession() = runTest {
        var capturedRequest: HtxClientRequest? = null

        val htxElement = openHtxElement { request ->
            capturedRequest = request
            HtxClientMessage(status = 200, body = """{"name": "sessions/my-session", "state": "ACTIVE"}""")
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "test-api-key")
        val result = client.api.getSession("sessions/my-session")

        assertNotNull(result)
        assertTrue(result.contains("my-session"))
        assertTrue(capturedRequest!!.path.contains("sessions/my-session"))
    }

    @Test
    fun testSendMessage() = runTest {
        var capturedRequest: HtxClientRequest? = null

        val htxElement = openHtxElement { request ->
            capturedRequest = request
            HtxClientMessage(status = 200, body = """{"message": {"role": "model", "text": "Hello!"}}""")
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "test-api-key")
        val result = client.api.sendMessage("sessions/test-123")

        assertNotNull(result)
        assertTrue(capturedRequest!!.path.contains("sessions/test-123"))
        assertTrue(capturedRequest!!.path.contains("sendMessage"))
    }

    @Test
    fun testApiKeyInjectedIntoPath() = runTest {
        val htxElement = openHtxElement { request ->
            assertTrue(request.path.contains("key=test-key-123"))
            HtxClientMessage(status = 200, body = """{"name": "sessions/test"}""")
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "test-key-123")
        client.api.createSession()
    }
}
