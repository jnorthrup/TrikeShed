package borg.trikeshed.tls.record

/**
 * TLS 1.3 ContentType (RFC 8446 §5.1).
 *
 * In TLS 1.3, the record layer uses these types for unencrypted framing.
 * Actual encrypted records use an opaque_type byte set to 0x17 (application_data)
 * to hide the true content type from passive observers.
 */
enum class ContentType(val code: Byte) {
    /** 0x14 — signals transition to authenticated encryption */
    CHANGE_CIPHER_SPEC(0x14),
    /** 0x15 — warning or fatal error */
    ALERT(0x15),
    /** 0x16 — handshake messages (ClientHello, ServerHello, etc.) */
    HANDSHAKE(0x16),
    /** 0x17 — application data */
    APPLICATION_DATA(0x17);

    companion object {
        fun fromCode(code: Byte): ContentType = entries.first { it.code == code }
    }
}
