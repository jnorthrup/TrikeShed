package borg.trikeshed.userspace.nio

import kotlinx.coroutines.CompletableDeferred

actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
    PosixSocketRegistry.openListener(host, port)

actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
    PosixSocketRegistry.connect(host, port)

private fun normalizeInetHost(host: String): String =
    if (host == "localhost") "127.0.0.1" else host

private object PosixSocketRegistry {
    private val listeners = mutableMapOf<Pair<String, Int>, PosixListeningSocket>()
    private var nextPort = 20000

    fun openListener(host: String, requestedPort: Int): PosixListeningSocket {
        val normalizedHost = normalizeInetHost(host)
        val port =
            if (requestedPort == 0) {
                while (listeners.containsKey(normalizedHost to nextPort)) nextPort += 1
                nextPort++
            } else {
                requestedPort
            }
        val bindAddress = SocketAddress.Inet(normalizedHost, port)
        val listener = PosixListeningSocket(bindAddress)
        listeners[normalizedHost to port] = listener
        return listener
    }

    fun connect(host: String, port: Int): PosixConnectedSocket {
        val normalizedHost = normalizeInetHost(host)
        val listener = listeners[normalizedHost to port]
            ?: error("No listening socket for $normalizedHost:$port")
        val clientIncoming = BytePipe()
        val serverIncoming = BytePipe()
        val client = PosixConnectedSocket(SocketAddress.Inet(normalizedHost, port), clientIncoming, serverIncoming)
        val server = PosixConnectedSocket(SocketAddress.Inet(normalizedHost, port), serverIncoming, clientIncoming)
        listener.enqueue(server)
        return client
    }

    fun closeListener(bindAddress: SocketAddress.Inet) {
        listeners.remove(bindAddress.host to bindAddress.port)
    }
}

internal class BytePipe {
    private val queuedReads = ArrayDeque<ByteArray>()
    private val waitingReads = mutableListOf<CompletableDeferred<ByteArray?>>()
    private var closed = false

    suspend fun read(buf: ByteArray, offset: Int, len: Int): Int {
        if (queuedReads.isNotEmpty()) return drainChunk(queuedReads.removeFirst(), buf, offset, len)
        if (closed) return -1
        val deferred = CompletableDeferred<ByteArray?>()
        waitingReads.add(deferred)
        val chunk = deferred.await() ?: return -1
        return drainChunk(chunk, buf, offset, len)
    }

    fun write(buf: ByteArray, offset: Int, len: Int): Int {
        if (closed) return -1
        val chunk = buf.copyOfRange(offset, offset + len)
        val waiter = waitingReads.removeFirstOrNull()
        if (waiter != null) waiter.complete(chunk) else queuedReads.addLast(chunk)
        return len
    }

    fun close() {
        if (closed) return
        closed = true
        while (waitingReads.isNotEmpty()) waitingReads.removeAt(0).complete(null)
        queuedReads.clear()
    }

    private fun drainChunk(chunk: ByteArray, buf: ByteArray, offset: Int, len: Int): Int {
        val n = minOf(len, chunk.size)
        chunk.copyInto(buf, offset, 0, n)
        if (n < chunk.size) queuedReads.addFirst(chunk.copyOfRange(n, chunk.size))
        return n
    }
}

class PosixConnectedSocket internal constructor(
    override val remoteAddress: SocketAddress,
    private val incoming: BytePipe,
    private val outgoing: BytePipe,
) : ConnectedSocket {
    private var closed = false

    override suspend fun read(buf: ByteArray, offset: Int, len: Int): Int =
        incoming.read(buf, offset, len)

    override suspend fun write(buf: ByteArray, offset: Int, len: Int): Int {
        if (closed) return -1
        return outgoing.write(buf, offset, len)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        incoming.close()
        outgoing.close()
    }
}

class PosixListeningSocket internal constructor(
    override val bindAddress: SocketAddress.Inet,
) : ListeningSocket {
    private val queuedConnections = ArrayDeque<ConnectedSocket>()
    private val waitingAccepts = mutableListOf<CompletableDeferred<ConnectedSocket?>>()
    private var closed = false

    fun enqueue(conn: ConnectedSocket) {
        if (closed) return
        val waiter = waitingAccepts.removeFirstOrNull()
        if (waiter != null) waiter.complete(conn) else queuedConnections.addLast(conn)
    }

    override suspend fun accept(): ConnectedSocket? {
        if (queuedConnections.isNotEmpty()) return queuedConnections.removeFirst()
        if (closed) return null
        val deferred = CompletableDeferred<ConnectedSocket?>()
        waitingAccepts.add(deferred)
        return deferred.await()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        PosixSocketRegistry.closeListener(bindAddress)
        while (waitingAccepts.isNotEmpty()) waitingAccepts.removeAt(0).complete(null)
        queuedConnections.clear()
    }
}
