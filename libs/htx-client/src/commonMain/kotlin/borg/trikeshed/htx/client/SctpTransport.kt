package borg.trikeshed.htx.client

/**
 * SCTP transport handler (RFC 4960).
 *
 * Resolves via CCEK: coroutineContext[SctpTransport.Key]?.handler.
 * Platform actuals wire the SCTP association + 4-way handshake + multi-stream
 * through the ring reactor.
 */
fun createSctpHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "SCTP transport not implemented")
}
