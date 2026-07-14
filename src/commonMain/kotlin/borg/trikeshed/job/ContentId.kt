package borg.trikeshed.job

/**
 * ContentId — SHA-256 over canonical bytes.
 * Format: "sha256:<64 lowercase hex chars>"
 */
data class ContentId(val value: String) {
    companion object {
        fun of(bytes: ByteArray): ContentId {
            val digest = sha256(bytes)
            val hex = buildString {
                for (b in digest) {
                    append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                    append(HEX_CHARS[b.toInt() and 0x0F])
                }
            }
            return ContentId("sha256:$hex")
        }
    }

    val hex: String get() = value.removePrefix("sha256:")
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/** KMP-safe SHA-256. Uses java.security on JVM, falls back to a simple hash on other targets. */
expect fun sha256(bytes: ByteArray): ByteArray