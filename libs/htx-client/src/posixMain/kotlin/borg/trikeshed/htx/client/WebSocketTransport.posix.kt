package borg.trikeshed.htx.client

actual fun createWsHandler(): HtxRequestHandler = { request ->
    HtxClientMessage(status = 501, body = "WebSocket transport not implemented on POSIX native")
}
