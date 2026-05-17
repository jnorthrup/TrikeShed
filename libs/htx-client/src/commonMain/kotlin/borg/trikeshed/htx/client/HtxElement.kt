package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

/**
 * Options for HTTP/HTTPS/WebSocket requests through the HTX transport layer.
 *
 * @property method  HTTP method (GET, POST, PUT, DELETE)
 * @property path    Full URL (https://api.example.com/endpoint) or ws:// / wss://
 * @property body    Request body string (null for GET)
 * @property headers Additional headers (e.g. "X-CMC_PRO_API_KEY")
 * @property transport  Transport selection
 */
data class HtxClientRequest(
    val method: String = "GET",
    val path: String,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val transport: HtxTransport = HtxTransport.HTTPS,
)

data class HtxClientMessage(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * Transport backends available for [HtxElement].
 */
enum class HtxTransport {
    TCP, QUIC, SCTP, HTTPS, WEBSOCKET
}

/**
 * Maps URI schemes to transport enums.
 */
private val schemeToTransport = mapOf(
    "http://" to HtxTransport.HTTPS,
    "https://" to HtxTransport.HTTPS,
    "ws://" to HtxTransport.WEBSOCKET,
    "wss://" to HtxTransport.WEBSOCKET,
    "tcp://" to HtxTransport.TCP,
    "quic://" to HtxTransport.QUIC,
    "sctp://" to HtxTransport.SCTP,
)

fun selectTransport(uri: String): HtxTransport {
    for ((scheme, transport) in schemeToTransport) {
        if (uri.startsWith(scheme)) return transport
    }
    return HtxTransport.HTTPS
}

/** Handler type registered with [HtxElement]. */
typealias HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage

// ── Legacy compat (used by libs:server) ──────────────────────────

val HtxKey: AsyncContextKey<HtxElementCompat> = HtxElementCompat.Key

suspend fun openHtxElement(): HtxElementCompat =
    HtxElementCompat().also { it.open() }

class HtxElementCompat : AsyncContextElement() {
    companion object Key : AsyncContextKey<HtxElementCompat>()
    override val key: AsyncContextKey<HtxElementCompat> get() = Key

    suspend fun request(method: String = "GET", path: String = "/", body: String = ""): HtxClientMessage {
        // Stub for server compat — real transport goes through new API
        return HtxClientMessage(status = 200, body = "ok")
    }
}

// ── New (simplified) API ──────────────────────────────────────────

@Suppress("unused")
class HtxElement {
    val transports = mutableMapOf<HtxTransport, HtxRequestHandler>()

    fun registerTransport(transport: HtxTransport, handler: HtxRequestHandler) {
        transports[transport] = handler
    }

    suspend fun request(
        method: String = "GET",
        path: String,
        body: CharSequence = "",
        headers: Map<String, String> = emptyMap(),
        transport: HtxTransport? = null,
    ): HtxClientMessage {
        val req = HtxClientRequest(
            method = method,
            path = path,
            body = body.toString(),
            headers = headers,
            transport = transport ?: selectTransport(path),
        )
        val handler = transports[req.transport]
            ?: error("No handler registered for transport ${req.transport}. Call registerTransport first.")
        return handler(req)
    }
}
