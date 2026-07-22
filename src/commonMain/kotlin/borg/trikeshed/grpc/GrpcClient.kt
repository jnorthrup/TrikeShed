/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.grpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * A GrpcClient supporting bidirectional streaming mode.
 * Simulates gRPC length-prefixed framing (1 byte compression flag + 4 bytes length).
 */
class GrpcClient(private val transport: (Flow<ByteArray>) -> Flow<ByteArray>) {

    class GrpcStreamClosedException(message: String) : Exception(message)

    fun bidirectionalStreamingMode(input: Flow<ByteArray>): Flow<ByteArray> = flow {
        // Frame the outgoing messages with a 5-byte header
        val framedInput = flow {
            input.collect { payload ->
                val len = payload.size
                val header = ByteArray(5)
                header[0] = 0 // Compression flag (uncompressed)
                header[1] = (len ushr 24).toByte()
                header[2] = (len ushr 16).toByte()
                header[3] = (len ushr 8).toByte()
                header[4] = len.toByte()

                emit(header + payload)
            }
        }.catch { e ->
            throw CancellationException("Client stream cancelled", e)
        }

        val rawResponseStream = transport(framedInput)

        // Deframer state
        var buffer = ByteArray(0)
        var isClosed = false

        rawResponseStream
            .catch { e ->
                if (e is CancellationException) throw e
                throw CancellationException("Transport error", e)
            }
            .onCompletion {
                isClosed = true
                if (buffer.isNotEmpty()) {
                    // Partial trailing data without a full frame is technically an error in gRPC
                    // but we just silently ignore it or could throw.
                }
            }
            .collect { chunk ->
                buffer += chunk

                while (buffer.size >= 5) {
                    val compressedFlag = buffer[0]
                    val len = ((buffer[1].toInt() and 0xFF) shl 24) or
                              ((buffer[2].toInt() and 0xFF) shl 16) or
                              ((buffer[3].toInt() and 0xFF) shl 8) or
                              (buffer[4].toInt() and 0xFF)

                    val frameSize = 5 + len
                    if (buffer.size >= frameSize) {
                        val payload = buffer.sliceArray(5 until frameSize)
                        emit(payload)
                        buffer = buffer.sliceArray(frameSize until buffer.size)
                    } else {
                        break // Need more data for this frame
                    }
                }
            }
    }
}
