package borg.trikeshed.htx.client

import borg.trikeshed.htx.client.generated.api.DefaultHtxGeneralApi
import borg.trikeshed.htx.client.generated.api.HtxGeneralApiContract
import borg.trikeshed.htx.client.generated.infrastructure.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedHtxGeneralClientTest {
    @Test
    fun generatedGetHealthOperationMatchesTheAuthoritativeOpenApiContract() = runTest {
        val api = DefaultHtxGeneralApi { request ->
            assertEquals(HttpMethod.GET, request.method)
            assertEquals("/health", request.path)
            HtxGeneralApiContract.GetHealth.responseBody
        }

        val response = api.getHealth()

        assertEquals("getHealth", HtxGeneralApiContract.GetHealth.operationId)
        assertEquals("ok", response.body)
        assertTrue(response.ok)
    }
}
