package borg.trikeshed.server

import borg.trikeshed.htx.client.generated.api.HtxGeneralApiContract
import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest
import borg.trikeshed.htx.client.generated.infrastructure.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtxGeneralClientServerCompatibilityTest {
    @Test
    fun generatedClientRoundTripMatchesOpenApiContract() = runTest {
        val context = buildServerContext()
        try {
            val adapter = HtxGeneralServerAdapter(context)
            val request = HtxGeneralApiContract.GetHealth.request

            assertEquals(HttpMethod.GET, request.method)
            assertEquals("/health", request.path)

            val transportResponse = adapter.execute(request)
            assertEquals(200, transportResponse.status)
            assertEquals("ok", transportResponse.body)

            val response = adapter.client().getHealth()
            assertEquals("getHealth", HtxGeneralApiContract.GetHealth.operationId)
            assertEquals("ok", response.body)
            assertTrue(response.ok)
        } finally {
            closeServerContext(context)
        }
    }

    @Test
    fun adapterRejectsPathDriftWithConcreteFailureSignals() = runTest {
        val context = buildServerContext()
        try {
            val adapter = HtxGeneralServerAdapter(context)
            val driftedRequest = GeneratedRequest(
                method = HttpMethod.GET,
                path = "/healthz",
            )

            val response = adapter.execute(driftedRequest)
            assertEquals(404, response.status)
            assertEquals("not found", response.body)
        } finally {
            closeServerContext(context)
        }
    }
}
