package borg.trikeshed.htx.client

actual fun createQuicHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "QUIC over WebTransport pending")
}
