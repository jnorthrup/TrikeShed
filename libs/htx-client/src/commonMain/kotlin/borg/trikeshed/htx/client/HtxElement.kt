package borg.trikeshed.htx.client

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

// Compromise: this standalone lib does not depend on the root project to avoid composite-build cycles.
data class HtxClientMessage(val status: Int = 200, val body: String = "ok")
data class HtxClientRequest(
    val method: String,
    val path: String,
    val body: String = "",
)

typealias HtxRequestHandler = suspend (HtxClientRequest) -> HtxClientMessage

val HtxKey: AsyncContextKey<HtxElement> = HtxElement.Key
suspend fun defaultHtxRequestHandler(request: HtxClientRequest): HtxClientMessage =
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
    companion object Key : AsyncContextKey<HtxElement>()

    override val key: AsyncContextKey<HtxElement>
        get() = Key

    suspend fun request(
        method: String = "GET",
        path: String = "/",
        body: String = "",
    ): HtxClientMessage {
        requireState(ElementState.OPEN)
        return requestHandler(
            HtxClientRequest(
                method = method.trim().uppercase(),
                path = path,
                body = body,
            ),
        )
    }
}
