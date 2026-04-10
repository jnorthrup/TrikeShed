package borg.literbike.curl_h2

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

/**
 * HTTP/2 client for testing.
 *
 * Uses Ktor client with CIO engine for HTTP/2 support.
 * The Rust version uses curl-sys; this Kotlin version uses Ktor as the idiomatic equivalent.
 */
class H2Client(
    private val timeout: ULong = 30uL,
    private val verifySsl: Boolean = false
) {
    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = timeout.toLong() * 1000
            // UNSK: SSL verification would be configured here
            // In production, always verify SSL certificates
        }
    }

    /**
     * Create a new HTTP/2 client
     */
    companion object {
        fun new(): Result<H2Client> = runCatching { H2Client() }

        fun withTimeout(timeout: ULong): Result<H2Client> = runCatching { H2Client(timeout = timeout) }
    }

    /**
     * Perform a GET request
     */
    suspend fun get(url: String): Result<H2Response> {
        return request(H2Request.get(url))
    }

    /**
     * Perform a POST request
     */
    suspend fun post(url: String, body: ByteArray): Result<H2Response> {
        return request(H2Request.post(url).withBody(body))
    }

    /**
     * Perform a custom request
     */
    suspend fun request(req: H2Request): Result<H2Response> = runCatching {
        val response = client.request(req.url) {
            method = HttpMethod.parse(req.method)
            req.headers.forEach { (name, value) ->
                header(name, value)
            }
            req.body?.let { bodyData ->
                setBody(bodyData)
            }
            timeout {
                requestTimeoutMillis = req.timeout.toLong() * 1000
            }
            // UNSK: followRedirects and verifySsl would be configured on engine level
        }

        val responseHeaders = mutableMapOf<String, String>()
        response.headers.entries().forEach { (name, values) ->
            values.firstOrNull()?.let { value ->
                responseHeaders[name.lowercase()] = value
            }
        }

        H2Response(
            status = response.status.value.toUShort(),
            headers = responseHeaders,
            body = response.readRawBytes(),
            streamId = null, // Ktor doesn't expose stream ID directly
            version = response.version.toString()
        )
    }

    /**
     * Download a file from URL
     */
    suspend fun download(url: String): Result<ByteArray> {
        return get(url).map { it.body }
    }

    /**
     * Check if server supports HTTP/2
     */
    suspend fun checkH2Support(url: String): Result<Boolean> {
        return get(url).map { it.version.startsWith("HTTP/2") }
    }
}
