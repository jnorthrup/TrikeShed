/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.grpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrpcClientTest {

    private fun encodeFrame(payload: String): ByteArray {
        val bytes = payload.encodeToByteArray()
        val len = bytes.size
        val header = ByteArray(5)
        header[0] = 0
        header[1] = (len ushr 24).toByte()
        header[2] = (len ushr 16).toByte()
        header[3] = (len ushr 8).toByte()
        header[4] = len.toByte()
        return header + bytes
    }

    private fun mockServer(input: Flow<ByteArray>): Flow<ByteArray> = flow {
        // Echo server handling framing for the test mock.
        input.collect { bytes ->
            val str = bytes.decodeToString()
            if (str.contains("cancel")) {
                throw CancellationException("Stream cancelled by client request")
            }
            emit(bytes)
        }
    }

    @Test
    fun testBidirectionalStreamingMode_success() = runTest {
        val client = GrpcClient(::mockServer)
        val inputFlow = flowOf("msg1", "msg2").map { it.encodeToByteArray() }
        val output = client.bidirectionalStreamingMode(inputFlow).map { it.decodeToString() }.toList()
        assertEquals(listOf("msg1", "msg2"), output)
    }

    @Test
    fun testBidirectionalStreamingMode_cancellation() = runTest {
        val client = GrpcClient(::mockServer)
        val inputFlow = flow {
            emit("msg1".encodeToByteArray())
            emit("cancel".encodeToByteArray())
            emit("msg2".encodeToByteArray())
        }
        assertFailsWith<CancellationException> {
            client.bidirectionalStreamingMode(inputFlow).collect()
        }
    }

    @Test
    fun testBidirectionalStreamingMode_partialDeliveryAndClose() = runTest {
        // A server that sends back frames in fragmented chunks
        val fragmentedTransport: (Flow<ByteArray>) -> Flow<ByteArray> = { _ ->
            flow {
                val frame1 = encodeFrame("part1")
                val frame2 = encodeFrame("part2")
                val combined = frame1 + frame2

                // Emit byte by byte to heavily fragment
                for (byte in combined) {
                    emit(byteArrayOf(byte))
                }
            }
        }

        val client = GrpcClient(fragmentedTransport)
        val inputFlow = emptyFlow<ByteArray>()

        val results = mutableListOf<String>()
        client.bidirectionalStreamingMode(inputFlow).collect {
            results.add(it.decodeToString())
        }

        assertEquals(listOf("part1", "part2"), results)
    }
}
