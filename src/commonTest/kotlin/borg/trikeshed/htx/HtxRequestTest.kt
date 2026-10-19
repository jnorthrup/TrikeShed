package borg.trikeshed.htx

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HtxRequestTest {
    @Test
    fun parsesContentLengthResponseIntoRootResponseShape() {
        val response = parseHtxResponse(
            ByteSeries(
                "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: text/plain\r\n\r\nhello",
            ),
        )

        assertEquals(200, response.status)
        assertEquals("hello", response.body.asString())
        assertEquals("5", response.headers.toList().first { it.a == "Content-Length" }.b)
    }

    @Test
    fun parsesChunkedResponseBody() {
        val response = parseHtxResponse(
            ByteSeries(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
            ),
        )

        assertEquals(200, response.status)
        assertEquals("hello world", response.body.asString())
    }

    @Test
    fun rejectsIncompleteOrMalformedChunkFramingInsteadOfReturningWireBytes() {
        for (body in listOf("10\r\n", "1\r\nx\r\n0\r\n", "1\r\nx!\r\n0\r\n\r\n", "z\r\n")) {
            assertFailsWith<IllegalArgumentException> {
                parseHtxResponse(ByteSeries("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$body"))
            }
        }
    }

    @Test
    fun transportDefaultsAddIdentityEncodingWithoutDroppingExistingHeaders() {
        val request = parseHtxRequest("https://example.com/api").copy(
            headers = htxHeaders("User-Agent" j "TrikeShed/1.0"),
        )

        val normalized = request.withTransportDefaults()

        assertEquals("TrikeShed/1.0", normalized.headerValue("User-Agent"))
        assertEquals("identity", normalized.headerValue("Accept-Encoding"))
    }

    @Test
    fun renderWireRequestDeclaresLengthForNonEmptyBody() {
        val request = parseHtxRequest(
            url = "https://jules.googleapis.com/v1alpha/sessions",
            method = HtxMethod.POST,
            body = ByteSeries("{}"),
        )

        val wire = request.renderWireRequest()

        assertEquals("2", wire.lineSequence()
            .first { it.startsWith("Content-Length: ") }
            .substringAfter(": "))
    }
}
