package borg.trikeshed.reactor.openapi

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.lib.ByteSeries
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

data class OpenApiInvocation(
    val operationId: String,
    val pathParameters: Map<String, String> = emptyMap(),
    val queryParameters: Map<String, String> = emptyMap(),
    val body: ByteSeries? = null,
)

/**
 * Runtime operation reactor.  A document supplies the plan; HtxKey supplies
 * the live endpoint.  No generated operation-specific binding is installed.
 */
class OpenApiCallPipeline(
    val document: ResolvedOpenApiDocument,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : CoroutineContext.Key<OpenApiCallPipeline>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun execute(invocation: OpenApiInvocation): HtxResponse {
        check(state.isAtLeast(ElementState.OPEN)) { "OpenAPI call pipeline must be open" }
        val operation = document.operationsById[invocation.operationId]
            ?: error("OpenAPI operation is not declared: ${invocation.operationId}")
        val path = operation.path.replace(Regex("\\{([^}]+)}")) { match ->
            invocation.pathParameters[match.groupValues[1]]
                ?: error("Missing path parameter ${match.groupValues[1]} for ${operation.operationId}")
        }
        val query = invocation.queryParameters.entries.joinToString("&", prefix = if (invocation.queryParameters.isEmpty()) "" else "?") {
            "${it.key}=${it.value}"
        }
        val origin = document.servers.firstOrNull() ?: error("OpenAPI document has no server URL")
        val htx = currentCoroutineContext()[HtxKey]
            ?: error("OpenAPI call pipeline requires HtxKey in coroutine context")
        return htx.request(parseHtxRequest(origin.trimEnd('/') + path + query, method = operation.method.toHtxMethod(), body = invocation.body ?: ByteSeries(byteArrayOf())))
    }
}

private fun String.toHtxMethod(): HtxMethod = when (lowercase()) {
    "get" -> HtxMethod.GET
    "head" -> HtxMethod.HEAD
    "post" -> HtxMethod.POST
    "put" -> HtxMethod.PUT
    "patch" -> HtxMethod.PATCH
    "delete" -> HtxMethod.DELETE
    "options" -> HtxMethod.OPTIONS
    else -> error("Unsupported OpenAPI HTTP method: $this")
}