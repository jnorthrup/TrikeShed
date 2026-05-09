package borg.trikeshed.htx.client

actual fun createWsHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "WebSocket pending reactor wiring")
}
