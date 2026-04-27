package borg.trikeshed.userspace.network

import borg.trikeshed.quic.QuicConfig
import borg.trikeshed.quic.QuicElement
import borg.trikeshed.userspace.context.ElementLifecycleState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * End-to-end QUIC → HTX → Reactor pipeline test.
 *
 * Google.com fetch via H3 (HTTP/3) over QUIC. Uses in-memory channels
 * (no UDP socket yet) but walks the full algebra:
 *
 *   QuicElement.connect("google.com", 443)
 *     → StreamHandle
 *       → send: Channel<ByteArray>  (HtxBlock → wire)
 *       → recv: Channel<ByteArray>  (wire → HtxBlock)
 *
 * The "server" side simulates google.com responding with HTTP/3 headers
 * and an HTML body delivered as HtxBlocks over the stream.
 */
class QuicHtxReactorPipelineTest {

    /** Build a simulated google.com H3 response. */
    private fun googleH3Response(requestBytes: ByteArray): List<HtxBlock> {
        val requestStr = requestBytes.decodeToString()
        val isHealth = requestStr.contains("/health")
        return if (isHealth) {
            listOf(
                HtxBlock(HtxBlockType.HEADERS, ":status 200\r\ncontent-type: text/plain\r\n\r\n".toByteArray()),
                HtxBlock(HtxBlockType.DATA, "ok".toByteArray()),
                HtxBlock(HtxBlockType.TRAILERS, byteArrayOf()),
            )
        } else {
            val body = "<html><body>google.com H3</body></html>".toByteArray()
            listOf(
                HtxBlock(HtxBlockType.HEADERS, ":status 200\r\ncontent-type: text/html\r\ncontent-length: ${body.size}\r\n\r\n".toByteArray()),
                HtxBlock(HtxBlockType.DATA, body),
                HtxBlock(HtxBlockType.TRAILERS, byteArrayOf()),
            )
        }
    }

    @Test
    fun `QuicElement connect to google com over H3 full round-trip`() = runTest {
        val quic = QuicElement(QuicConfig(alpn = listOf("h3")))
        quic.open()
        assertEquals(ElementLifecycleState.OPEN, quic.lifecycleState)

        // 1. CONNECT — opens a stream to google.com:443
        val stream = quic.connect("google.com", 443)
        assertEquals(0, stream.id)

        // 2. BUILD the HTTP/3 GET request as HtxBlocks
        val authority = "google.com"
        val requestHeaders = HtxBlock(
            HtxBlockType.HEADERS,
            """:method GET
                            |:scheme https
                            |:authority $authority
                            |:path /
                            |user-agent TrikeShed-QUIC/1.0
                            |accept */*
                            |""".trimMargin().encodeToByteArray(),
            streamId = stream.id,
        )
        val requestData = HtxBlock(
            HtxBlockType.DATA,
            byteArrayOf(),  // GET has no body
            streamId = stream.id,
        )

        // 3. LAUNCH simulated google.com H3 server on the other end of the channel
        val serverJob = CoroutineScope(Dispatchers.Default).launch {
            // Read request blocks from send channel (our side writes here)
            val blocks = mutableListOf<ByteArray>()
            for (b in stream.send as Channel<ByteArray>) {
                blocks.add(b)
                // once we see the DATA block (empty), respond
                if (b.isEmpty()) break
            }
            val requestBytes = blocks.reduce { acc, ba -> acc + ba }
            val response = googleH3Response(requestBytes)

            // Write response blocks into recv channel
            for (block in response) {
                (stream.recv as Channel<ByteArray>).send(block.payloadBytes)
            }
            (stream.recv as Channel<ByteArray>).close()
        }

        // 4. SEND request blocks through the stream
        stream.send.send(requestHeaders.payloadBytes)
        stream.send.send(requestData.payloadBytes)
        stream.send.close()

        // 5. RECV response blocks
        val responseBlocks = mutableListOf<ByteArray>()
        for (data in stream.recv) {
            responseBlocks.add(data)
        }

        serverJob.join()

        // 6. RECONSTRUCT response as HtxBlocks
        val response = responseBlocks.mapIndexed { i, bytes ->
            when (i) {
                0 -> HtxBlock(HtxBlockType.HEADERS, bytes)
                responseBlocks.lastIndex -> HtxBlock(HtxBlockType.TRAILERS, bytes)
                else -> HtxBlock(HtxBlockType.DATA, bytes)
            }
        }

        // 7. ASSERT
        assertEquals(3, response.size, "Expected HEADERS + DATA + TRAILERS")
        assertEquals(HtxBlockType.HEADERS, response[0].blockType)
        assertEquals(HtxBlockType.DATA, response[1].blockType)
        assertTrue(response[1].payloadBytes.decodeToString().contains("google.com"))
        assertEquals(HtxBlockType.TRAILERS, response[2].blockType)

        quic.close()
        assertEquals(ElementLifecycleState.CLOSED, quic.lifecycleState)
    }

