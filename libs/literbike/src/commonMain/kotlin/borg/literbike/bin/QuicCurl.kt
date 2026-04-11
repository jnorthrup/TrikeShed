package borg.literbike.bin

/**
 * QUIC Curl - QUIC client for making HTTP-like requests.
 * Ported from literbike/src/bin/quic_curl.rs.
 */

/**
 * QUIC request configuration.
 */
data class QuicRequestConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMillis: Long = 30000,
    val alpnProtocols: List<String> = listOf("h3"),
    val insecure: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuicRequestConfig) return false
        return url == other.url && method == other.method && headers == other.headers &&
                (body?.contentEquals(other.body) ?: (other.body == null)) &&
                timeoutMillis == other.timeoutMillis && alpnProtocols == other.alpnProtocols &&
                insecure == other.insecure
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeoutMillis.hashCode()
        result = 31 * result + alpnProtocols.hashCode()
        result = 31 * result + insecure.hashCode()
        return result
    }
}

/**
 * QUIC response.
 */
data class QuicResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val alpnProtocol: String,
    val connectionTimeNanos: Long
) {
    fun bodyAsString(): String = body.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QuicResponse) return false
        return statusCode == other.statusCode && headers == other.headers &&
                body.contentEquals(other.body) && alpnProtocol == other.alpnProtocol &&
                connectionTimeNanos == other.connectionTimeNanos
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + alpnProtocol.hashCode()
        result = 31 * result + connectionTimeNanos.hashCode()
        return result
    }
}

/**
 * QUIC client for making requests.
 */
class QuicClient {
    /**
     * Execute a QUIC request.
     * Note: Full QUIC implementation requires a proper QUIC library.
     * This is a placeholder for the interface.
     */
    fun execute(config: QuicRequestConfig): Result<QuicResponse> {
        println("QUIC request to ${config.url} (mock - requires QUIC library)")
        return Result.failure(UnsupportedOperationException(
            "Full QUIC implementation requires a proper QUIC library (e.g., quic4j)"
        ))
    }
}

/**
 * Main entry point for QUIC Curl.
 */
fun runQuicCurl(url: String = "https://example.com", insecure: Boolean = false) {
    println("QUIC Curl - $url")
    val client = QuicClient()
    val config = QuicRequestConfig(
        url = url,
        insecure = insecure
    )

    client.execute(config).fold(
        onSuccess = { response ->
            println("HTTP/${response.statusCode}")
            response.headers.forEach { (k, v) -> println("$k: $v") }
            println()
            println(response.bodyAsString())
        },
        onFailure = { e ->
            println("Error: ${e.message}")
        }
    )
}
