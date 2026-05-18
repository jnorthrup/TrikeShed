package borg.trikeshed.htx.client

actual fun createHttpsHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "HTTPS transport not implemented on native")
}
