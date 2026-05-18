package borg.trikeshed.tls

/**
 * TLS 1.3 cipher suites (16-bit IANA identifiers).
 * Mirrors literbike/src/tls_fingerprint.rs:MOBILE_CIPHER_SUITES.
 */
object CipherSuites {
    // TLS 1.3
    const val AES_128_GCM_SHA256       = 0x1301.toShort()
    const val AES_256_GCM_SHA384       = 0x1302.toShort()
    const val CHACHA20_POLY1305_SHA256 = 0x1303.toShort()

    // TLS 1.2
    const val ECDHE_RSA_AES_128_GCM_SHA256           = 0xC02F.toShort()
    const val ECDHE_RSA_AES_256_GCM_SHA384           = 0xC030.toShort()
    const val ECDHE_ECDSA_CHACHA20_POLY1305_SHA256   = 0xCCA9.toShort()
    const val ECDHE_ECDSA_AES_128_GCM_SHA256         = 0xC02B.toShort()
    const val ECDHE_ECDSA_AES_256_GCM_SHA384         = 0xC02C.toShort()
    const val ECDHE_RSA_CHACHA20_POLY1305_SHA256     = 0xCCA8.toShort()
    const val ECDHE_RSA_AES_128_CBC_SHA              = 0xC013.toShort()
    const val ECDHE_RSA_AES_256_CBC_SHA              = 0xC014.toShort()

    /** Default mobile browser cipher suites (TLS 1.3 first, then TLS 1.2). */
    val mobileDefaults: List<Short> = listOf(
        AES_128_GCM_SHA256, AES_256_GCM_SHA384, CHACHA20_POLY1305_SHA256,
        ECDHE_RSA_AES_128_GCM_SHA256, ECDHE_RSA_AES_256_GCM_SHA384,
        ECDHE_ECDSA_CHACHA20_POLY1305_SHA256,
        ECDHE_ECDSA_AES_128_GCM_SHA256, ECDHE_ECDSA_AES_256_GCM_SHA384,
        ECDHE_RSA_CHACHA20_POLY1305_SHA256,
    )
}

/**
 * TLS extensions (16-bit IANA identifiers).
 * Mirrors literbike/src/tls_fingerprint.rs:MOBILE_TLS_EXTENSIONS.
 */
object TlsExtensions {
    const val SERVER_NAME = 0x0000.toShort()              // SNI
    const val EC_POINT_FORMATS = 0x000B.toShort()
    const val SUPPORTED_GROUPS = 0x000A.toShort()
    const val SESSION_TICKET = 0x0023.toShort()
    const val ALPN = 0x0010.toShort()
    const val OCSP_STAPLING = 0x0005.toShort()
    const val SIGNED_CERT_TIMESTAMP = 0x0012.toShort()
    const val KEY_SHARE = 0x0033.toShort()                // TLS 1.3
    const val SUPPORTED_VERSIONS = 0x002B.toShort()
    const val EARLY_DATA = 0x002A.toShort()
    const val COMPRESS_CERTIFICATE = 0x001B.toShort()
    const val PRE_SHARED_KEY = 0x0029.toShort()           // must be last

    val mobileDefaults: List<Short> = listOf(
        SERVER_NAME, EC_POINT_FORMATS, SUPPORTED_GROUPS, SESSION_TICKET,
        ALPN, OCSP_STAPLING, SIGNED_CERT_TIMESTAMP, KEY_SHARE,
        SUPPORTED_VERSIONS, EARLY_DATA, COMPRESS_CERTIFICATE, PRE_SHARED_KEY,
    )
}

/**
 * Elliptic curve groups (16-bit IANA identifiers).
 * Mirrors literbike/src/tls_fingerprint.rs:MOBILE_ELLIPTIC_CURVES.
 */
object EllipticCurves {
    const val X25519 = 0x001D.toShort()
    const val SECP256R1 = 0x0017.toShort()
    const val SECP384R1 = 0x0018.toShort()
    const val SECP521R1 = 0x0019.toShort()

    val mobileDefaults: List<Short> = listOf(X25519, SECP256R1, SECP384R1, SECP521R1)
}

/**
 * Signature scheme identifiers (16-bit IANA identifiers).
 */
object SignatureSchemes {
    const val RSA_PKCS1_SHA256    = 0x0401.toShort()
    const val RSA_PKCS1_SHA384    = 0x0501.toShort()
    const val RSA_PSS_SHA256      = 0x0804.toShort()
    const val RSA_PSS_SHA384      = 0x0805.toShort()
    const val RSA_PSS_SHA512      = 0x0806.toShort()
    const val ECDSA_SECP256R1_SHA256 = 0x0403.toShort()
    const val ECDSA_SECP384R1_SHA384 = 0x0503.toShort()
    const val ECDSA_SECP521R1_SHA512 = 0x0603.toShort()
}

/**
 * TLS protocol versions (wire format: major.minor).
 */
enum class TlsVersionKind(val major: Byte, val minor: Byte) {
    V1_2(3, 3),
    V1_3(3, 4);

    companion object {
        val mobileDefault: TlsVersionKind get() = V1_3
    }
}
