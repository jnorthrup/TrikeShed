package borg.literbike.ccek.agent8888

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * I/O Utilities for CCEK Protocol Handling
 *
 * This module provides production-ready connection handling and stream management.
 */

/**
 * PrefixedStream wraps a Socket with a buffer of already-read data.
 * This is essential for protocol detection where we need to peek at initial bytes
 * before deciding how to handle the connection.
 */
class PrefixedStream(
    private val inner: Socket,
    prefix: ByteArray,
    private var prefixOffset: Int = 0
) {
    private var prefix: ByteArray = prefix.copyOf()

    constructor(inner: Socket, prefix: ByteArray) : this(inner, prefix, 0)

    /**
     * Create a new PrefixedStream with the given prefix buffer.
     */
    companion object {
        fun new(inner: Socket, prefix: ByteArray): PrefixedStream =
            PrefixedStream(inner, prefix)
    }

    /**
     * Get the underlying TCP socket (for advanced operations)
     */
    fun intoInner(): Socket = inner

    /**
     * Get the total bytes available in the prefix buffer
     */
    fun availablePrefixBytes(): Int = prefix.size - prefixOffset

    /**
     * Check if prefix has been fully consumed
     */
    fun prefixConsumed(): Boolean = prefixOffset >= prefix.size

    /**
     * Read from the prefixed stream. Serves prefix bytes first, then delegates to socket.
     */
    fun read(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        var totalRead = 0

        // First, serve from prefix buffer
        if (prefixOffset < prefix.size) {
            val available = prefix.size - prefixOffset
            val toCopy = minOf(available, length - totalRead)
            prefix.copyInto(
                buf, offset + totalRead,
                prefixOffset, prefixOffset + toCopy
            )
            prefixOffset += toCopy
            totalRead += toCopy
        }

        // If we still need more data, read from underlying socket
        if (totalRead < length) {
            val inputStream = inner.getInputStream()
            val remaining = length - totalRead
            val n = inputStream.read(buf, offset + totalRead, remaining)
            if (n > 0) {
                totalRead += n
            } else if (n == -1 && totalRead == 0) {
                return -1 // EOF
            }
        }

        return if (totalRead == 0) -1 else totalRead
    }

    /**
     * Write to the underlying socket
     */
    fun write(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset) {
        inner.getOutputStream().write(buf, offset, length)
    }

    fun flush() {
        inner.getOutputStream().flush()
    }

    fun getInputStream(): InputStream = inner.getInputStream()
    fun getOutputStream(): OutputStream = inner.getOutputStream()
    fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress
    fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress
}

/**
 * Connection represents an accepted client connection with protocol detection state
 */
class Connection private constructor(
    var stream: PrefixedStream,
    val peerAddr: SocketAddress?,
    val localAddr: SocketAddress?,
    var detectionBuffer: ByteArray,
    var maxDetectionBytes: Int
) {
    /**
     * Create a new connection from a TCP socket
     */
    constructor(socket: Socket) : this(
        stream = PrefixedStream(socket, ByteArray(0)),
        peerAddr = socket.remoteSocketAddress,
        localAddr = socket.localSocketAddress,
        detectionBuffer = ByteArray(0),
        maxDetectionBytes = 8192
    )

    /**
     * Read initial bytes for protocol detection.
     * Note: This reads from the socket and stores the bytes,
     * then wraps the stream with the detection buffer.
     */
    fun detectBytes(): ByteArray {
        val tempBuf = ByteArray(1024)
        val detectionBuf = mutableListOf<Byte>()

        while (detectionBuf.size < maxDetectionBytes) {
            val n = stream.read(tempBuf)
            if (n <= 0) break
            for (i in 0 until n) {
                detectionBuf.add(tempBuf[i])
            }
        }

        detectionBuffer = detectionBuf.toByteArray()

        // Re-wrap the stream with the detection buffer as prefix
        val innerSocket = stream.intoInner()
        stream = PrefixedStream(innerSocket, detectionBuffer)

        return detectionBuffer
    }

    /**
     * Set maximum bytes to read for detection
     */
    fun withMaxDetectionBytes(max: Int): Connection {
        this.maxDetectionBytes = max
        return this
    }
}

/**
 * ConnectionPool manages a pool of active connections for the reactor
 */
class ConnectionPool(
    val maxConnections: Int
) {
    private val connections = mutableListOf<Connection>()

    companion object {
        fun new(maxConnections: Int): ConnectionPool = ConnectionPool(maxConnections)
    }

    /**
     * Add a connection to the pool
     */
    fun add(conn: Connection): Result<Unit> {
        return if (connections.size < maxConnections) {
            connections.add(conn)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Connection pool is full"))
        }
    }

    /**
     * Remove a connection from the pool
     */
    fun remove(index: Int): Connection? {
        return if (index in connections.indices) {
            connections.removeAt(index)
        } else {
            null
        }
    }

    /**
     * Get the number of active connections
     */
    fun size(): Int = connections.size

    /**
     * Check if pool is empty
     */
    fun isEmpty(): Boolean = connections.isEmpty()

    /**
     * Get a reference to a connection
     */
    fun get(index: Int): Connection? = connections.getOrNull(index)

    /**
     * Get a mutable reference to a connection
     */
    fun getMutable(index: Int): Connection? = connections.getOrNull(index)

    /**
     * Iterate over all connections
     */
    fun forEach(action: (Connection) -> Unit) {
        connections.forEach(action)
    }

    /**
     * Clear all connections
     */
    fun clear() {
        connections.clear()
    }
}

/**
 * I/O statistics for monitoring
 */
data class IoStats(
    var bytesRead: Long = 0,
    var bytesWritten: Long = 0,
    var connectionsAccepted: Long = 0,
    var connectionsClosed: Long = 0,
    var errors: Long = 0
) {
    companion object {
        fun new() = IoStats()
    }

    fun recordRead(bytes: Int) {
        bytesRead += bytes
    }

    fun recordWrite(bytes: Int) {
        bytesWritten += bytes
    }

    fun recordAccept() {
        connectionsAccepted++
    }

    fun recordClose() {
        connectionsClosed++
    }

    fun recordError() {
        errors++
    }

    fun activeConnections(): Long = (connectionsAccepted - connectionsClosed).coerceAtLeast(0)
}
