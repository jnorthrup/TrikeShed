package borg.trikeshed.userspace.nio.tls.codec

import borg.trikeshed.userspace.nio.tls.record.ContentType
import kotlin.coroutines.CoroutineContext

/**
 * TLS 1.3 record layer — CCEK interface (RFC 8446 §5).
 *
 * Each platform can provide an optimal implementation:
 *   JVM    → javax.crypto-backed AES-GCM record codec
 *   Native → OpenSSL record layer
 *   JS     → Web Crypto-backed record codec
 *
 * Resolved from the coroutine context via [TlsRecordCodec.Key].
 */
interface TlsRecordCodec : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TlsRecordCodec>

    /**
     * Encrypt a TLS 1.3 record.
     *
     * @param direction  client_write or server_write
     * @param innerType  actual inner content type (handshake / application_data)
     * @param plaintext  payload before encryption
     * @return TLSCiphertext wire format (5-byte header + AEAD-encrypted payload)
     */
    suspend fun encrypt(direction: borg.trikeshed.userspace.nio.tls.codec.RecordDirection, innerType: borg.trikeshed.userspace.nio.tls.record.ContentType, plaintext: ByteArray): ByteArray

    /**
     * Decrypt a TLS 1.3 record.
     *
     * @param direction  which side's keys to use for decryption
     * @param wire       full TLSCiphertext bytes (including 5-byte header)
     * @return decrypted plaintext, or null on AEAD authentication failure
     */
    suspend fun decrypt(direction: borg.trikeshed.userspace.nio.tls.codec.RecordDirection, wire: ByteArray): ByteArray?

    /**
     * Install traffic keys for both directions.
     *
     * @param clientKey client_write AEAD key
     * @param clientIv  client_write initialization vector (12 bytes)
     * @param serverKey server_write AEAD key
     * @param serverIv  server_write initialization vector (12 bytes)
     */
    fun installKeys(clientKey: ByteArray, clientIv: ByteArray, serverKey: ByteArray, serverIv: ByteArray)
}

/**
 * Which direction's keys to use for record encryption/decryption.
 */
enum class RecordDirection { CLIENT_WRITE, SERVER_WRITE }
