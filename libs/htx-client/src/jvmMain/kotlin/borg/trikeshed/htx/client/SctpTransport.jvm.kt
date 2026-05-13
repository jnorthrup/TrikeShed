package borg.trikeshed.htx.client

actual fun createSctpHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "SCTP not yet implemented on JVM")
}
