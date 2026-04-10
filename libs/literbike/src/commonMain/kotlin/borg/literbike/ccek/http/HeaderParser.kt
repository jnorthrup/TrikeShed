package borg.literbike.ccek.http

import kotlin.experimental.compareTo

/**
 * HTTP/1.1 Header Parser - relaxfactory Rfc822HeaderState pattern
 *
 * Zero-copy header parsing from ByteBuffer-like buffer.
 * Parses only what's necessary, defers expensive string operations.
 */

/**
 * HTTP/1.1 constants
 */
object HttpConstants {
    const val HTTP_1_1 = "HTTP/1.1"
    const val HTTP_1_0 = "HTTP/1.0"
    val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    const val CR: Byte = '\r'.code.toByte()
    const val LF: Byte = '\n'.code.toByte()
    const val COLON: Byte = ':'.code.toByte()
    const val SPACE: Byte = ' '.code.toByte()
}

/**
 * HTTP methods
 */
enum class HttpMethod(val methodString: String) {
    GET("GET"), POST("POST"), PUT("PUT"), DELETE("DELETE"),
    HEAD("HEAD"), OPTIONS("OPTIONS"), PATCH("PATCH"), UNKNOWN("UNKNOWN");

    companion object {
        fun fromBytes(s: ByteArray): HttpMethod = when {
            s contentEquals "GET".toByteArray() -> GET
            s contentEquals "POST".toByteArray() -> POST
            s contentEquals "PUT".toByteArray() -> PUT
            s contentEquals "DELETE".toByteArray() -> DELETE
            s contentEquals "HEAD".toByteArray() -> HEAD
            s contentEquals "OPTIONS".toByteArray() -> OPTIONS
            s contentEquals "PATCH".toByteArray() -> PATCH
            else -> UNKNOWN
        }
    }
}

/**
 * HTTP status codes
 */
enum class HttpStatus(val code: UShort, val reasonPhrase: String) {
    Status200(200u, "OK"),
    Status201(201u, "Created"),
    Status204(204u, "No Content"),
    Status301(301u, "Moved Permanently"),
    Status302(302u, "Found"),
    Status304(304u, "Not Modified"),
    Status400(400u, "Bad Request"),
    Status401(401u, "Unauthorized"),
    Status403(403u, "Forbidden"),
    Status404(404u, "Not Found"),
    Status405(405u, "Method Not Allowed"),
    Status500(500u, "Internal Server Error"),
    Status502(502u, "Bad Gateway"),
    Status503(503u, "Service Unavailable");

    companion object {
        fun fromUShort(code: UShort): HttpStatus = when (code) {
            200u -> Status200
            201u -> Status201
            204u -> Status204
            301u -> Status301
            302u -> Status302
            304u -> Status304
            400u -> Status400
            401u -> Status401
            403u -> Status403
            404u -> Status404
            405u -> Status405
            500u -> Status500
            502u -> Status502
            503u -> Status503
            else -> Status500
        }
    }
}

/**
 * Common HTTP headers
 */
object Headers {
    const val CONTENT_LENGTH = "Content-Length"
    const val CONTENT_TYPE = "Content-Type"
    const val TRANSFER_ENCODING = "Transfer-Encoding"
    const val CONNECTION = "Connection"
    const val HOST = "Host"
    const val COOKIE = "Cookie"
    const val LOCATION = "Location"
    const val AUTHORIZATION = "Authorization"
    const val USER_AGENT = "User-Agent"
    const val ACCEPT = "Accept"
    const val ACCEPT_ENCODING = "Accept-Encoding"
}

/**
 * MIME types
 */
object MimeTypes {
    const val TEXT_HTML = "text/html"
    const val TEXT_PLAIN = "text/plain"
    const val TEXT_CSS = "text/css"
    const val TEXT_JAVASCRIPT = "text/javascript"
    const val APPLICATION_JSON = "application/json"
    const val APPLICATION_OCTET_STREAM = "application/octet-stream"
}

/**
 * HTTP parse error
 */
