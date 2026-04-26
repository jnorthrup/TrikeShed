package borg.trikeshed.couch.htx

import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTX Comprehensive Search & Transformation TDD Tests
 * RED phase: tests define desired algebra before implementation exists.
 *
 * HTX algebra:
 * - HtxBlock(addr, info) — HAProxy-compatible block metadata
 * - HtxBlockData — sealed union over block content types
 * - HtxMessage — ordered list of HtxBlockData with flags
 * - HtxStartLine — request or response start-line
 * - HtxBlockType — 8-type discriminated tag (ReqSl|ResSl|Hdr|Eoh|Data|Tlr|Eot|Unused)
 * - HtxFlags — message-level flags (EOM, FRAGMENTED, etc.)
 * - HtxSlFlags — start-line flags (IS_RESP, VER_11, SCHM_HTTP, etc.)
 *
 * SEARCH ALGEBRA:
 * - byBlockType(HtxMessage, HtxBlockType) → Sequence<HtxBlockData>
 * - startLine(HtxMessage) → HtxStartLine?
 * - headers(HtxMessage) → Sequence<Pair<ByteArray,ByteArray>>
 * - dataBlocks(HtxMessage) → Sequence<HtxBlockData.Data>
 * - trailers(HtxMessage) → Sequence<HtxBlockData.Trailer>
 * - findHeader(HtxMessage, ByteArray) → ByteArray?
 * - blockCount(HtxMessage) → Int
 * - hasFlag(HtxMessage, HtxFlags) → Boolean
 * - isRequest(HtxMessage) → Boolean
 * - isResponse(HtxMessage) → Boolean
 *
 * TRANSFORMATION ALGEBRA:
 * - HtxMessage.serialize() → ByteArray (NDJSON block stream)
 * - HtxMessage.toHttp1() → ByteArray (legacy HTTP/1.x wire format)
 * - ByteArray.parseHttp1() → HtxMessage?
 * - ByteArray.normalizeToHtx() → HtxMessage (auto-detect)
 * - HtxMessage.mergeTrailers() → HtxMessage (move trailers into headers)
 * - HtxMessage.stripBody() → HtxMessage (body-less copy for HEAD/204)
 *
 * CONSTRUCTION ALGEBRA:
 * - HtxMessage.request(method, uri, headers, body) → HtxMessage
 * - HtxMessage.response(status, reason, headers, body) → HtxMessage
 * - HtxMessage.withFlag(HtxFlags) → HtxMessage
 */
@Ignore
class HtxSearchRedTest {

    // === SEARCH ALGEBRA TESTS ===

    @Test
    fun `byBlockType finds all matching block types in message`() {
        // GIVEN an HTX message with multiple block types
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addHeader("Host".encodeToByteArray(), "example.com".encodeToByteArray())
            addHeader("Accept".encodeToByteArray(), "*/*".encodeToByteArray())
            addEndHeaders()
            addData("Hello".encodeToByteArray())
        }

        // WHEN searching by HtxBlockType.Hdr
        val headers = byBlockType(msg, HtxBlockType.Hdr).toList()

