package borg.trikeshed.userspace.htx

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
    }
}
