@file:Suppress("UnsafeCastFromDynamic")
package borg.trikeshed.userspace.nio

import kotlinx.coroutines.CompletableDeferred
import kotlin.random.Random

private val nodeNet: dynamic = js("require('net')")
private val nodeBuffer: dynamic = js("globalThis.Buffer")

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    JsListeningSocket(host, if (port == 0) randomNodePort() else port)

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    JsConnectedSocket(nodeNet.createConnection(port, host), SocketAddress.Inet(host, port))

private fun randomNodePort(): Int = 20000 + Random.nextInt(30000)

private fun bufferToByteArray(buffer: dynamic): ByteArray {
    val size = (buffer.length as Number).toInt()
    return ByteArray(size) { index -> (buffer[index] as Number).toByte() }
}

private fun byteArrayToBuffer(bytes: ByteArray, offset: Int, len: Int): dynamic {
    val slice = bytes.copyOfRange(offset, offset + len)
    return nodeBuffer.from(slice)
}

private class JsConnectedSocket(
    private val socket: dynamic,
    override val remoteAddress: SocketAddress,
) : ConnectedSocket {
    private val queuedReads = mutableListOf<ByteArray>()
    private val waitingReads = mutableListOf<CompletableDeferred<ByteArray?>>()
    private var closed = false

    init {
        socket.on("data", { chunk: dynamic ->
            val bytes = bufferToByteArray(chunk)
            val waiter = waitingReads.removeFirstOrNull()
            if (waiter != null) waiter.complete(bytes) else queuedReads.add(bytes)
        })
        val closeHandler = {
            closed = true
            if (queuedReads.isEmpty()) {
                while (waitingReads.isNotEmpty()) waitingReads.removeAt(0).complete(null)
            }
        }
        socket.on("end", { _: dynamic -> closeHandler() })
        socket.on("close", { _: dynamic -> closeHandler() })
        socket.on("error", { _: dynamic -> closeHandler() })
    }

    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int {
        if (queuedReads.isNotEmpty()) return drainChunk(queuedReads.removeAt(0), buf, offset, len)
        if (closed) return -1
        val deferred = CompletableDeferred<ByteArray?>()
        waitingReads.add(deferred)
        val chunk = deferred.await() ?: return -1
        return drainChunk(chunk, buf, offset, len)
    }

    private fun drainChunk(chunk: ByteArray, buf: ByteArray, offset: Int, len: Int): Int {
        val n = minOf(len, chunk.size)
        chunk.copyInto(buf, offset, 0, n)
        if (n < chunk.size) queuedReads.add(0, chunk.copyOfRange(n, chunk.size))
        return n
    }

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int {
        if (closed) return -1
        val deferred = CompletableDeferred<Int>()
        try {
            socket.write(byteArrayToBuffer(buf, offset, len), { deferred.complete(len) })
        } catch (_: Throwable) {
            closed = true
            deferred.complete(-1)
        }
        return deferred.await()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        val deferred = CompletableDeferred<Unit>()
        try {
            socket.end({ deferred.complete(Unit) })
        } catch (_: Throwable) {
            deferred.complete(Unit)
        }
        while (waitingReads.isNotEmpty()) waitingReads.removeAt(0).complete(null)
        deferred.await()
    }
}

private class JsListeningSocket(
    private val host: String,
    private val port: Int,
) : ListeningSocket {
    private val server: dynamic = nodeNet.createServer()
    private val queuedConnections = mutableListOf<ConnectedSocket>()
    private val waitingAccepts = mutableListOf<CompletableDeferred<ConnectedSocket?>>()
    private var closed = false

    init {
        server.on("connection", { socket: dynamic ->
            val conn = JsConnectedSocket(socket, remoteAddressOf(socket))
            val waiter = waitingAccepts.removeFirstOrNull()
            if (waiter != null) waiter.complete(conn) else queuedConnections.add(conn)
        })
        val closeHandler = {
            closed = true
            while (waitingAccepts.isNotEmpty()) waitingAccepts.removeAt(0).complete(null)
        }
        server.on("close", { _: dynamic -> closeHandler() })
        server.on("error", { _: dynamic -> closeHandler() })
        server.listen(port, host)
    }

    override val bindAddress: SocketAddress
        get() = SocketAddress.Inet(host, port)

    override suspend fun accept(): ConnectedSocket? {
        if (queuedConnections.isNotEmpty()) return queuedConnections.removeAt(0)
        if (closed) return null
        val deferred = CompletableDeferred<ConnectedSocket?>()
        waitingAccepts.add(deferred)
        return deferred.await()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        val deferred = CompletableDeferred<Unit>()
        try {
            server.close({ deferred.complete(Unit) })
        } catch (_: Throwable) {
            deferred.complete(Unit)
        }
        while (waitingAccepts.isNotEmpty()) waitingAccepts.removeAt(0).complete(null)
        deferred.await()
    }

    private fun remoteAddressOf(socket: dynamic): SocketAddress {
        val remoteHost = socket.remoteAddress as? String ?: host
        val remotePort = (socket.remotePort as? Number)?.toInt() ?: port
        return SocketAddress.Inet(remoteHost, remotePort)
    }
}
