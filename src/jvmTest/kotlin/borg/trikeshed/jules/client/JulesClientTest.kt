package borg.trikeshed.jules.client

import borg.trikeshed.htx.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import borg.trikeshed.lib.ByteSeries
import kotlin.coroutines.coroutineContext

class JulesClientTest {

    class FakeHtxRouteService(val handler: (HtxRequest) -> HtxResponse) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            val response = handler(request)
            val responded = state.copy(
                lifecycle = HtxExchangeLifecycle.RESPONDED,
                request = request,
                response = response
            )
            return HtxExchangeResult(responded)
        }
    }

    @Test
    fun createSession_serializesAndParsesCorrectly() = runTest {
        var recordedRequest: HtxRequest? = null
        val fakeService = FakeHtxRouteService { req ->
            recordedRequest = req
            HtxResponse(
                status = 200,
                body = ByteSeries(
                    """{"name": "sessions/s123", "id": "s123", "prompt": "test-prompt", "title": "test-title"}""".encodeToByteArray()
                )
            )
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "my-secret-api-key")

        val session = client.createSession(prompt = "test-prompt", title = "test-title")

        assertEquals("sessions/s123", session.name)
        assertEquals("s123", session.id)
        assertEquals("test-prompt", session.prompt)
        assertEquals("test-title", session.title)

        val req = requireNotNull(recordedRequest)
        assertEquals(HtxMethod.POST, req.method)
        // Check key query injection
        assertTrue(req.target.requestPath.contains("key=my-secret-api-key"))
        assertTrue(req.target.requestPath.contains("/v1alpha/sessions"))

        htxElement.close()
    }

    @Test
    fun getSession_retrievesAndParsesCorrectly() = runTest {
        var recordedRequest: HtxRequest? = null
        val fakeService = FakeHtxRouteService { req ->
            recordedRequest = req
            HtxResponse(
                status = 200,
                body = ByteSeries(
                    """{"name": "sessions/s123", "id": "s123", "prompt": "hi"}""".encodeToByteArray()
                )
            )
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "my-secret-api-key")

        val session = client.getSession("sessions/s123")

        assertEquals("sessions/s123", session.name)
        assertEquals("s123", session.id)

        val req = requireNotNull(recordedRequest)
        assertEquals(HtxMethod.GET, req.method)
        assertTrue(req.target.requestPath.contains("/v1alpha/sessions/s123"))

        htxElement.close()
    }

    @Test
    fun listSessions_parsesWrapperCorrectly() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            HtxResponse(
                status = 200,
                body = ByteSeries(
                    """{"sessions": [{"name": "sessions/1", "id": "1"}, {"name": "sessions/2", "id": "2"}]}""".encodeToByteArray()
                )
            )
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "key")

        val sessions = client.listSessions()
        assertEquals(2, sessions.size)
        assertEquals("sessions/1", sessions[0].name)
        assertEquals("sessions/2", sessions[1].name)

        htxElement.close()
    }

    @Test
    fun sendMessage_sendsPostRequest() = runTest {
        var recordedRequest: HtxRequest? = null
        val fakeService = FakeHtxRouteService { req ->
            recordedRequest = req
            HtxResponse(status = 200)
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "key")

        client.sendMessage("sessions/s123", "hello jules")

        val req = requireNotNull(recordedRequest)
        assertEquals(HtxMethod.POST, req.method)
        assertTrue(req.target.requestPath.contains("/v1alpha/sessions/s123:sendMessage"))

        htxElement.close()
    }

    @Test
    fun listActivities_parsesWrapperCorrectly() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            HtxResponse(
                status = 200,
                body = ByteSeries(
                    """{"activities": [{"name": "act1", "type": "migration"}, {"name": "act2", "type": "refactor"}]}""".encodeToByteArray()
                )
            )
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "key")

        val activities = client.listActivities("sessions/s123")
        assertEquals(2, activities.size)
        assertEquals("act1", activities[0].name)
        assertEquals("migration", activities[0].type)

        htxElement.close()
    }

    @Test
    fun listSources_parsesWrapperCorrectly() = runTest {
        val fakeService = FakeHtxRouteService { _ ->
            HtxResponse(
                status = 200,
                body = ByteSeries(
                    """{"sources": [{"name": "file1.kt", "content": "package a"}, {"name": "file2.kt"}]}""".encodeToByteArray()
                )
            )
        }

        val htxElement = openHtxElement(routeService = fakeService)
        val context = coroutineContext + htxElement
        val client = JulesClient(context, "key")

        val sources = client.listSources()
        assertEquals(2, sources.size)
        assertEquals("file1.kt", sources[0].name)
        assertEquals("package a", sources[0].content)

        htxElement.close()
    }
}