        // THEN all header blocks are found
        assertEquals(2, headers.size)
        assertTrue(headers.all { it is HtxBlockData.Header })
    }

    @Test
    fun `byBlockType returns empty sequence for absent block type`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
            // no trailers present
        }

        val trailers = byBlockType(msg, HtxBlockType.Tlr).toList()

        assertTrue(trailers.isEmpty())
    }

    @Test
    fun `startLine extracts request start-line`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Post, "/api/data".encodeToByteArray(), 2, 0))
            addEndHeaders()
        }

        val sl = startLine(msg)

        assertNotNull(sl)
        assertTrue(sl.isRequest)
        assertEquals(HttpMethod.Post, sl.method)
        assertEquals("POST", sl.method!!.name)
        assertEquals("2.0".encodeToByteArray().toList(), sl.version.toString().encodeToByteArray().toList())
    }

    @Test
    fun `startLine extracts response start-line`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addEndHeaders()
        }

        val sl = startLine(msg)

        assertNotNull(sl)
        assertFalse(sl.isRequest)
        assertEquals(200, sl.status)
    }

    @Test
    fun `startLine returns null for empty message`() {
        val msg = HtxMessage()

        assertNull(startLine(msg))
    }

    @Test
    fun `headers extracts all header name-value pairs`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addHeader("Host".encodeToByteArray(), "example.com".encodeToByteArray())
            addHeader("X-Request-Id".encodeToByteArray(), "abc123".encodeToByteArray())
            addEndHeaders()
        }

        val hdrs = headers(msg).associate { it.first.decodeToString() to it.second.decodeToString() }

        assertEquals("example.com", hdrs["Host"])
        assertEquals("abc123", hdrs["X-Request-Id"])
    }

    @Test
    fun `dataBlocks extracts only data blocks in order`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
            addData("chunk1".encodeToByteArray())
            addData("chunk2".encodeToByteArray())
            addTrailer("Server".encodeToByteArray(), "nginx".encodeToByteArray())
            addData("chunk3".encodeToByteArray())
        }

        val dataSeq = dataBlocks(msg).toList()

        assertEquals(3, dataSeq.size)
        assertEquals("chunk1", dataSeq[0].bytes.decodeToString())
        assertEquals("chunk2", dataSeq[1].bytes.decodeToString())
        assertEquals("chunk3", dataSeq[2].bytes.decodeToString())
    }

    @Test
    fun `trailers extracts all trailer blocks`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Post, "/upload".encodeToByteArray()))
            addEndHeaders()
            addData("body".encodeToByteArray())
            addTrailer("Checksum".encodeToByteArray(), "sha256:abc".encodeToByteArray())
            addTrailer("Expires".encodeToByteArray(), "never".encodeToByteArray())
            addEndTrailers()
        }

        val trailers = trailers(msg).toList()

        assertEquals(2, trailers.size)
        assertEquals("Checksum", trailers[0].name.decodeToString())
        assertEquals("sha256:abc", trailers[0].value.decodeToString())
    }

    @Test
    fun `findHeader returns value for existing header case-insensitive`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addHeader("Content-Type".encodeToByteArray(), "application/json".encodeToByteArray())
            addEndHeaders()
        }

        val value = findHeader(msg, "content-type".encodeToByteArray())

        assertNotNull(value)
        assertEquals("application/json", value.decodeToString())
    }

    @Test
    fun `findHeader returns null for missing header`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
        }

        val value = findHeader(msg, "X-Custom".encodeToByteArray())

        assertNull(value)
    }

    @Test
    fun `blockCount returns total number of blocks`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addHeader("Host".encodeToByteArray(), "x.com".encodeToByteArray())
            addEndHeaders()
            addData("body".encodeToByteArray())
            addTrailer("X-Trailer".encodeToByteArray(), "val".encodeToByteArray())
            addEndTrailers()
        }

        assertEquals(5, blockCount(msg))
    }

    @Test
    fun `hasFlag detects EOM flag`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
            setEom()
        }

        assertTrue(hasFlag(msg, HtxFlags.EOM))
        assertFalse(hasFlag(msg, HtxFlags.FRAGMENTED))
    }

    @Test
    fun `isRequest returns true for request messages`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
        }

        assertTrue(isRequest(msg))
        assertFalse(isResponse(msg))
    }

    @Test
    fun `isResponse returns true for response messages`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addEndHeaders()
        }

        assertFalse(isRequest(msg))
        assertTrue(isResponse(msg))
    }

    // === SERIALIZATION ALGEBRA TESTS ===

    @Test
    fun `serialize produces NDJSON block stream`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray(), 1, 1))
            addHeader("Host".encodeToByteArray(), "example.com".encodeToByteArray())
            addEndHeaders()
            addData("Hello World".encodeToByteArray())
        }

        val bytes = msg.serialize()

        assertTrue(bytes.isNotEmpty())
        // NDJSON means each line is a JSON object
        val lines = bytes.decodeToString().lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 3)
    }

    @Test
    fun `deserialize restores message from NDJSON`() {
        val original = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Post, "/api".encodeToByteArray()))
            addHeader("Content-Type".encodeToByteArray(), "application/json".encodeToByteArray())
            addEndHeaders()
            addData("""{"key":"value"}""".encodeToByteArray())
        }

        val bytes = original.serialize()
        val restored = HtxMessage.deserialize(bytes)

        assertNotNull(restored)
        assertEquals(blockCount(original), blockCount(restored))
        assertEquals(original.startLine()?.method, restored.startLine()?.method)
    }

    @Test
    fun `toHttp1 produces valid HTTP-1-x wire format`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray(), 1, 1))
            addHeader("Host".encodeToByteArray(), "example.com".encodeToByteArray())
            addEndHeaders()
        }

        val wire = msg.toHttp1()

        val text = wire.decodeToString()
        assertTrue(text.startsWith("GET / HTTP/1.1"))
        assertTrue(text.contains("Host: example.com"))
        assertTrue(text.contains("\r\n\r\n"))
    }

    @Test
    fun `toHttp1 response produces valid status line`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.response(404, "Not Found".encodeToByteArray(), 1, 1))
            addHeader("Content-Type".encodeToByteArray(), "text/plain".encodeToByteArray())
            addEndHeaders()
        }

        val wire = msg.toHttp1()

        val text = wire.decodeToString()
        assertTrue(text.startsWith("HTTP/1.1 404 Not Found"))
    }

    @Test
    fun `toHttp1 with body includes Content-Length when known`() {
        val body = "Hello".encodeToByteArray()
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Post, "/".encodeToByteArray(), 1, 1))
            addHeader("Host".encodeToByteArray(), "x.com".encodeToByteArray())
            addEndHeaders()
            addData(body)
        }

        val wire = msg.toHttp1()

        val text = wire.decodeToString()
        assertTrue(text.contains("Content-Length: 5"))
    }

    @Test
    fun `parseHttp1 round-trips through toHttp1`() {
        val http1Text = "GET /api/users?id=42 HTTP/1.1\r\nHost: example.com\r\nAccept: application/json\r\n\r\n"

        val msg = parseHttp1(http1Text.encodeToByteArray())

        assertNotNull(msg)
        assertTrue(isRequest(msg))
        assertEquals(HttpMethod.Get, msg.startLine()?.method)
        assertEquals("/api/users?id=42", msg.startLine()?.uri?.decodeToString())

        val wire = msg.toHttp1()
        assertTrue(wire.decodeToString().contains("GET /api/users"))
    }

    @Test
    fun `parseHttp1 returns null for invalid HTTP`() {
        assertNull(parseHttp1("NOT HTTP AT ALL".encodeToByteArray()))
        assertNull(parseHttp1("GET / HTTP".encodeToByteArray()))
        assertNull(parseHttp1("".encodeToByteArray()))
    }

    @Test
    fun `normalizeToHtx auto-detects HTTP-1-x`() {
        val input = "POST /api HTTP/1.1\r\nHost: x.com\r\n\r\nbody".encodeToByteArray()

        val msg = normalizeToHtx(input)

        assertNotNull(msg)
        assertTrue(isRequest(msg))
    }

    @Test
    fun `normalizeToHtx handles HTTP-2 preface`() {
        val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()

        val msg = normalizeToHtx(preface)

        // Should detect HTTP/2 and return appropriate representation
        assertNotNull(msg)
    }

    @Test
    fun `normalizeToHtx returns empty message for unknown protocol`() {
        val unknown = "SOME UNKNOWN PROTOCOL\r\n\u0000\u0001\u0002".encodeToByteArray()

        val msg = normalizeToHtx(unknown)

        assertNotNull(msg)
        assertTrue(msg.isEmpty())
    }

    // === TRANSFORMATION ALGEBRA TESTS ===

    @Test
    fun `mergeTrailers moves trailers into headers section`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addHeader("Server".encodeToByteArray(), "nginx".encodeToByteArray())
            addEndHeaders()
            addData("body".encodeToByteArray())
            addTrailer("X-Custom-Trailer".encodeToByteArray(), "trailer-value".encodeToByteArray())
            addEndTrailers()
        }

        val merged = msg.mergeTrailers()

        // Original message unchanged
        assertEquals(1, trailers(msg).count())
        // Merged message has trailer promoted to header
        val mergedHeaders = headers(merged).toList()
        assertTrue(mergedHeaders.any { it.first.decodeToString() == "X-Custom-Trailer" })
        // And trailer is removed
        assertEquals(0, trailers(merged).count())
    }

    @Test
    fun `stripBody removes data blocks for body-less responses`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.response(204, "No Content".encodeToByteArray()))
            addEndHeaders()
            addData("should be removed".encodeToByteArray())
        }

        val stripped = msg.stripBody()

        assertEquals(0, dataBlocks(stripped).count())
        assertTrue(hasFlag(stripped, HtxFlags.EOM))
    }

    @Test
    fun `stripBody preserves headers and start-line`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Head, "/".encodeToByteArray()))
            addHeader("Accept".encodeToByteArray(), "*/*".encodeToByteArray())
            addEndHeaders()
        }

        val stripped = msg.stripBody()

        assertNotNull(startLine(stripped))
        assertEquals(HttpMethod.Head, stripped.startLine()?.method)
        assertEquals(1, headers(stripped).count())
    }

    // === CONSTRUCTION ALGEBRA TESTS ===

    @Test
    fun `HtxMessage-request factory creates valid request`() {
        val msg = request(HttpMethod.Get, "/api/users".encodeToByteArray()) {
            header("Authorization".encodeToByteArray(), "Bearer token".encodeToByteArray())
        }

        assertTrue(isRequest(msg))
        assertEquals(HttpMethod.Get, msg.startLine()?.method)
        assertEquals("/api/users", msg.startLine()?.uri?.decodeToString())
        assertEquals("Bearer token", findHeader(msg, "authorization".encodeToByteArray())?.decodeToString())
    }

    @Test
    fun `HtxMessage-response factory creates valid response`() {
        val msg = response(201, "Created".encodeToByteArray()) {
            header("Location".encodeToByteArray(), "/api/users/42".encodeToByteArray())
        }

        assertTrue(isResponse(msg))
        assertEquals(201, msg.startLine()?.status)
        assertEquals("Created", msg.startLine()?.reason?.decodeToString())
    }

    @Test
    fun `withFlag returns copy with new flag set`() {
        val original = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/".encodeToByteArray()))
            addEndHeaders()
        }

        val withEom = original.withFlag(HtxFlags.EOM)

        // Original unchanged
        assertFalse(hasFlag(original, HtxFlags.EOM))
        // Copy has flag
        assertTrue(hasFlag(withEom, HtxFlags.EOM))
    }

    // === EDGE CASES ===

    @Test
    fun `empty message has zero blockCount`() {
        val msg = HtxMessage()

        assertEquals(0, blockCount(msg))
        assertTrue(msg.isEmpty())
    }

    @Test
    fun `message with only EndHeaders has blockCount 1`() {
        val msg = HtxMessage().apply {
            addEndHeaders()
        }

        assertEquals(1, blockCount(msg))
    }

    @Test
    fun `byBlockType on empty message returns empty sequence`() {
        val msg = HtxMessage()

        assertTrue(byBlockType(msg, HtxBlockType.Data).toList().isEmpty())
    }

    @Test
    fun `startLine on message with only headers returns null`() {
        val msg = HtxMessage().apply {
            addHeader("Foo".encodeToByteArray(), "bar".encodeToByteArray())
            addEndHeaders()
        }

        assertNull(startLine(msg))
    }

    @Test
    fun `multiple start-lines only returns first`() {
        val msg = HtxMessage().apply {
            addStartLine(HtxStartLine.request(HttpMethod.Get, "/first".encodeToByteArray()))
            addStartLine(HtxStartLine.request(HttpMethod.Post, "/second".encodeToByteArray()))
            addEndHeaders()
        }

        val sl = startLine(msg)

        assertEquals("/first", sl?.uri?.decodeToString())
    }
}
