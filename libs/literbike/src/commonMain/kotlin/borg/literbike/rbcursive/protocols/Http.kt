package borg.literbike.rbcursive.protocols

import borg.literbike.rbcursive.*

/**
 * HTTP protocol parser using RBCursive combinators.
 * Zero-allocation, SIMD-accelerated HTTP parsing.
 * Ported from literbike/src/rbcursive/protocols/http.rs.
 */

class HttpParser(
    private val scanner: SimdScanner = ScalarScanner()
) {

    companion object {
        fun new(): HttpParser = HttpParser()
    }

    /** Parse complete HTTP request */
    fun parseRequest(input: ByteArray): ParseResult<HttpRequestFull> {
        val requestLineResult = parseRequestLine(input)
        if (requestLineResult is ParseResult.Error) return requestLineResult
        if (requestLineResult is ParseResult.Incomplete) return requestLineResult

        val (method, path, version) = (requestLineResult as ParseResult.Complete).value
        var consumed = requestLineResult.consumed

        // Expect \r\n after request line
        if (consumed + 1 >= input.size ||
            input[consumed] != '\r'.code.toByte() ||
            input[consumed + 1] != '\n'.code.toByte()
        ) {
            return if (consumed >= input.size) ParseResult.Incomplete(consumed)
            else ParseResult.Error(ParseError.InvalidInput, consumed)
        }
        consumed += 2

        val headersResult = parseHeaders(input.copyOfRange(consumed, input.size))
        return when (headersResult) {
            is ParseResult.Complete -> {
                ParseResult.Complete(
                    HttpRequestFull(method, path, version, headersResult.value),
                    consumed + headersResult.consumed
                )
            }
            is ParseResult.Incomplete -> headersResult
            is ParseResult.Error -> ParseResult.Error(
                headersResult.error,
                consumed + headersResult.consumed
            )
        }
    }

    /** Parse HTTP method using SIMD acceleration */
    fun parseMethod(input: ByteArray): ParseResult<HttpMethod> {
        val spaces = scanner.scanBytes(input, byteArrayOf(' '.code.toByte()))
        if (spaces.isNotEmpty()) {
            val spacePos = spaces[0]
            if (spacePos > 0) {
                val methodBytes = input.copyOf(spacePos)
                HttpMethod.fromBytes(methodBytes)?.let { method ->
                    return ParseResult.Complete(method, spacePos)
                }
            }
        }

        // Fallback to combinator-based parsing
        val methods = listOf(
            "GET" to HttpMethod.Get,
            "POST" to HttpMethod.Post,
            "PUT" to HttpMethod.Put,
            "DELETE" to HttpMethod.Delete,
            "HEAD" to HttpMethod.Head,
            "OPTIONS" to HttpMethod.Options,
            "CONNECT" to HttpMethod.Connect,
            "PATCH" to HttpMethod.Patch,
            "TRACE" to HttpMethod.Trace,
        )
        for ((name, method) in methods) {
            val bytes = name.toByteArray()
            if (input.size >= bytes.size && input.copyOf(bytes.size).contentEquals(bytes)) {
                return ParseResult.Complete(method, bytes.size)
            }
        }
        return ParseResult.Error(ParseError.InvalidInput, 0)
    }

    /** Parse HTTP request line (method, path, version) */
    private fun parseRequestLine(input: ByteArray): ParseResult<Triple<HttpMethod, ByteArray, HttpVersion>> {
        // Parse method
        val methodResult = parseMethod(input)
        val (method, methodConsumed) = when (methodResult) {
            is ParseResult.Complete -> methodResult.value to methodResult.consumed
            is ParseResult.Incomplete -> return methodResult
            is ParseResult.Error -> return methodResult
        }

        var remaining = input.copyOfRange(methodConsumed, input.size)

        // Parse space after method
        if (remaining.isEmpty() || remaining[0] != ' '.code.toByte()) {
            return ParseResult.Incomplete(methodConsumed + remaining.size)
        }
        remaining = remaining.copyOfRange(1, remaining.size)
        val spaceConsumed = 1

        // Parse path using SIMD to find next space
        val spaces = scanner.scanBytes(remaining, byteArrayOf(' '.code.toByte()))
        val (path, pathConsumed) = if (spaces.isNotEmpty()) {
            val spacePos = spaces[0]
            remaining.copyOf(spacePos) to spacePos
        } else {
            return ParseResult.Incomplete(methodConsumed + spaceConsumed + remaining.size)
        }

        remaining = remaining.copyOfRange(pathConsumed, remaining.size)

        // Parse space before version
        if (remaining.isEmpty() || remaining[0] != ' '.code.toByte()) {
            return ParseResult.Incomplete(methodConsumed + spaceConsumed + pathConsumed + remaining.size)
        }
        remaining = remaining.copyOfRange(1, remaining.size)
        val space2Consumed = 1

        // Parse HTTP version
        val versionResult = parseVersion(remaining)
        return when (versionResult) {
            is ParseResult.Complete -> {
                val totalConsumed = methodConsumed + spaceConsumed + pathConsumed + space2Consumed + versionResult.consumed
                ParseResult.Complete(
                    Triple(method, path, versionResult.value),
                    totalConsumed
                )
            }
            is ParseResult.Incomplete -> ParseResult.Incomplete(
                methodConsumed + spaceConsumed + pathConsumed + space2Consumed + versionResult.consumed
            )
            is ParseResult.Error -> ParseResult.Error(
                versionResult.error,
                methodConsumed + spaceConsumed + pathConsumed + space2Consumed + versionResult.consumed
            )
        }
    }

    /** Parse HTTP version */
    private fun parseVersion(input: ByteArray): ParseResult<HttpVersion> {
        return when {
            input.size >= 8 && input.copyOf(8).contentEquals("HTTP/1.1".toByteArray()) ->
                ParseResult.Complete(HttpVersion.Http11, 8)
            input.size >= 8 && input.copyOf(8).contentEquals("HTTP/1.0".toByteArray()) ->
                ParseResult.Complete(HttpVersion.Http10, 8)
            input.size >= 7 && input.copyOf(7).contentEquals("HTTP/2".toByteArray()) ->
                ParseResult.Complete(HttpVersion.Http2, 7)
            input.size < 8 -> ParseResult.Incomplete(input.size)
            else -> ParseResult.Error(ParseError.InvalidInput, 0)
        }
    }

    /** Parse HTTP headers using SIMD acceleration */
    private fun parseHeaders(input: ByteArray): ParseResult<MutableList<HttpHeader>> {
        val headers = mutableListOf<HttpHeader>()
        var consumed = 0
        var remaining = input

        while (true) {
            // Check for end of headers (empty line)
            if (remaining.size >= 2 &&
                remaining[0] == '\r'.code.toByte() &&
                remaining[1] == '\n'.code.toByte()
            ) {
                consumed += 2
                break
            }

            if (remaining.isEmpty()) {
                return ParseResult.Incomplete(consumed)
            }

            // Parse single header using SIMD to find colon
            val colons = scanner.scanBytes(remaining, byteArrayOf(':'.code.toByte()))
            val colonPos = if (colons.isNotEmpty()) colons[0] else {
                return ParseResult.Incomplete(consumed + remaining.size)
            }

            val headerName = remaining.copyOf(colonPos)
            var afterColon = remaining.copyOfRange(colonPos + 1, remaining.size)

            // Skip optional whitespace after colon
            while (afterColon.isNotEmpty() &&
                (afterColon[0] == ' '.code.toByte() || afterColon[0] == '\t'.code.toByte())
            ) {
                afterColon = afterColon.copyOfRange(1, afterColon.size)
            }

            // Find end of line
            val crlfPos = findCrlf(afterColon)
            val (headerValue, lineEndConsumed) = if (crlfPos != null) {
                afterColon.copyOf(crlfPos) to crlfPos + 2
            } else {
                return ParseResult.Incomplete(consumed + remaining.size)
            }

            headers.add(HttpHeader(headerName, headerValue))

            val headerConsumed = colonPos + 1 + (afterColon.size - (remaining.size - colonPos - 1)) + lineEndConsumed
            consumed += headerConsumed
            remaining = remaining.copyOfRange(headerConsumed, remaining.size)
        }

        return ParseResult.Complete(headers, consumed)
    }

    /** Find CRLF sequence using SIMD */
    private fun findCrlf(data: ByteArray): Int? {
        val crPositions = scanner.scanBytes(data, byteArrayOf('\r'.code.toByte()))
        for (pos in crPositions) {
            if (pos + 1 < data.size && data[pos + 1] == '\n'.code.toByte()) {
                return pos
            }
        }
        return null
    }
}
