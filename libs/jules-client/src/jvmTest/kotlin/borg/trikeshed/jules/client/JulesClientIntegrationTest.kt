package borg.trikeshed.jules.client

import borg.trikeshed.htx.client.HtxClientMessage
import borg.trikeshed.htx.client.openHtxElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JulesClientIntegrationTest {

    @Test
    fun generatedClientMatchesOpenApiContract() = runTest {
        var requestedMethod: CharSequence? = null
        var requestedPath: CharSequence? = null

        val htxElement = openHtxElement { request ->
            requestedMethod = request.method
            requestedPath = request.path
            if (request.method == "POST" && request.path.startsWith("https://jules.googleapis.com/v1alpha/sessions")) {
                HtxClientMessage(status = 200, body = """{"name": "sessions/123", "id": "123"}""")
            } else {
                HtxClientMessage(status = 404, body = "Not Found")
            }
        }

        val context = coroutineContext + htxElement
        val client = JulesClient(context, "my-test-key")
        val response = client.api.createSession()

        assertEquals("POST", requestedMethod)
        assertEquals("https://jules.googleapis.com/v1alpha/sessions?key=my-test-key", requestedPath)
        assertTrue(response.contains("\"id\": \"123\""))
    }
}
