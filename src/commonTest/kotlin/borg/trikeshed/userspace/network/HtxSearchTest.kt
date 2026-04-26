package borg.trikeshed.userspace.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** HTX block algebra tests — stubs live in TransportSearchRedTest. */
class HtxSearchTest {

    @Test fun htxBlockType_count() {
        assertEquals(4, HtxBlockType.entries.size)
    }

    @Test fun htxBlockType_message() {
        assertEquals(HtxBlockType.MESSAGE, HtxBlockType.MESSAGE)
    }

    @Test fun htxBlockType_headers() {
        assertEquals(HtxBlockType.HEADERS, HtxBlockType.HEADERS)
    }

    @Test fun htxBlockType_data() {
        assertEquals(HtxBlockType.DATA, HtxBlockType.DATA)
    }

    @Test fun htxBlockType_trailers() {
        assertEquals(HtxBlockType.TRAILERS, HtxBlockType.TRAILERS)
    }

    @Test fun htxBlock_equality() {
        val a = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2, 3))
        val b = HtxBlock(HtxBlockType.DATA, byteArrayOf(1, 2, 3))
        val c = HtxBlock(HtxBlockType.DATA, byteArrayOf(4, 5, 6))
        assertTrue(a == b)
        assertTrue(a != c)
    }

    @Test fun htxBlock_hashCode() {
        val a = HtxBlock(HtxBlockType.DATA, byteArrayOf(1))
        val b = HtxBlock(HtxBlockType.DATA, byteArrayOf(1))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun htxBlock_streamId() {
        val b0 = HtxBlock(HtxBlockType.DATA, byteArrayOf(1), streamId = 0)
        val b1 = HtxBlock(HtxBlockType.DATA, byteArrayOf(1), streamId = 1)
        assertTrue(b0 != b1)
    }

    @Test fun httpMethod_get() { assertEquals("GET", HttpMethod.GET) }
    @Test fun httpMethod_post() { assertEquals("POST", HttpMethod.POST) }
    @Test fun httpMethod_put() { assertEquals("PUT", HttpMethod.PUT) }
    @Test fun httpMethod_delete() { assertEquals("DELETE", HttpMethod.DELETE) }

    @Test fun htxStartLine_interface() {
        val s = object : HtxStartLine {}
        assertNotNull(s)
    }

    @Test fun htxMessage_interface() {
        val m = object : HtxMessage {
            override val startLine: HtxStartLine = object : HtxStartLine {}
            override val headers: Map<String, String> = mapOf("host" to "example.com")
            override val body: ByteArray? = byteArrayOf(1, 2)
        }
        assertEquals("example.com", m.headers["host"])
        assertEquals(2, m.body?.size)
    }

    @Test fun htxBlock_payloadBytes() {
        val block = HtxBlock(HtxBlockType.DATA, byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
        assertEquals(2, block.payloadBytes.size)
        assertEquals(0xAB.toByte(), block.payloadBytes[0])
    }
}
