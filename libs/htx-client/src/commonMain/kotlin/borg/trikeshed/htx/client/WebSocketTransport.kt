package borg.trikeshed.htx.client

/**
 * Platform-specific WebSocket transport handler factory.
 *
 * Returns an [HtxRequestHandler] suitable for registering with
 * `HtxElement.registerTransport(HtxTransport.WEBSOCKET, handler)`.
 *
 * Platform actuals:
 *   - JVM: java.net.http.WebSocket (Java 11+) or raw Socket + manual RFC 6455
 *   - JS:   browser WebSocket API (new WebSocket(url))
 *   - WASM: node:ws or similar
 *   - POSIX: 501 stub
 */
fun createWsHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "WebSocket transport not implemented")
}
