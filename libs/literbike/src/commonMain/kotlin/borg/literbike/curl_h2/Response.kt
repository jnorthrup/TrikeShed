package borg.literbike.curl_h2

/**
 * HTTP/2 response
 */
data class H2Response(
    val status: UShort = 200u,
    val headers: MutableMap<String, String> = mutableMapOf(),
    val body: ByteArray = ByteArray(0),
    val streamId: UInt? = null,
    val version: String = "HTTP/2"
) {
    companion object {
        fun new() = H2Response()
    }

    /**
     * Get a header value by name
     */
    fun header(name: String): String? = headers[name.lowercase()]

    /**
     * Get body as string (UTF-8)
     */
    fun text(): String = body.decodeToString()

    /**
     * Check if response is successful (2xx status)
     */
    fun isSuccess(): Boolean = status.toInt() in 200..299
}
