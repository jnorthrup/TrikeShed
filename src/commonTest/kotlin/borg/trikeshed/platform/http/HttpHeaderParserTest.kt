package borg.trikeshed.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HttpHeaderParserTest {
    @Test
    fun parseContentTypeHeader() {
        val headerLine = "Content-Type: application/json"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Content-Type", result!!.name)
        assertEquals("application/json", result.value)
    }

    @Test
    fun parseHostHeader() {
        val headerLine = "Host: localhost:8080"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Host", result!!.name)
        assertEquals("localhost:8080", result.value)
    }

    @Test
    fun parseHeaderWithMultipleColons() {
        val headerLine = "X-Custom-Header: value:with:colons"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("X-Custom-Header", result!!.name)
        assertEquals("value:with:colons", result.value)
    }

    @Test
    fun parseHeaderWithWhitespace() {
        val headerLine = "   Authorization   :   Bearer token123   "

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Authorization", result!!.name)
        assertEquals("Bearer token123", result.value)
    }

    @Test
    fun parseHeaderWithEmptyValue() {
        val headerLine = "Empty-Header:"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Empty-Header", result!!.name)
        assertEquals("", result.value)
    }

    @Test
    fun parseHeaderWithOnlySpacesAfterColon() {
        val headerLine = "Spaces-Header:     "

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Spaces-Header", result!!.name)
        assertEquals("", result.value)
    }

    @Test
    fun parseInvalidHeaderWithoutColonReturnsNull() {
        val result = parseHttpHeader("InvalidHeaderWithoutColon")
        assertEquals(null, result)
    }

    @Test
    fun parseEmptyLineReturnsNull() {
        val result = parseHttpHeader("")
        assertEquals(null, result)
    }

    @Test
    fun parseHeaderLineWithColonAtStartReturnsNull() {
        val result = parseHttpHeader(":value-without-name")
        assertEquals(null, result)
    }

    @Test
    fun parseHeaderWithComplexValue() {
        val headerLine = "Content-Type: text/html; charset=utf-8"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Content-Type", result!!.name)
        assertEquals("text/html; charset=utf-8", result.value)
    }

    @Test
    fun parseContentLengthHeader() {
        val headerLine = "Content-Length: 1024"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("Content-Length", result!!.name)
        assertEquals("1024", result.value)
    }

    @Test
    fun parseUserAgentHeader() {
        val headerLine = "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

        val result = parseHttpHeader(headerLine)

        assertNotNull(result)
        assertEquals("User-Agent", result!!.name)
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", result.value)
    }
}

data class HttpHeader(
    val name: String,
    val value: String,
)

fun parseHttpHeader(line: String): HttpHeader? = TODO()
