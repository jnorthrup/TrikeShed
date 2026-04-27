package borg.trikeshed.couch.htx

/**
 * HTX start-line — matches HAProxy struct htx_sl.
 * Request: method + uri + version
 * Response: version + status + reason
 */
data class HtxStartLine(
    val flags: UInt = 0u,
    val method: HttpMethod? = null,
    val status: Int? = null,
    val uri: ByteArray = byteArrayOf(),
    val version: Pair<Int, Int> = 1 to 1,
    val reason: ByteArray = byteArrayOf(),
) {
    val isRequest: Boolean get() = method != null

    companion object {
        fun request(method: HttpMethod, uri: ByteArray, major: Int = 1, minor: Int = 1): HtxStartLine =
            HtxStartLine(
                flags = 0u,
                method = method,
                uri = uri,
                version = major to minor,
            )

        fun response(status: Int, reason: ByteArray, major: Int = 1, minor: Int = 1): HtxStartLine =
            HtxStartLine(
                flags = HtxSlFlags.IS_RESP.mask or HtxSlFlags.VER_11.mask,
                status = status,
                reason = reason,
                version = major to minor,
            )

        /**
         * Parse an HTTP request start-line from raw bytes.
         * Format: METHOD SP URI SP HTTP/MAJOR.MINOR CR LF
         *
         * Edge cases handled:
         * - Non-standard methods (PROPFIND, MKCOL, etc.) → HttpMethod.Unknown
         * - Lowercase methods → uppercased before lookup
         * - Malformed version → defaults to "HTTP/1.1"
         * - Extra whitespace → trimmed
         * - Missing version → assumes HTTP/0.9
         */
        fun parse(bytes: ByteArray): HtxStartLine {
            val s = bytes.decodeToString().trimEnd('\r', '\n')
            val parts = s.split(" ", limit = 3)
            if (parts.size < 2) {
                // Bare-minimum: METHOD URI
                return HtxStartLine(
                    method = HttpMethod.Unknown,
                    uri = s.encodeToByteArray(),
                    version = 0 to 9,
                )
            }
            val methodStr = parts[0]
            val uri = parts[1].encodeToByteArray()
            val (major, minor) = if (parts.size >= 3) {
                parseHttpVersion(parts[2])
            } else {
                0 to 9 // HTTP/0.9 — no version in request
            }
            val method = HttpMethod.fromString(methodStr.uppercase()) ?: HttpMethod.Unknown
            return HtxStartLine(
                flags = if (method == HttpMethod.Unknown) HtxSlFlags.SCHM_HTTP.mask else 0u,
                method = method,
                uri = uri,
                version = major to minor,
            )
        }

        private fun parseHttpVersion(versionStr: String): Pair<Int, Int> {
            // Expect "HTTP/1.1" or "HTTP/2" or "HTTP/3"
            val trimmed = versionStr.trim()
            if (!trimmed.startsWith("HTTP/", ignoreCase = true)) {
                return 1 to 1 // default
            }
            val ver = trimmed.substring(5) // after "HTTP/"
            return when (ver) {
                "1.0" -> 1 to 0
                "1.1" -> 1 to 1
                "2", "2.0" -> 2 to 0
                "3", "3.0" -> 3 to 0
                else -> {
                    val dot = ver.indexOf('.')
                    if (dot > 0) {
                        val m = ver.substring(0, dot).toIntOrNull() ?: 1
                        val n = ver.substring(dot + 1).toIntOrNull() ?: 1
                        m to n
                    } else {
                        ver.toIntOrNull()?.let { it to 0 } ?: (1 to 1)
                    }
                }
            }
        }
    }
}
