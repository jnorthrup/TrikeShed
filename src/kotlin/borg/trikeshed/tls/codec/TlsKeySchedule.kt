package borg.trikeshed.tls.codec

import kotlin.coroutines.CoroutineContext

/**
 * TLS 1.3 key schedule — CCEK interface (RFC 8446 §7.1).
 *
 * Derives all traffic secrets from the ECDHE shared secret through
 * the three-phase HKDF chain. Platform implementations may delegate
 * to OpenSSL / BoringSSL / Web Crypto for optimized key derivation.
 *
 * Resolved from the coroutine context via [TlsKeySchedule.Key].
 */
interface TlsKeySchedule : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TlsKeySchedule>

    /** AEAD key length (16 bytes for AES-128-GCM). */
    val keyLength: Int get() = 16

    /** AEAD IV length (12 bytes for TLS 1.3). */
    val ivLength: Int get() = 12

    /**
     * Run the complete TLS 1.3 key schedule.
     *
     * @param sharedSecret  ECDHE shared secret (32 bytes, from X25519)
     * @param helloHash     SHA-256 hash of ClientHello || ServerHello
     * @param handshakeHash SHA-256 hash of all handshake messages through ServerFinished
     * @return complete set of traffic secrets and keys
     */
    fun compute(
        sharedSecret: ByteArray,
        helloHash: ByteArray,
        handshakeHash: ByteArray,
    ): TrafficKeyMaterial
}

data class TrafficKeyMaterial(
    val clientHandshakeKey: ByteArray,   val clientHandshakeIv: ByteArray,
    val serverHandshakeKey: ByteArray,   val serverHandshakeIv: ByteArray,
    val clientApplicationKey: ByteArray, val clientApplicationIv: ByteArray,
    val serverApplicationKey: ByteArray, val serverApplicationIv: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrafficKeyMaterial) return false
        return clientHandshakeKey.contentEquals(other.clientHandshakeKey) &&
            clientHandshakeIv.contentEquals(other.clientHandshakeIv) &&
            serverHandshakeKey.contentEquals(other.serverHandshakeKey) &&
            serverHandshakeIv.contentEquals(other.serverHandshakeIv) &&
            clientApplicationKey.contentEquals(other.clientApplicationKey) &&
            clientApplicationIv.contentEquals(other.clientApplicationIv) &&
            serverApplicationKey.contentEquals(other.serverApplicationKey) &&
            serverApplicationIv.contentEquals(other.serverApplicationIv)
    }
    override fun hashCode(): Int {
        var result = clientHandshakeKey.contentHashCode()
        result = 31 * result + clientHandshakeIv.contentHashCode()
        result = 31 * result + serverHandshakeKey.contentHashCode()
        result = 31 * result + serverHandshakeIv.contentHashCode()
        result = 31 * result + clientApplicationKey.contentHashCode()
        result = 31 * result + clientApplicationIv.contentHashCode()
        result = 31 * result + serverApplicationKey.contentHashCode()
        result = 31 * result + serverApplicationIv.contentHashCode()
        return result
    }
}
