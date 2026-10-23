package borg.trikeshed.skinnerbox

import kotlin.test.*

class MeshHttpTest {
    private fun parse(header: String, limits: MeshListenerLimits = MeshListenerLimits()) = MeshHttp.parseHead(header.encodeToByteArray(), limits)

    @Test
    fun validHeaderCapturesOnlyTheDeclaredBoundedBody() {
        val head = parse("POST /v1/skinnerbox/exchange HTTP/1.1\r\nHost: localhost\r\nContent-Length: 128\r\nContent-Type: application/cbor\r\n\r\n")
        assertEquals(128, head.length)
        assertEquals("POST", head.method)
        assertEquals("application/cbor", head.headers["content-type"])
        assertEquals(0, parse("GET /rest/v1/rows HTTP/1.1\r\nHost: localhost\r\n\r\n").length)
    }

    @Test
    fun ambiguousLengthsTransferCodingAndFoldedHeadersRefuse() {
        val prefixes = listOf(
            "Content-Length: 1\r\nContent-Length: 1", "Content-Length: 1\r\ncontent-length: 2",
            "Content-Length: +1", "Content-Length: -1", "Content-Length: 1,1",
            "Transfer-Encoding: chunked", "Transfer-Encoding: identity\r\nContent-Length: 1",
            " Content-Length: 1", "Content-Length : 1", "Content-Length: 1\r\n\tcontinued",
            "Content-Length: 1\r\nX-Bad: \u001f", "Content-Length: 1\r\nHost: other",
        )
        for (header in prefixes) assertFailsWith<MeshHttpException>(header) {
            parse("POST /v1/skinnerbox/exchange HTTP/1.1\r\nHost: localhost\r\n$header\r\n\r\n")
        }
    }

    @Test
    fun declaredOversizeFailsBeforeAnyBodyIsRequired() {
        val tooLarge = assertFailsWith<MeshHttpException> {
            parse("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2147483647\r\n\r\n")
        }
        assertEquals(413, tooLarge.status)
        assertEquals(411, assertFailsWith<MeshHttpException> { parse("POST / HTTP/1.1\r\nHost: localhost\r\n\r\n") }.status)
        assertEquals(417, assertFailsWith<MeshHttpException> {
            parse("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nExpect: 100-continue\r\n\r\n")
        }.status)
    }

    @Test
    fun absoluteTargetsMissingHostAndHeaderOverflowRefuse() {
        for (line in listOf("GET http://other/ HTTP/1.1", "GET //other/ HTTP/1.1", "GET / HTTP/1.0", "GET /#fragment HTTP/1.1"))
            assertFailsWith<MeshHttpException> { parse("$line\r\nHost: localhost\r\n\r\n") }
        assertFailsWith<MeshHttpException> { parse("GET / HTTP/1.1\r\n\r\n") }
        assertEquals(431, assertFailsWith<MeshHttpException> {
            parse("GET / HTTP/1.1\r\nHost: localhost\r\nX: ${"a".repeat(8192)}\r\n\r\n")
        }.status)
    }
}
