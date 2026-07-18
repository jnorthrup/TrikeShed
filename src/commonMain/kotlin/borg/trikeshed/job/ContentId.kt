package borg.trikeshed.job

/**
 * ContentId — SHA-256 over canonical bytes.
 * Format: "sha256:<64 lowercase hex chars>"
 */
data class ContentId(val value: String) {
    init {
        require(
            value.length == SHA256_TEXT_LENGTH &&
                value.startsWith(SHA256_PREFIX) &&
                value.substring(SHA256_PREFIX.length).all { it in LOWER_HEX }
        ) {
            "ContentId must be sha256 followed by 64 lowercase hexadecimal digits"
        }
    }

    companion object {
        fun of(bytes: ByteArray): ContentId {
            val digest = sha256(bytes)
            val hex = buildString {
                for (b in digest) {
                    append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                    append(HEX_CHARS[b.toInt() and 0x0F])
                }
            }
            return ContentId(SHA256_PREFIX + hex)
        }

        /** Compute ContentId over canonical CBOR of a ConfixDoc. */
        fun of(doc: borg.trikeshed.parse.confix.ConfixDoc): ContentId =
            of(CanonicalCbor.encode(doc))
    }

    val hex: String get() = value.removePrefix("sha256:")
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
private const val LOWER_HEX = "0123456789abcdef"
private const val SHA256_PREFIX = "sha256:"
private const val SHA256_TEXT_LENGTH = 71

/** KMP-safe SHA-256. Uses java.security on JVM, falls back to a simple hash on other targets. */
expect fun sha256(bytes: ByteArray): ByteArray