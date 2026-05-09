package borg.trikeshed.htx.client

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun createWsHandler(): HtxRequestHandler = JsWsHandler()

class JsWsHandler : HtxRequestHandler {

    private var ws: dynamic = null
    private var lastMessage: String = ""

    override suspend fun invoke(request: HtxClientRequest): HtxClientMessage {
        if (ws == null || js("ws.readyState") as Int != 1 /* OPEN */) {
            connect(request)
        }
        return readMessage()
    }

    private suspend fun connect(request: HtxClientRequest) {
        val url = request.path  // already wss:// or ws://

        ws = suspendCancellableCoroutine { cont ->
            val socket = js("new WebSocket(url)")
            socket.onopen = {
                cont.resume(socket)
            }
            socket.onerror = { event: dynamic ->
                cont.resumeWithException(RuntimeException("WebSocket connect failed: ${event.message}"))
            }
        }
    }

    private suspend fun readMessage(): HtxClientMessage {
        return suspendCancellableCoroutine { cont ->
            ws.onmessage = { event: dynamic ->
                lastMessage = event.data as String
                cont.resume(HtxClientMessage(status = 200, body = lastMessage))
            }
            ws.onerror = { event: dynamic ->
                cont.resumeWithException(RuntimeException("WebSocket error: ${event.message}"))
            }
            ws.onclose = { event: dynamic ->
                cont.resume(HtxClientMessage(status = 500, body = "connection closed"))
            }
        }
    }

    fun send(text: String) {
        ws.send(text)
    }

    fun close() {
        ws.close()
    }
}
