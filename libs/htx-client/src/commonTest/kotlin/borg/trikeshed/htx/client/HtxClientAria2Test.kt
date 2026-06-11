package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import borg.trikeshed.htx.client.spi.NetworkConnection
import borg.trikeshed.htx.client.spi.NetworkTransportSpi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay

class HtxClientAria2Test {

    @Test
    fun testParallelChunkFetching() = runTest {
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
                        "HTTP/1.1 206 Partial Content\r\n\r\nChunk(${rangeStart}-${rangeEnd})".encodeToByteArray()
                    )

                    override suspend fun close() {}
                }
            }
        }

        val client = HtxClient(transport)

        // Emulate Aria2 multi-chunk feature: Request 2 chunks concurrently
        val deferredChunks = listOf(
            async { client.getRange("http://example.com/file", 0, 99) },
            async { client.getRange("http://example.com/file", 100, 199) }
        )

        val chunks = deferredChunks.awaitAll()

        assertEquals("Chunk(0-99)", chunks[0].body)
        assertEquals("Chunk(100-199)", chunks[1].body)
    }
}