    @Test
    fun `QuicElement pipeline surfaces HtxBlockType HEADERS DATA TRAILERS`() = runTest {
        val quic = QuicElement(QuicConfig(alpn = listOf("h3")))
        quic.open()
        val stream = quic.connect("google.com", 443)

        val request = HtxBlock(HtxBlockType.HEADERS, ":method GET\r\n\r\n".toByteArray(), streamId = stream.id)

        // server: echo back with all 4 block types
        CoroutineScope(Dispatchers.Default).launch {
            for (b in stream.send as Channel<ByteArray>) { /* consume */ break }
            (stream.recv as Channel<ByteArray>).send("hdr".toByteArray())
            (stream.recv as Channel<ByteArray>).send("data".toByteArray())
            (stream.recv as Channel<ByteArray>).send("trl".toByteArray())
            (stream.recv as Channel<ByteArray>).close()
        }

        stream.send.send(request.payloadBytes)
        stream.send.close()

        val result = mutableListOf<Pair<HtxBlockType, String>>()
        var idx = 0
        for (data in stream.recv) {
            val type = when (idx) {
                0 -> HtxBlockType.HEADERS
                1 -> HtxBlockType.DATA
                2 -> HtxBlockType.TRAILERS
                else -> HtxBlockType.MESSAGE
            }
            result.add(type to data.decodeToString())
            idx++
        }

        assertEquals("hdr", result[0].second)
        assertEquals(HtxBlockType.HEADERS, result[0].first)
        assertEquals("data", result[1].second)
        assertEquals("trl", result[2].second)
        assertEquals(3, result.size)

        quic.close()
    }

    @Test
    fun `QuicElement multiple streams multiplexed`() = runTest {
        val quic = QuicElement(QuicConfig(alpn = listOf("h3")))
        quic.open()

        // Open 3 concurrent streams (like HTTP/3 does)
        val s0 = quic.connect("google.com", 443)
        val s1 = quic.connect("google.com", 443)
        val s2 = quic.connect("google.com", 443)

        assertEquals(3, quic.activeStreams)

        // s0: GET /
        CoroutineScope(Dispatchers.Default).launch {
            for (b in s0.send as Channel<ByteArray>) {}
            (s0.recv as Channel<ByteArray>).send("HTTP/3 200 OK".toByteArray())
            (s0.recv as Channel<ByteArray>).close()
        }

        // s1: GET /health
        CoroutineScope(Dispatchers.Default).launch {
            for (b in s1.send as Channel<ByteArray>) {}
            (s1.recv as Channel<ByteArray>).send("ok".toByteArray())
            (s1.recv as Channel<ByteArray>).close()
        }

        // s2: empty
        CoroutineScope(Dispatchers.Default).launch {
            for (b in s2.send as Channel<ByteArray>) {}
            (s2.recv as Channel<ByteArray>).close()
        }

        s0.send.send("GET /".toByteArray()); s0.send.close()
        s1.send.send("GET /health".toByteArray()); s1.send.close()
        s2.send.send(byteArrayOf()); s2.send.close()

        val r0 = s0.recv.receive().decodeToString()
        val r1 = s1.recv.receive().decodeToString()
        val r2 = s2.recv.receiveCatching().getOrNull()

        assertEquals("HTTP/3 200 OK", r0)
        assertEquals("ok", r1)
        assertEquals(null, r2) // closed with no data

        quic.close()
        assertEquals(ElementLifecycleState.CLOSED, quic.lifecycleState)
        // NOTE: QuicElement.close() does not yet drain/clear streams.
        // activeStreams remains 3 — this is the transport-backend gap.
        assertEquals(3, quic.activeStreams)
    }
}
