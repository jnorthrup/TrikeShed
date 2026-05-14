package borg.trikeshed.userspace.nio.tls.codec.kdf

import kotlin.coroutines.CoroutineContext

/**
 * HKDF with SHA-256 (RFC 5869).
 *
 * Used by TLS 1.3 for all key derivation. The TLS 1.3 key schedule
 * calls extract → expand_label repeatedly through the handshake phases.
 *
 * CCEK: register platform HKDF in the coroutine context; the key schedule
 * reads it via `coroutineContext[HkdfSha256.Key]`.
 */
interface HkdfSha256 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<HkdfSha256>

    /** Hash output length (32 bytes for SHA-256). */
    val hashLen: Int get() = 32

    /**
     * HKDF-Extract: PRK = HMAC-Hash(salt, IKM).
     *
     * If salt is empty or null, use a string of HashLen zeros (RFC 5869 §2.2).
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray

    /**
     * HKDF-Expand: OKM = T(1) || T(2) || ... || T(N)
     * where T(0) = empty, T(i) = HMAC-Hash(PRK, T(i-1) || info || byte(i))
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray

    /**
     * HKDF-Expand-Label (RFC 8446 §7.1).
     *
     * Derive-Secret(Secret, Label, Messages) =
     *   HKDF-Expand-Label(Secret, Label, Transcript-Hash(Messages), Hash.length)
     *
     * HKDF-Expand-Label(Secret, Label, Context, Length) =
     *   HKDF-Expand(Secret, HkdfLabel, Length)
     *
     * where HkdfLabel = struct {
     *   uint16 length = Length;
     *   opaque label<7..255> = "tls13 " + Label;
     *   opaque context<0..255> = Context;
     * }
     */
    fun expandLabel(secret: ByteArray, label: CharSequence, context: ByteArray, length: Int): ByteArray
}
