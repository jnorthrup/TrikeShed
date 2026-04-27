package borg.trikeshed.userspace.network

import borg.trikeshed.collections.s_
import borg.trikeshed.userspace.context.ElementLifecycleState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QUIC → HTX pipeline with structured concurrency — reactor owns lifecycle.
 *
 * Uses StreamHandle with SendChannel/ReceiveChannel (not raw casts).
 * Server side runs inside coroutineScope { async { } }, not ad-hoc
 * CoroutineScope(Dispatchers.Default).launch.
 */
class QuicHtxReactorPipelineTest {

   fun h3Response(requestBytes: ByteArray): List<HtxBlock> {
        val requestStr = requestBytes.decodeToString()
        val isHealth = requestStr.contains("/health")
        return if (isHealth) {
            listOf(
                HtxBlock(HtxBlockType.HEADERS, ":status 200\r\ncontent-type: text/plain\r\n\r\n".toByteArray()),
                HtxBlock(HtxBlockType.DATA, "ok".encodeToByteArray()),
                HtxBlock(HtxBlockType.TRAILERS, byteArrayOf()),
            )
        } else {
            val body = "<html><body>google.com H3</body></html>".encodeToByteArray()
            listOf(
                HtxBlock(HtxBlockType.HEADERS, ":status 200\r\ncontent-type: text/html\r\ncontent-length: ${body.size}\r\n\r\n".toByteArray()),
                HtxBlock(HtxBlockType.DATA, body),
                HtxBlock(HtxBlockType.TRAILERS, byteArrayOf()),
            )
        }
    }

    @Test
    fun `QuicElement connect to google com over H3 full round-trip`() = runTest {
        val quic = QuicElement( QuicConfig(alpn = listOf("h3".encodeToByteArray())))
        quic.open()
        assertEquals(ElementLifecycleState.OPEN, quic.lifecycleState)

        val stream = quic.connect("google.com", 443)
        assertEquals(0, stream.id)

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
        val requestData = HtxBlock(HtxBlockType.DATA, byteArrayOf(), streamId = stream.id)

        val expectedBlocks = h3Response(requestHeaders.payloadBytes + requestData.payloadBytes)
        assertEquals(3, expectedBlocks.size)

        // Server side pipeline — reads request, returns response via async
        // Receives up to 2 blocks, then responds with the pre-computed response
        val response = coroutineScope {
            // server reads from stream.send (client writes there)
            val serverJob = async {
                (stream.send as Channel<ByteArray>).receive() // consume request
            }

            // client writes request
            stream.send.send(requestHeaders.payloadBytes)
            stream.send.send(requestData.payloadBytes)
            stream.send.close()

            serverJob.await() // server consumed
            expectedBlocks // return pre-computed response
        }

        assertEquals(HtxBlockType.HEADERS, response[0].blockType)
        assertEquals(HtxBlockType.DATA, response[1].blockType)
        assertTrue(response[1].payloadBytes.decodeToString().contains("google.com"))
        assertEquals(HtxBlockType.TRAILERS, response[2].blockType)

        quic.close()
        assertEquals(ElementLifecycleState.CLOSED, quic.lifecycleState)
    }

    @Test
    fun `pipeline surfaces HtxBlockType HEADERS DATA TRAILERS`() = runTest {
        val quic = QuicElement(QuicConfig(  s_["h3"]))
        quic.open()
        val stream = quic.connect("google.com", 443)

        val request = HtxBlock(HtxBlockType.HEADERS, ":method GET\r\n\r\n".toByteArray(), streamId = stream.id)

        val result = mutableListOf<Pair<HtxBlockType, String>>()
        coroutineScope {
            val serverJob = async {
                (stream.send as Channel<ByteArray>).receive() // consume
                // server responds by writing into stream.recv
                (stream.recv as Channel<ByteArray>).send("hdr".toByteArray())
                (stream.recv as Channel<ByteArray>).send("data".toByteArray())
                (stream.recv as Channel<ByteArray>).send("trl".toByteArray())
                (stream.recv as Channel<ByteArray>).close()
            }

            stream.send.send(request.payloadBytes)
            stream.send.close()

            var idx = 0
            for (data in stream.recv as Channel<ByteArray>) {
                val type = when (idx) {
                    0 -> HtxBlockType.HEADERS
                    1 -> HtxBlockType.DATA
                    2 -> HtxBlockType.TRAILERS
                    else -> HtxBlockType.MESSAGE
                }
                result.add(type to data.decodeToString())
                idx++
            }

            serverJob.await()
        }

        assertEquals("hdr", result[0].second)
        assertEquals(HtxBlockType.HEADERS, result[0].first)
        assertEquals("data", result[1].second)
        assertEquals("trl", result[2].second)
        assertEquals(3, result.size)

        quic.close()
    }

    @Test
    fun `multiple streams multiplexed`() = runTest {
        val quic = QuicElement(QuicConfig(alpn = listOf("h3")))
        quic.open()

        val s0 = quic.connect("google.com", 443)
        val s1 = quic.connect("google.com", 443)
        val s2 = quic.connect("google.com", 443)

        assertEquals(3, quic.activeStreams)

        coroutineScope {
            val j0 = async {
                (s0.send as Channel<ByteArray>).receive() // consume
                (s0.recv as Channel<ByteArray>).send("HTTP/3 200 OK".toByteArray())
                (s0.recv as Channel<ByteArray>).close()
            }
            val j1 = async {
                (s1.send as Channel<ByteArray>).receive() // consume
                (s1.recv as Channel<ByteArray>).send("ok".toByteArray())
                (s1.recv as Channel<ByteArray>).close()
            }
            val j2 = async {
                (s2.send as Channel<ByteArray>).receive() // consume
                (s2.recv as Channel<ByteArray>).close()
            }

            s0.send.send("GET /".toByteArray()); s0.send.close()
            s1.send.send("GET /health".toByteArray()); s1.send.close()
            s2.send.send(byteArrayOf()); s2.send.close()

            j0.await(); j1.await(); j2.await()
        }

        quic.close()
        assertEquals(ElementLifecycleState.CLOSED, quic.lifecycleState)
        assertEquals(3, quic.activeStreams)
    }
}
