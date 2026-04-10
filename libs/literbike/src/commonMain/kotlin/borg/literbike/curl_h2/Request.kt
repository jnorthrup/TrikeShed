package borg.literbike.curl_h2

/**
 * HTTP/2 request builder
 */
data class H2Request(
    val url: String,
    val method: String = "GET",
    val headers: MutableMap<String, String> = mutableMapOf(),
    val body: ByteArray? = null,
    val timeout: ULong = 30uL,
    val followRedirects: Boolean = true,
    val verifySsl: Boolean = false
) {
    companion object {
        /**
         * Create a new GET request
         */
        fun get(url: String): H2Request = H2Request(url = url)

        /**
         * Create a new POST request
         */
        fun post(url: String): H2Request = H2Request(url = url, method = "POST")
    }

    /**
     * Set request method
     */
    fun withMethod(method: String): H2Request = copy(method = method)

    /**
     * Set request header
     */
    fun withHeader(name: String, value: String): H2Request {
        headers[name.lowercase()] = value
        return this
    }

    /**
     * Set request body
     */
    fun withBody(body: ByteArray): H2Request = copy(body = body)

    /**
     * Set request body from string
     */
    fun withBodyText(body: String): H2Request = copy(body = body.encodeToByteArray())

    /**
     * Set timeout in seconds
     */
    fun withTimeout(seconds: ULong): H2Request = copy(timeout = seconds)

    /**
     * Set follow redirects
     */
    fun withFollowRedirects(follow: Boolean): H2Request = copy(followRedirects = follow)

    /**
     * Set SSL verification
     */
    fun withVerifySsl(verify: Boolean): H2Request = copy(verifySsl = verify)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is H2Request) return false
        if (url != other.url) return false
        if (method != other.method) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (timeout != other.timeout) return false
        if (followRedirects != other.followRedirects) return false
        if (verifySsl != other.verifySsl) return false
        return true
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeout.hashCode()
        result = 31 * result + followRedirects.hashCode()
        result = 31 * result + verifySsl.hashCode()
        return result
    }
}
