package borg.trikeshed.htx.client

actual fun createQuicHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "QUIC transport not available on WASM")
}
