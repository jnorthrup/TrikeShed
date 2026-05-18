package borg.trikeshed.htx.client

actual fun createSctpHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "SCTP transport pending ring reactor association support")
}
