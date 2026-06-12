package borg.trikeshed.htx.client

import kotlinx.serialization.Serializable

/**
 * HTTP response from HTX client/server.
 */
@Serializable
data class HtxClientMessage(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val error: String? = null
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * HTTP request for HTX client/server.
 */
@Serializable
data class HtxClientRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = ""
) {
    companion object {
        fun get(path: String, headers: Map<String, List<String>> = emptyMap()) = 
            HtxClientRequest("GET", path, headers)
        fun post(path: String, body: String = "", headers: Map<String, List<String>> = emptyMap()) = 
            HtxClientRequest("POST", path, headers, body)
    }
}

/**
 * HTX Element for the HTX client - Stub implementation.
 * Provides the CoroutineContext.Element interface for structured concurrency
 * integration with the TrikeShed context system.
 * 
 * TODO: Replace with generated OpenAPI client implementation.
 */
class HtxElement(
    val baseUrl: String = "http://127.0.0.1"
) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> get() = HtxKey

    @Volatile
    var state: ElementState = ElementState.CLOSED
        private set

    suspend fun open() {
        state = ElementState.OPEN
    }

    suspend fun close() {
        state = ElementState.CLOSED
    }

    /**
     * Execute an HTTP request.
     * This is the main entry point used by the server adapter.
     */
    suspend fun request(
        method: String,
        path: String,
        headers: Map<String, List<String>> = emptyMap(),
        body: String = ""
    ): HtxClientMessage {
        // Stub implementation - returns mock success
        // Real implementation would use generated OpenAPI client
        return HtxClientMessage(
            status = 200,
            body = "OK"
        )
    }
}

suspend fun openHtxElement(
    baseUrl: String = "http://127.0.0.1"
): HtxElement {
    val element = HtxElement(baseUrl)
    element.open()
    return element
}

enum class ElementState {
    CLOSED,
    OPEN
}

object HtxKey : CoroutineContext.Key<HtxElement>