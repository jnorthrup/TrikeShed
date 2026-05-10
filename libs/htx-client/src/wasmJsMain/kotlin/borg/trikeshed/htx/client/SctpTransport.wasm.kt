package borg.trikeshed.htx.client

actual fun createSctpHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "SCTP transport not available on WASM")
}
