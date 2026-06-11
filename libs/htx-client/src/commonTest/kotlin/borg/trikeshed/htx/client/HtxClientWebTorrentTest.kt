package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import borg.trikeshed.htx.client.spi.NetworkConnection
import borg.trikeshed.htx.client.spi.NetworkTransportSpi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay

class HtxClientWebTorrentTest {

    @Test
    fun testMultiChunkStreamingFlow() = runTest {
        val transport = object : NetworkTransportSpi {
            override suspend fun connect(host: String, port: Int): NetworkConnection {
                return object : NetworkConnection {
                    var rangeStart = 0L
                    var rangeEnd = 0L
                    override suspend fun write(data: ByteArray) {
                        val req = data.decodeToString()
                        val rangeHeader = req.lines().find { it.startsWith("Range: bytes=") }
                        if (rangeHeader != null) {
                            val range = rangeHeader.substringAfter("Range: bytes=").split("-")
                            rangeStart = range[0].toLongOrNull() ?: 0L
                            rangeEnd = range[1].toLongOrNull() ?: 0L
                        }
                    }

                    override fun read(): Flow<ByteArray> = flowOf(
                        "HTTP/1.1 206 Partial Content\r\n\r\n".encodeToByteArray(),
                        "Chunk(${rangeStart}".encodeToByteArray(),
                        "-${rangeEnd})".encodeToByteArray()
                    )

                    override suspend fun close() {}
                }
            }
        }

        val client = HtxClient(transport)

        // Emulate WebTorrent streaming
        val stream = client.streamRange("http://example.com/movie.mp4", 1000, 1999)
        val chunks = stream.toList().map { it.decodeToString() }

        assertEquals(2, chunks.size)
        assertEquals("Chunk(1000", chunks[0])
        assertEquals("-1999)", chunks[1])
    }
}