sealed class HttpParseError(message: String) : Exception(message) {
    object InvalidRequestLine : HttpParseError("Invalid request line")
    object InvalidStatusLine : HttpParseError("Invalid status line")
    object InvalidHeader : HttpParseError("Invalid header")
    object BufferOverflow : HttpParseError("Buffer overflow")
}

/**
 * Header parser state - holds buffer and parsed state
 * Like Rfc822HeaderState, parses lazily and caches results
 */
class HeaderParser {
    private var buffer: ByteArray = ByteArray(512)
    private var bufferSize: Int = 0
    private val headers = mutableMapOf<String, String>()

    // Request line components
    private var method: HttpMethod? = null
    private var path: String? = null
    private var protocol: String? = null

    // Response line components
    private var status: HttpStatus? = null
    private var statusText: String? = null

    // Parse state
    private var headerComplete: Boolean = false
    private var contentLength: Int? = null

    companion object {
        fun new(): HeaderParser = HeaderParser()
        fun withCapacity(cap: Int): HeaderParser {
            val p = HeaderParser()
            p.buffer = ByteArray(cap)
            return p
        }
    }

    /**
     * Clear parser for reuse
     */
    fun clear() {
        bufferSize = 0
        headers.clear()
        method = null
        path = null
        protocol = null
        status = null
        statusText = null
        headerComplete = false
        contentLength = null
    }

    /**
     * Get header buffer
     */
    fun buffer(): ByteArray = buffer.copyOf(bufferSize)

    /**
     * Set buffer (for buffer swapping)
     */
    fun setBuffer(buf: ByteArray) {
        buffer = buf
        bufferSize = buf.size
        headerComplete = false
        headers.clear()
        method = null
        path = null
        protocol = null
    }

    /**
     * Append data to buffer
     */
    fun append(data: ByteArray) {
        val newBuffer = ByteArray(bufferSize + data.size)
        buffer.copyInto(newBuffer, 0, 0, bufferSize)
        data.copyInto(newBuffer, bufferSize)
        buffer = newBuffer
        bufferSize += data.size
        headerComplete = false
    }

    /**
     * Check if headers are complete
     */
    fun headersComplete(): Boolean {
        if (headerComplete) return true
        if (bufferSize < 4) return false
        return buffer[bufferSize - 4] == HttpConstants.CR &&
                buffer[bufferSize - 3] == HttpConstants.LF &&
                buffer[bufferSize - 2] == HttpConstants.CR &&
                buffer[bufferSize - 1] == HttpConstants.LF
    }

    /**
     * Parse headers if complete
     */
    fun parse(): Result<Boolean> {
        if (headerComplete) return Result.success(true)

        val terminator = byteArrayOf(HttpConstants.CR, HttpConstants.LF, HttpConstants.CR, HttpConstants.LF)
        val pos = findTerminator(terminator) ?: return Result.success(false)

        val headerEnd = pos

        // Find first line ending
        val firstLineEnd = findCrlf(0, headerEnd) ?: return Result.success(false)

        val firstLineData = buffer.copyOfRange(0, firstLineEnd)
        if (firstLineData.startsWith("HTTP/".toByteArray())) {
            parseStatusLine(firstLineData)
        } else {
            parseRequestLine(firstLineData)
        }

        val headerStart = firstLineEnd + 2
        val headerLinesData = buffer.copyOfRange(headerStart, headerEnd)

        // Split by LF and parse each header
        var lineStart = 0
        while (lineStart < headerLinesData.size) {
            val lineEnd = headerLinesData.indexOfFirstOrElse(lineStart) { it == HttpConstants.LF.toByte() } ?: headerLinesData.size
            var line = headerLinesData.copyOfRange(lineStart, lineEnd)

            // Remove leading/trailing CR
            if (line.isNotEmpty() && line[0] == HttpConstants.CR) line = line.copyOfRange(1, line.size)
            if (line.isNotEmpty() && line.last() == HttpConstants.CR) line = line.copyOfRange(0, line.size - 1)

            if (line.isNotEmpty()) {
                val colonPos = line.indexOfFirstOrElse(0) { it == HttpConstants.COLON.toByte() }
                if (colonPos != null) {
                    val name = line.copyOfRange(0, colonPos).decodeToString().trim()
                    val value = line.copyOfRange(colonPos + 1, line.size).decodeToString().trim()

                    if (name.equals(Headers.CONTENT_LENGTH, ignoreCase = true)) {
                        contentLength = value.toIntOrNull()
                    }
                    headers[name] = value
                }
            }

            lineStart = lineEnd + 1
        }

        headerComplete = true
        return Result.success(true)
    }

