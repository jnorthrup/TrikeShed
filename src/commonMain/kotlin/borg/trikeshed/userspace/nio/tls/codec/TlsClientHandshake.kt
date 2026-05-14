package borg.trikeshed.userspace.nio.tls.codec

import kotlin.coroutines.CoroutineContext

/**
 * TLS 1.3 client handshake — CCEK interface (RFC 8446 §4).
 *
 * State machine: IDLE → CLIENT_HELLO → WAITING_SH → WAITING_EE
 *   → WAITING_CERT → WAITING_CV → WAITING_FINISHED → CONNECTED
 *
 * Platform engines (JDK SSLEngine, OpenSSL, Web Crypto) implement
 * this interface and register in the coroutine context via [Key].
 */
interface TlsClientHandshake : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TlsClientHandshake>

    enum class State { IDLE, CLIENT_HELLO_SENT, WAITING_SH, WAITING_EE, WAITING_CERT, WAITING_CV, WAITING_FINISHED, CONNECTED }

    /** Current handshake state. */
    val state: State

    /** Build TLS 1.3 ClientHello message bytes. Advances state to CLIENT_HELLO_SENT. */
    fun buildClientHello(): ByteArray

    /** Process ServerHello; extracts key_share. Advances to WAITING_EE. */
    fun processServerHello(message: ByteArray)

    /** Process EncryptedExtensions. Advances to WAITING_CERT. */
    fun processEncryptedExtensions(message: ByteArray)

    /** Process Certificate message. Advances to WAITING_CV. */
    fun processCertificate(message: ByteArray)

    /** Process CertificateVerify. Advances to WAITING_FINISHED. */
    fun processCertificateVerify(message: ByteArray)

    /**
     * Process Server Finished. Installs handshake traffic keys via
     * [borg.trikeshed.userspace.nio.tls.codec.TlsRecordCodec.installKeys]. Advances to CONNECTED.
     */
    fun processServerFinished(message: ByteArray)

    /** Build client Finished message. Must be called after server Finished. */
    fun buildClientFinished(): ByteArray
}
