package borg.trikeshed.reactor

/**
 * One TLS connection driven by an event loop that owns its socket and its thread. Ciphertext the socket received
 * goes in; plaintext comes out to [TlsRecordSink.plaintext], and every byte the protocol must send (handshake
 * flights, application records, alerts, close_notify) comes out to [TlsRecordSink.ciphertext] for the loop to write.
 *
 * The bytes a sink receives are valid only during the call: they live in buffers the next call reuses. Between calls
 * a session holds its engine, at most the ciphertext of one incomplete record, and at most [pendingLimit] bytes of
 * plaintext it accepted but could not encrypt yet. One thread at a time drives it, and not re-entrantly: a sink must
 * not call into a session (this one or another driven on the same thread) during its callback; an implementation may
 * refuse such a call by throwing IllegalStateException.
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

    /**
     * Plaintext [send] accepted that the session could not encrypt yet, in bytes: the engine needs the peer's
     * handshake data first (a renegotiation, a post-handshake exchange). It leaves in order once a [receive] lets the
     * handshake go on; [close] then sends close_notify after it.
     */
    val pendingPlaintext: Int get() = 0

    /**
     * The most plaintext the session holds as [pendingPlaintext]. A [send] longer than pendingLimit - pendingPlaintext
     * is refused whole as [TlsSendOutcome.BACKPRESSURE], so no single send may exceed this.
     */
    val pendingLimit: Int get() = Int.MAX_VALUE

    /** Starts the handshake; a client's ClientHello goes to [sink]. Idempotent; [receive] begins it too. */
    fun begin(sink: TlsRecordSink)

    /**
     * Ciphertext [bytes] in [from, to) the socket received. False once the session is closed: the peer closed it, it
     * failed, or a [close] that waited behind held plaintext finished; the sink then has any alert or close_notify
     * to write, and the session takes nothing more.
     */
    fun receive(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): Boolean

    /**
     * Plaintext [bytes] in [from, to) as records into [sink], or held behind the engine (see [pendingPlaintext]).
     * Anything but [TlsSendOutcome.ACCEPTED] leaves the bytes with the caller.
     */
    fun send(bytes: ByteArray, from: Int, to: Int, sink: TlsRecordSink): TlsSendOutcome

    /** close_notify into [sink] after any held plaintext; the session accepts nothing more. */
    fun close(sink: TlsRecordSink)
}

/** What [TlsRecordSession.send] did with the plaintext it was offered. */
enum class TlsSendOutcome {
    /** All of it: encrypted into the sink or, while the engine waits for the peer, held behind earlier plaintext. */
    ACCEPTED,

    /**
     * None of it: holding it would take [TlsRecordSession.pendingPlaintext] past [TlsRecordSession.pendingLimit].
     * The caller keeps the bytes; a receive that lets the engine go on frees the room.
     */
    BACKPRESSURE,

    /** None of it: the handshake has not finished. */
    NOT_ESTABLISHED,

    /** The session is closed or closing: nothing more of it reaches the peer. */
    CLOSED,
}

/** Where a [TlsRecordSession] delivers bytes; each call's bytes must be consumed (copied or written) before it returns. */
interface TlsRecordSink {
    fun plaintext(bytes: ByteArray, from: Int, to: Int)
    fun ciphertext(bytes: ByteArray, from: Int, to: Int)
}
