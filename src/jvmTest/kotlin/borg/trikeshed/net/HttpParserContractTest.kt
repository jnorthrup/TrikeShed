package borg.trikeshed.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ── Salvage contract stubs ────────────────────────────────────────────────────
// These stubs document the expected API surface from archival HttpParser.kt
// (v2superbikeshed lineage). They live here until a commonMain implementation
// is written under borg.trikeshed.net.http.

/** RFC 7230 request-line: METHOD SP request-target SP HTTP-version */
interface HttpRequestLineSpec {
    val method: String
    val requestTarget: String
    val httpVersion: String
}

/**
 * Salvage contract: parse an RFC 7230 request line.
 * Returns null when the line is malformed (fewer than 3 space-delimited parts).
 *
 * CONTRACT (intentionally unimplemented — this stub always fails):
 * - "GET /path HTTP/1.1"   → method=GET, target=/path, version=HTTP/1.1
 * - "POST /api HTTP/1.1"   → method=POST, target=/api, version=HTTP/1.1
 * - "HEAD / HTTP/1.0"      → method=HEAD, target=/, version=HTTP/1.0
 * - ""                     → null
 * - "BADLINE"              → null
 */
fun parseHttpRequestLine(line: String): HttpRequestLineSpec? = TODO("not implemented — add src/commonMain/kotlin/borg/trikeshed/net/http/HttpRequestLine.kt")

// ── Header salvage contract stub ──────────────────────────────────────────────

/**
 * Parse header lines (name: value) into a list of pairs.
 * Stops at blank line or end of list.
 *
 * CONTRACT (intentionally unimplemented):
 * - listOf("Content-Type: text/html", "Content-Length: 42") → [("Content-Type","text/html"), ("Content-Length","42")]
 * - Leading/trailing whitespace on value is trimmed.
 * - Lines without ':' are skipped.
 */
fun parseHttpHeaders(lines: List<String>): List<Pair<String, String>> = TODO("not implemented — add header parsing to src/commonMain/kotlin/borg/trikeshed/net/http/")

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
