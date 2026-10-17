package borg.trikeshed.reactor

/**
 * One TLS connection driven by an event loop that owns its socket and its thread. Ciphertext the socket received
 * goes in; plaintext comes out to [TlsRecordSink.plaintext], and every byte the protocol must send (handshake
 * flights, application records, alerts, close_notify) comes out to [TlsRecordSink.ciphertext] for the loop to write.
 *
 * The bytes a sink receives are valid only during the call: they live in buffers the next call reuses. Between calls
 * a session holds its engine and at most the ciphertext of one incomplete record. One thread at a time drives it.
 */
interface TlsRecordSession {
    /** The handshake finished: application data flows both ways. */
    val established: Boolean

    /** The session ended: close_notify exchanged, or a failure. Nothing more is accepted. */
    val closed: Boolean

    /** The negotiated protocol and suite once [established]. */
    val session: TlsSession?

    /** Ciphertext of an incomplete record held between calls, in bytes. */
    val pendingCiphertext: Int

    /** Starts the handshake; a client's ClientHello goes to [sink]. Idempotent; [receive] begins it too. */
    fun begin(sink: TlsRecordSink)

    /**
     * Ciphertext [bytes] in [from, to) the socket received. False once the peer closed the session or it failed:
     * the sink then has any alert or close_notify reply to write, and the session takes nothing more.
     */
    fun receive(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean

    /** Plaintext [bytes] in [from, to) as records into [sink]; false when the session is not open. */
    fun send(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean

    /** close_notify into [sink]; the session sends nothing more. */
    fun close(sink: TlsRecordSink)
}

/** Where a [TlsRecordSession] delivers bytes; each call's bytes must be consumed (copied or written) before it returns. */
interface TlsRecordSink {
    fun plaintext(bytes: ByteArray, from: Int, to: Int)
    fun ciphertext(bytes: ByteArray, from: Int, to: Int)
}
