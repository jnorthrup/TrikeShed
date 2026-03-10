package borg.trikeshed.net

import borg.trikeshed.net.http.HttpRequestLineSpec
import borg.trikeshed.net.http.parseHttpHeaders
import borg.trikeshed.net.http.parseHttpRequestLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ── Contract tests ────────────────────────────────────────────────────────────

class HttpParserContractTest {

    @Test
    fun parseGETRequestLine() {
        val result = parseHttpRequestLine("GET /index.html HTTP/1.1")
        assertNotNull(result, "GET request line must parse to non-null")
        assertEquals("GET", result.method)
        assertEquals("/index.html", result.requestTarget)
        assertEquals("HTTP/1.1", result.httpVersion)
    }

    @Test
    fun parsePOSTRequestLine() {
        val result = parseHttpRequestLine("POST /api/v1/data HTTP/1.1")
        assertNotNull(result)
        assertEquals("POST", result.method)
        assertEquals("/api/v1/data", result.requestTarget)
        assertEquals("HTTP/1.1", result.httpVersion)
    }

    @Test
    fun parseHEADRequestLine() {
        val result = parseHttpRequestLine("HEAD / HTTP/1.0")
        assertNotNull(result)
        assertEquals("HEAD", result.method)
        assertEquals("/", result.requestTarget)
        assertEquals("HTTP/1.0", result.httpVersion)
    }

    @Test
    fun emptyLineReturnsNull() {
        val result = parseHttpRequestLine("")
        assertNull(result, "Empty line must return null")
    }

    @Test
    fun malformedLineReturnsNull() {
        val result = parseHttpRequestLine("BADLINE")
        assertNull(result, "Single-token line must return null")
    }

    @Test
    fun parseContentTypeHeader() {
        val headers = parseHttpHeaders(listOf("Content-Type: text/html"))
        assertEquals(1, headers.size)
        assertEquals("Content-Type", headers[0].first)
        assertEquals("text/html", headers[0].second)
    }

    @Test
    fun parseMultipleHeaders() {
        val headers = parseHttpHeaders(listOf(
            "Content-Type: application/json",
            "Content-Length: 42",
            "X-Custom: value",
        ))
        assertEquals(3, headers.size)
        assertEquals("Content-Type" to "application/json", headers[0])
        assertEquals("Content-Length" to "42", headers[1])
        assertEquals("X-Custom" to "value", headers[2])
    }

    @Test
    fun headerValueWhitespaceIsTrimmed() {
        val headers = parseHttpHeaders(listOf("Host:  example.com  "))
        assertEquals(1, headers.size)
        assertEquals("example.com", headers[0].second)
    }

    @Test
    fun headerLinesWithoutColonAreSkipped() {
        val headers = parseHttpHeaders(listOf("malformed-no-colon", "Valid: yes"))
        assertEquals(1, headers.size)
        assertEquals("Valid" to "yes", headers[0])
    }
}
