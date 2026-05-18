package borg.trikeshed.htx.client

/**
 * QUIC transport handler (RFC 9000).
 *
 * Resolves via CCEK: coroutineContext[QuicTransport.Key]?.handler.
 * Platform actuals wire the QUIC UDP socket + TLS 1.3 + stream multiplexer
 * through the ring reactor.
 */
fun createQuicHandler(): HtxRequestHandler = { _ ->
    HtxClientMessage(status = 501, body = "QUIC transport not implemented")
}
