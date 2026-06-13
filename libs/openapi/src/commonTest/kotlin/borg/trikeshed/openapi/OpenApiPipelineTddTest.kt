package borg.trikeshed.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD spec for OpenAPI call pipeline.
 *
 * OpenApiCallPipeline: builds HTTP calls from OpenAPI operation definitions.
 * Key stages:
 *   1. Resolve operation by operationId
 *   2. Build request from schema (method, path, headers, body)
 *   3. Dispatch via HTX transport
 *   4. Parse response according to response schema
 */
class OpenApiPipelineTddTest {

    // ── Pipeline stages contract ───────────────────────────────────────────────

    @Test
    fun `pipeline has operationId resolution stage`() {
        // OperationId uniquely identifies an endpoint within an OpenAPI doc
        TODO("redefine test requirements")
    }

    @Test
    fun `pipeline has request construction stage`() {
        // Builds HtxClientRequest from OpenAPI operation definition
        TODO("redefine test requirements")
    }

    @Test
    fun `pipeline has response parsing stage`() {
        // Parses HtxClientMessage into typed result per response schema
        TODO("redefine test requirements")
    }

    // ── OpenApiClientGenerator contract ───────────────────────────────────────

    @Test
    fun `OpenApiClientGenerator generates Keys object`() {
        // Generated Keys object contains AsyncContextKey<HtxElement> per endpoint
        TODO("redefine test requirements")
    }

    @Test
    fun `OpenApiClientGenerator generates Elements factory`() {
        // Generated Elements object contains suspend fun htx(): HtxElement
        TODO("redefine test requirements")
    }

    @Test
    fun `OpenApiClientGenerator generates SupervisorJobs`() {
        // One SupervisorJob per operationId, supporting fan-out
        TODO("redefine test requirements")
    }

    // ── Raw parser ────────────────────────────────────────────────────────────

    @Test
    fun `rawParser handles YAML OpenAPI documents`() {
        // OpenApiRawParser reads the .yaml file and produces an OpenApiDoc
        TODO("redefine test requirements")
    }

    @Test
    fun `rawParser handles JSON OpenAPI documents`() {
        // OpenApiRawParser reads the .json variant
        TODO("redefine test requirements")
    }
}
