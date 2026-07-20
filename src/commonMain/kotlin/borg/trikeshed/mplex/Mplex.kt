package borg.trikeshed.mplex

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

interface Stream {
    val id: Long
    suspend fun read(maxBytes: Int): ByteArray
    suspend fun readExactly(bytes: Int): ByteArray
    suspend fun write(data: ByteArray)
    suspend fun close()
}

open class FlowWindow(initialSize: Int = 65535) {
    private val availableState = MutableStateFlow(initialSize)

    val available: Int
        get() = availableState.value

    suspend fun consume(bytes: Int) {
        while (true) {
            // Suspend until we might have enough bytes available
            availableState.first { it >= bytes }

            // Try to atomically consume the bytes
            var success = false
            availableState.update { current ->
                if (current >= bytes) {
                    success = true
                    current - bytes
                } else {
                    success = false // Critical: reset success on retry failure
                    current
                }
            }
            if (success) {
                return
            }
        }
    }

    suspend fun update(bytes: Int) {
        availableState.update { it + bytes }
    }
}

class StreamWindow(initialSize: Int = 65535) : FlowWindow(initialSize)

class SessionWindow(initialSize: Int = 65535) : FlowWindow(initialSize)

class MplexStream(
    override val id: Long,
    private val sessionWindow: SessionWindow,
    initialWindowSize: Int = 65535,
    private val onWrite: suspend (Long, ByteArray) -> Unit = { _, _ -> }
) : Stream {
    val streamWindow = StreamWindow(initialWindowSize)

    // Applying receive side backpressure limit. Buffer size prevents immediate HOL blocking,
    // though a full implementation needs proper flow control feedback.
    private val readChannel = Channel<ByteArray>(64)
    private var closed = false
    private var leftover: ByteArray? = null

    override suspend fun read(maxBytes: Int): ByteArray {
        val data = leftover ?: try {
            readChannel.receive()
        } catch (e: ClosedReceiveChannelException) {
            return ByteArray(0)
        }

        return if (data.size <= maxBytes) {
            leftover = null
            data
        } else {
            val chunk = data.copyOfRange(0, maxBytes)
            leftover = data.copyOfRange(maxBytes, data.size)
            chunk
        }
    }

    override suspend fun readExactly(bytes: Int): ByteArray {
        val buffer = ByteArray(bytes)
        var offset = 0
        while (offset < bytes) {
            val chunk = read(bytes - offset)
            if (chunk.isEmpty()) {
                throw IllegalStateException("Stream closed before reading exactly $bytes bytes")
            }
            chunk.copyInto(buffer, offset)
            offset += chunk.size
        }
        return buffer
    }

    fun receiveData(data: ByteArray) {
        if (!closed && data.isNotEmpty()) {
            try {
                // Using trySend instead of send to avoid suspending the session reader loop,
                // mitigating Head-of-Line (HOL) blocking. If the channel is full, data is dropped
                // (in a real QUIC implementation, we would rely on QUIC's reliable delivery
                // and flow control to not exceed window size).
                readChannel.trySend(data)
            } catch (e: ClosedSendChannelException) {
                // Ignore if stream was closed concurrently
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        if (closed) throw IllegalStateException("Stream closed")
        sessionWindow.consume(data.size)
        streamWindow.consume(data.size)
        onWrite(id, data)
    }

    override suspend fun close() {
        closed = true
        readChannel.close()
    }
}
