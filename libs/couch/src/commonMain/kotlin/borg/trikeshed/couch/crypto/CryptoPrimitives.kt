package borg.trikeshed.couch.crypto

/**
 * Platform crypto primitives for TrikeShed.
 *
 * Each primitive is declared as expect here; platform source sets
 * (jvmMain, posixMain, jsMain) provide actual implementations.
 */

// ── X25519 (RFC 7748) ────────────────────────────────────────────────────────────

/** 32-byte X25519key (scalar clamped per RFC 7748 §5). */
expect class X25519PrivateKey(raw: ByteArray) {
    val raw: ByteArray
}

/** 32-byte X25519 public key (u-coordinate). */
expect class X25519PublicKey(raw: ByteArray) {
    val raw: ByteArray
}

/** Generate a new X25519 key pair. */
expect fun x25519GenerateKeyPair(): Pair<X25519PrivateKey, X25519PublicKey>

/**
 * X25519 Diffie-Hellman: scalar * u-coordinate → shared secret.
 * [ours] is thescalar, [theirs] is the peer's public u-coordinate.
 * Returns 32-byte shared secret per RFC 7748 §6.1.
 */
expect fun x25519Dh(ours: X25519PrivateKey, theirs: X25519PublicKey): ByteArray

// ── HKDF-SHA256 (RFC 5869) ───────────────────────────────────────────────────────

/**
 * HKDF-Extract: PRK = HMAC-SHA256(salt, ikm).
 * [salt] is optional (pass empty ByteArray for RFC-default zero-salt).
 */
expect fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray

/**
 * HKDF-Expand: OKM = T(1) || T(2) || ... truncated to [length].
 * Each T(i) = HMAC-SHA256(PRK, T(i-1) || info || i).
 * [length] must not exceed 255 * 32 = 8160 bytes.
 */
expect fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray

// ── AES-256-GCM (NIST SP 800-38D) ────────────────────────────────────────────────

/** AES-256-GCM encrypted output: ciphertext || 16-byte authentication tag. */
data class AesGcmCiphertext(
    val ciphertext: ByteArray,
    val tag: ByteArray, // 16 bytes
)

/**
 * AES-256-GCM encrypt.
 * [key] must be 32 bytes.  [nonce] should be 12 bytes (96 bits).
 * [plaintext] may be empty.  [aad] is optional additional authenticated data.
 */
expect fun aes256GcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray = byteArrayOf(),
): AesGcmCiphertext

/**
 * AES-256-GCM decrypt.
 * Returns null if authentication fails (tag mismatch).
 */
expect fun aes256GcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
    aad: ByteArray = byteArrayOf(),
): ByteArray?
