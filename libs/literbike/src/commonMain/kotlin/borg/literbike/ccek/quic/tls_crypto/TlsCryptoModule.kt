package borg.literbike.ccek.quic.tls_crypto

// ============================================================================
// TLS Crypto Module -- ported from tls_crypto/mod.rs
// ============================================================================

/** AEAD algorithms supported for QUIC packet protection */
enum class QuicAeadAlgorithm {
    Aes128Gcm,
    Aes256Gcm,
    ChaCha20Poly1305
}
