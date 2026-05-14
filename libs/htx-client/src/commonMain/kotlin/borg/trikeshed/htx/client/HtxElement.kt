package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

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
    val method: CharSequence = "GET",
    val path: CharSequence,
    val body: CharSequence = "",
    val headers: Map<CharSequence, CharSequence> = emptyMap(),
    val transport: HtxTransport = HtxTransport.HTTPS,
)

data class HtxClientMessage(val status: Int, val body: CharSequence, val headers: Map<CharSequence, CharSequence> = emptyMap())

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

fun selectTransport(uri: CharSequence): HtxTransport {
    for ((scheme, transport) in schemeToTransport) {
        if (uri.startsWith(scheme)) return transport
    }
    return HtxTransport.HTTPS
}

/** Handler type registered with [HtxElement]. */
typealias HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage

// ── Legacy compat (used by libs:server) ──────────────────────────

val HtxKey: AsyncContextKey<HtxElementCompat> = HtxElementCompat.Key

/**
 * Open an HtxElementCompat with a request handler.
 * The handler is registered for the HTTPS transport.
 */
suspend fun openHtxElement(handler: HtxRequestHandler? = null): HtxElementCompat =
 HtxElementCompat(handler)

class HtxElementCompat(private val handler: HtxRequestHandler? = null) : AsyncContextElement() {
 companion object Key : AsyncContextKey<HtxElementCompat>()
 override val key: AsyncContextKey<HtxElementCompat> get() = Key

 /** Delegate to the registered handler, or return stub if none provided */
 suspend fun request(method: CharSequence = "GET", path: CharSequence = "/", body: CharSequence = ""): HtxClientMessage {
 val req = HtxClientRequest(method = method, path = path, body = body)
 return handler?.invoke(req) ?: HtxClientMessage(status = 200, body = "ok")
 }
}

// ── New (simplified) API ──────────────────────────────────────────

@Suppress("unused")
class HtxElement {
    val transports = LinkedHashMap<HtxTransport, HtxRequestHandler>()

    fun registerTransport(transport: HtxTransport, handler: HtxRequestHandler) {
        transports[transport] = handler
    }

    suspend fun request(
        method: CharSequence = "GET",
        path: CharSequence,
        body: CharSequence = "",
        headers: Map<CharSequence, CharSequence> = emptyMap(),
        transport: HtxTransport? = null,
    ): HtxClientMessage {
        val req = HtxClientRequest(
            method = method,
            path = path,
            body = body,
            headers = headers,
            transport = transport ?: selectTransport(path),
        )
        val handler = transports[req.transport]
            ?: error("No handler registered for transport ${req.transport}. Call registerTransport first.")
        return handler(req)
    }
}
