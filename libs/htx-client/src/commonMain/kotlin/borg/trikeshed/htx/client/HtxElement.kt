package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.client.generated.infrastructure.GeneratedRequest

data class HtxClientMessage(val status: Int = 200, val body: String = "ok")

enum class HtxTransport {
    TCP,
    QUIC,
    SCTP,
}

data class Aria2Switches(
    val continueDownload: Boolean = true,
    val saveNotFound: Boolean = false,
    val maxConnectionsPerServer: Int = 15,
    val maxConcurrentDownloads: Int = 15,
    val split: Int = 15,
    val dir: String? = null,
) {
    fun toArgs(): List<String> = buildList {
        add("-Z")
        if (continueDownload) add("-c")
        if (!saveNotFound) add("--save-not-found=false")
        add("-x$maxConnectionsPerServer")
        add("-j$maxConcurrentDownloads")
        add("-s$split")
        dir?.let { add("-d"); add(it) }
    }
}

data class HtxClientRequest(
    val method: String,
    val path: String,
    val body: String = "",
    val switches: Aria2Switches? = null,
    val uris: List<String> = emptyList(),
    val transport: HtxTransport? = null,
)

typealias HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage

val HtxKey: AsyncContextKey<HtxElement> = HtxElement.Key

fun selectTransport(uri: String): HtxTransport = when {
    uri.startsWith("h3://")   -> HtxTransport.QUIC
    uri.startsWith("quic://") -> HtxTransport.QUIC
    uri.startsWith("sctp://") -> HtxTransport.SCTP
    else                      -> HtxTransport.TCP
}

fun defaultHtxRequestHandler(request: HtxClientRequest): HtxClientMessage =
    when {
        request.method.isBlank() || request.path.isBlank() -> HtxClientMessage(status = 400, body = "invalid request")
        request.method == "GET" && request.path == "/health" -> HtxClientMessage(status = 200, body = "ok")
        request.path == "/health" -> HtxClientMessage(status = 405, body = "method not allowed")
        else -> HtxClientMessage(status = 404, body = "not found")
    }

suspend fun openHtxElement(
    requestHandler: HtxRequestHandler = ::defaultHtxRequestHandler,
): HtxElement =
    HtxElement(requestHandler).also { it.open() }

class HtxElement(
    val requestHandler: HtxRequestHandler = ::defaultHtxRequestHandler,
) : AsyncContextElement() {
    private val transportHandlers = mutableMapOf<HtxTransport, HtxRequestHandler>(
        HtxTransport.TCP to requestHandler,
    )

    fun registerTransport(transport: HtxTransport, handler: HtxRequestHandler) {
        require(transport != HtxTransport.TCP) { "TCP handler is the default requestHandler; set via constructor" }
        transportHandlers[transport] = handler
    }

    fun generatedCall(): suspend (GeneratedRequest) -> String = { request ->
        val query = request.queryParams.entries.joinToString("&") { (key, value) -> "$key=$value" }
        val path = if (query.isEmpty()) request.path else "${request.path}?$query"
        val response = request(
            method = request.method.name,
            path = path,
            body = request.body ?: "",
        )
        check(response.status == 200) {
            "Expected 200 from HTX for ${request.method.name} ${request.path}, but got ${response.status} with body ${response.body}"
        }
        response.body
    }

    companion object Key : AsyncContextKey<HtxElement>()

    override val key: AsyncContextKey<HtxElement>
        get() = Key

    suspend fun request(
        method: String = "GET",
        path: String = "/",
        body: String = "",
        switches: Aria2Switches? = null,
        uris: List<String> = emptyList(),
        transport: HtxTransport? = null,
    ): HtxClientMessage {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE || state == ElementState.DRAINING) {
            "Expected OPEN, ACTIVE, or DRAINING but was $state"
        }
        val resolvedTransport = transport ?: selectTransport(path)
        val request = HtxClientRequest(
            method = method.trim().uppercase(),
            path = path,
            body = body,
            switches = switches,
            uris = uris,
            transport = resolvedTransport,
        )
        val handler = transportHandlers[resolvedTransport]
            ?: return HtxClientMessage(status = 501, body = "transport $resolvedTransport not registered")
        return handler(request)
    }
}
