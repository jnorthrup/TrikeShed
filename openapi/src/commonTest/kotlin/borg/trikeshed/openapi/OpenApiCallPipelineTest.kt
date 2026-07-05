package borg.trikeshed.openapi

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenApiCallPipelineTest {
    @Test
    fun fansOutAndFaninTruthActions() = runTest {
        val inputs = listOf(
            OpenApiCall(
                "pets",
                """{"openapi":"3.1.0","info":{"title":"Pets","version":"1.0.0"},"paths":{"/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}}}}}""",
            ),
            OpenApiCall(
                "orders",
                """{"openapi":"3.1.0","info":{"title":"Orders","version":"1.0.0"},"paths":{"/orders":{"post":{"operationId":"createOrder","responses":{"201":{"description":"created"}}}}}}""",
            ),
        )

        val results = speculativeParseBurndown(
            calls = inputs,
            parallelism = 2,
            parser = { it },
            truthAction = { _, document -> document.operations().single().operationId ?: "missing" },
        )

        assertEquals(2, results.size)
        assertEquals(setOf("listPets", "createOrder"), results.map { it.output }.toSet())
        assertTrue(results.all { it.analysis.isComplete })
        assertTrue(results.all { it.tokens.isNotEmpty() })
    }

    @Test
    fun failsFastOnInvalidPayload() = runTest {
        val inputs = listOf(
            OpenApiCall("good", """{"openapi":"3.1.0","paths":{"/pets":{"get":{"operationId":"listPets"}}}}"""),
            OpenApiCall("bad", """{"openapi":"3.1.0","paths":[]}"""),
        )

        val failure = assertFailsWith<OpenApiCallFailure> {
            speculativeParseBurndown(
                calls = inputs,
                parallelism = 2,
                parser = { it },
                truthAction = { _, document -> document.operations().size },
            )
        }

        assertEquals("bad", failure.callId)
        assertTrue(failure.cause is IllegalArgumentException)
    }

    @Test
    fun channelizesGapAnalysisBeforeTruthActions() = runTest {
        val inputs = listOf(
            OpenApiCall(
                "draft",
                """{"openapi":"3.1.0","info":{"title":"Draft"},"paths":{"/pets":{"get":{"responses":{"200":{"description":"ok"}}}}}}""",
            ),
        )

        val results = speculativeGapBurndown(
            calls = inputs,
            parallelism = 1,
            parser = { it },
            truthAction = { parsed -> parsed.analysis.gaps.map(OpenApiGap::code).sorted() },
        )

        assertEquals(1, results.size)
        assertEquals(listOf("missing-info-version", "missing-operation-id"), results.single().output)
        assertTrue(results.single().tokens.any { it.kind == "operation" && it.value == "GET /pets" })
    }
}
