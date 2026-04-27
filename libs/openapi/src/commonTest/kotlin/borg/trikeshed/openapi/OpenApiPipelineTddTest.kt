package borg.trikeshed.openapi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenApiPipelineTddTest {

   val validOpenApi = """
        {
          "openapi": "3.0.0",
          "info": { "title": "test", "version": "0.1.0" },
          "paths": {
            "/health": {
              "get": {
                "operationId": "health",
                "responses": { "200": {} }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun speculativeGapBurndown_success_returnsAll() = runTest {
            val calls = listOf(
                OpenApiCall(callId = "a", input = validOpenApi),
                OpenApiCall(callId = "b", input = validOpenApi),
            )

            val results = speculativeGapBurndown(
                calls = calls,
                parallelism = 2,
                parser = { input -> input },
                truthAction = { parsed -> parsed.analysis }
            )

            assertEquals(2, results.size)
        assertTrue(results.all { it.analysis.isComplete })
    }

    @Test
    fun speculativeGapBurndown_failure_throwsOpenApiCallFailure() = runTest {
            val calls = listOf(
                OpenApiCall(callId = "ok", input = validOpenApi),
                OpenApiCall(callId = "bad", input = "not json")
            )

        assertFailsWith<OpenApiCallFailure> {
            speculativeGapBurndown(
                calls = calls,
                parallelism = 2,
                parser = { input -> input },
                truthAction = { parsed -> parsed.analysis }
            )
        }
    }
}
