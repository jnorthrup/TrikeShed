package borg.trikeshed.lib

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HttpMotdReaction(
    private val reactor: Reactor,
    private val keepAlive: Boolean = true
) : UnaryAsyncReaction {
    private val motd = "Welcome to Trikeshed Server - ${LocalDateTime.now()}"
    private val httpDateFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
    
    override fun invoke(key: SelectionKey): Pair<Int, UnaryAsyncReaction>? {
        val channel = key.channel() as SocketChannel
        
        return when (key.readyOps()) {
            OP_READ -> handleRead(key, channel)
            OP_WRITE -> handleWrite(key, channel)
            else -> null
        }
    }

    private fun handleRead(key: SelectionKey, channel: SocketChannel): Pair<Int, UnaryAsyncReaction>? {
        val buffer = reactor.acquireBuffer()
        
        return try {
            val bytesRead = channel.read(buffer)
            if (bytesRead == -1) {
                channel.close()
                return null
            }

            if (isHttpGetComplete(buffer)) {
                // Prepare response buffer
                val response = buildHttpResponse()
                key.attach(WriteState(response))
                Pair(OP_WRITE, this)
            } else {
                // Need more data
                Pair(OP_READ, this)
            }
        } catch (e: Exception) {
            buffer.clear()
            channel.close()
            null
        }
    }

    private fun handleWrite(key: SelectionKey, channel: SocketChannel): Pair<Int, UnaryAsyncReaction>? {
        val writeState = key.attachment() as WriteState
        val buffer = writeState.buffer
        
        try {
            channel.write(buffer)
            
            return if (!buffer.hasRemaining()) {
                if (keepAlive) {
                    // Reset for next request
                    buffer.clear()
                    Pair(OP_READ, this)
                } else {
                    channel.close()
                    null
                }
            } else {
                // More to write
                Pair(OP_WRITE, this)
            }
        } catch (e: Exception) {
            channel.close()
            return null
        }
    }

    private fun buildHttpResponse(): ByteBuffer {
        val now = LocalDateTime.now()
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Date: ${httpDateFormatter.format(now)}\r\n")
            append("Server: Trikeshed/1.0\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${motd.length}\r\n")
            if (keepAlive) {
                append("Connection: keep-alive\r\n")
            } else {
                append("Connection: close\r\n")
            }
            append("\r\n")
            append(motd)
        }
        
        return ByteBuffer.wrap(response.toByteArray())
    }

    private class WriteState(val buffer: ByteBuffer)

    companion object {
        fun create(reactor: Reactor, keepAlive: Boolean = true): HttpMotdReaction {
            return HttpMotdReaction(reactor, keepAlive)
        }
    }
}
