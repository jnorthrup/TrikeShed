package borg.trikeshed.couch.crypto

// ── X25519 (RFC 7748) ────────────────────────────────────────────────────────────

actual class X25519PrivateKey actual constructor(raw: ByteArray) {
    actual val raw: ByteArray = raw.copyOf()
}

actual class X25519PublicKey actual constructor(raw: ByteArray) {
    actual val raw: ByteArray = raw.copyOf()
}

actual fun x25519GenerateKeyPair(): Pair<X25519PrivateKey, X25519PublicKey> {
    error("X25519 not implemented for WasmJs")
}

actual fun x25519Dh(ours: X25519PrivateKey, theirs: X25519PublicKey): ByteArray {
    error("X25519 DH not implemented for WasmJs")
}

// ── HKDF-SHA256 (RFC 5869) ───────────────────────────────────────────────────────

actual fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
    error("HKDF-Extract not implemented for WasmJs")
}

actual fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    error("HKDF-Expand not implemented for WasmJs")
}

// ── AES-256-GCM (NIST SP 800-38D) ────────────────────────────────────────────────

actual fun aes256GcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): AesGcmCiphertext {
    error("AES-256-GCM encrypt not implemented for WasmJs")
}

actual fun aes256GcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    tag: ByteArray,
    aad: ByteArray,
): ByteArray? {
    error("AES-256-GCM decrypt not implemented for WasmJs")
}
