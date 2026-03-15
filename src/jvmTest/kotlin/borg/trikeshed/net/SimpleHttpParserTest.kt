package borg.trikeshed.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimpleHttpParserTest {
    @Test
    fun parseSimpleRequestLine() {
        val requestLine = "GET /index.html HTTP/1.1"

        val result = parseHttpRequestLine(requestLine)

        assertNotNull(result, "GET request line must parse to non-null")
        assertEquals("GET", result!!.method)
        assertEquals("/index.html", result.path)
        assertEquals("HTTP/1.1", result.version)
    }

    @Test
    fun parsePOSTRequestLine() {
        val requestLine = "POST /api/data HTTP/1.0"

        val result = parseHttpRequestLine(requestLine)

        assertNotNull(result)
        assertEquals("POST", result!!.method)
        assertEquals("/api/data", result.path)
        assertEquals("HTTP/1.0", result.version)
    }

    @Test
    fun parseHEADRequestLine() {
        val requestLine = "HEAD / HTTP/2.0"

        val result = parseHttpRequestLine(requestLine)

        assertNotNull(result)
        assertEquals("HEAD", result!!.method)
        assertEquals("/", result.path)
        assertEquals("HTTP/2.0", result.version)
    }

    @Test
    fun parseRequestLineWithExtraSpaces() {
        val requestLine = "  PUT   /resource/123   HTTP/2.0  "

        val result = parseHttpRequestLine(requestLine)

        assertNotNull(result)
        assertEquals("PUT", result!!.method)
        assertEquals("/resource/123", result.path)
        assertEquals("HTTP/2.0", result.version)
    }

    @Test
    fun parseEmptyLineReturnsNull() {
        val result = parseHttpRequestLine("")
        assertNull(result)
    }

    @Test
    fun parseMalformedLineReturnsNull() {
        val result = parseHttpRequestLine("BADLINE")
        assertNull(result)
    }

    @Test
    fun parseLineWithOnlyTwoPartsReturnsNull() {
        val result = parseHttpRequestLine("GET /index.html")
        assertNull(result)
    }

    @Test
    fun parseLineWithTooManyPartsReturnsNull() {
        val result = parseHttpRequestLine("GET /index.html HTTP/1.1 EXTRA")
        assertNull(result)
    }

    @Test
    fun parseLineWithInvalidHttpVersionReturnsNull() {
        val result = parseHttpRequestLine("GET /index.html HTTP/INVALID")
        assertNull(result)
    }
}

data class HttpRequestLine(
    val method: String,
    val path: String,
    val version: String,
)

fun parseHttpRequestLine(line: String): HttpRequestLine? = TODO()