    private fun findTerminator(terminator: ByteArray): Int? {
        for (i in 0 until bufferSize - 3) {
            if (buffer[i] == terminator[0] && buffer[i + 1] == terminator[1] &&
                buffer[i + 2] == terminator[2] && buffer[i + 3] == terminator[3]) {
                return i
            }
        }
        return null
    }

    private fun findCrlf(start: Int, end: Int): Int? {
        for (i in start until end - 1) {
            if (buffer[i] == HttpConstants.CR.toByte() && buffer[i + 1] == HttpConstants.LF.toByte()) {
                return i
            }
        }
        return null
    }

    private fun ByteArray.indexOfFirstOrElse(start: Int, predicate: (Byte) -> Boolean): Int? {
        for (i in start until size) {
            if (predicate(this[i])) return i
        }
        return null
    }

    private fun parseRequestLine(line: ByteArray) {
        val parts = line.splitBySpace()
        if (parts.size >= 2) {
            method = HttpMethod.fromBytes(parts[0])
            path = parts[1].decodeToString()
            if (parts.size >= 3) {
                protocol = parts[2].decodeToString()
            }
        }
    }

    private fun parseStatusLine(line: ByteArray) {
        val parts = line.splitBySpace()
        if (parts.size >= 2) {
            protocol = parts[0].decodeToString()
            val code = parts[1].decodeToString().toUShortOrNull()
            if (code != null) {
                status = HttpStatus.fromUShort(code)
            }
            if (parts.size >= 3) {
                statusText = parts[2].decodeToString()
            }
        }
    }

    private fun ByteArray.splitBySpace(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (i in indices) {
            if (this[i] == HttpConstants.SPACE.toByte()) {
                result.add(copyOfRange(start, i))
                start = i + 1
            }
        }
        if (start <= size) {
            result.add(copyOfRange(start, size))
        }
        return result
    }

    // Getters
    fun method(): HttpMethod? = method
    fun path(): String? = path
    fun protocol(): String? = protocol
    fun status(): HttpStatus? = status

    fun setStatus(status: HttpStatus) { this.status = status }

    fun header(name: String): String? = headers[name]
    fun headers(): Map<String, String> = headers

    fun setHeader(name: String, value: String) {
        headers[name] = value
    }

    fun contentLength(): Int? = contentLength

    fun bodyOffset(): Int? {
        if (!headerComplete) return null
        val terminator = byteArrayOf(HttpConstants.CR, HttpConstants.LF, HttpConstants.CR, HttpConstants.LF)
        val pos = findTerminator(terminator) ?: return null
        return pos + 4
    }

    /**
     * Build response header bytes
     */
    fun buildResponse(bodyLen: Int): ByteArray {
        val status = this.status ?: HttpStatus.Status200
        val lines = mutableListOf<String>()
        lines.add("HTTP/1.1 ${status.code} ${status.reasonPhrase}")
        lines.add("${Headers.CONTENT_LENGTH}: $bodyLen")

        for ((name, value) in headers) {
            if (!name.equals(Headers.CONTENT_LENGTH, ignoreCase = true)) {
                lines.add("$name: $value")
            }
        }
        lines.add("") // Empty line

        return lines.joinToString("\r\n").toByteArray()
    }

    /**
     * Build simple response (status + content-type + body)
     */
    fun buildSimpleResponse(status: HttpStatus, contentType: String, body: ByteArray): ByteArray {
        val headerStr = "HTTP/1.1 ${status.code} ${status.reasonPhrase}\r\n" +
                "${Headers.CONTENT_TYPE}: $contentType\r\n" +
                "${Headers.CONTENT_LENGTH}: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        return headerStr.toByteArray() + body
    }
}
