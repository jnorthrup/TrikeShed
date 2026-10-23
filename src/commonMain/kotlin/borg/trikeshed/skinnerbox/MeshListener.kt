package borg.trikeshed.skinnerbox

import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.ServerSocketChannel
import borg.trikeshed.userspace.nio.channels.SocketChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

data class MeshListenerLimits(
    val workers: Int = 3,
    val pendingConnections: Int = 6,
    val maxHeaderBytes: Int = 8192,
    val maxBodyBytes: Int = MeshEnvelope.MAX_WIRE_BYTES,
    val maxResponseBytes: Int = MeshEnvelope.MAX_WIRE_BYTES,
    val readTimeoutMillis: Long = 5000,
    val writeTimeoutMillis: Long = 5000,
    val strokeBytes: Int = 16 * 1024,
    val admittedBytesPerLane: Int = 3 * MeshEnvelope.MAX_WIRE_BYTES,
) {
    init {
        require(workers in 1..3 && pendingConnections in 1..64)
        require(maxHeaderBytes in 1024..16 * 1024)
        require(maxBodyBytes in 1..MeshEnvelope.MAX_WIRE_BYTES)
        require(maxResponseBytes in 1..MeshEnvelope.MAX_WIRE_BYTES)
        require(readTimeoutMillis in 1..60_000 && writeTimeoutMillis in 1..60_000)
        require(strokeBytes in 1024..64 * 1024)
        require(admittedBytesPerLane in maxBodyBytes..64 * MeshEnvelope.MAX_WIRE_BYTES)
    }
}

typealias MeshHttpHandler = suspend (String, String, Map<String, String>, ByteArray) -> WireHttpResponse?

class MeshHttpException(val status: Int, message: String) : IllegalArgumentException(message)

data class MeshHttpHead(val method: String, val target: String, val headers: Map<String, String>, val length: Int)

/** One strictly framed HTTP/1.1 request per connection. No transfer coding or pipelining. */
object MeshHttp {
    fun parseHead(bytes: ByteArray, limits: MeshListenerLimits = MeshListenerLimits()): MeshHttpHead {
        if (bytes.size > limits.maxHeaderBytes) throw MeshHttpException(431, "Header limit")
        if (bytes.any { (it.toInt() and 255).let { c -> c !in 32..126 && c !in setOf(9, 10, 13) } })
            throw MeshHttpException(400, "Header encoding")
        val text = bytes.decodeToString()
        if (!text.endsWith("\r\n\r\n")) throw MeshHttpException(400, "Header boundary")
        val lines = text.dropLast(4).split("\r\n")
        if (lines.size > 65 || lines.any { '\r' in it || '\n' in it }) throw MeshHttpException(400, "Header lines")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[2] != "HTTP/1.1" ||
            request[0].isEmpty() || request[0].length > 16 || request[0].any { it !in 'A'..'Z' } ||
            request[1].length !in 1..4096 || !request[1].startsWith('/') || request[1].startsWith("//") ||
            request[1].any { it.code !in 33..126 || it == '#' || it == '\\' })
            throw MeshHttpException(400, "Request line")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line[0].isWhitespace()) throw MeshHttpException(400, "Header name")
            val name = line.substring(0, colon)
            if (!token(name)) throw MeshHttpException(400, "Header name")
            val key = name.lowercase()
            if (key in headers) throw MeshHttpException(400, "Duplicate header")
            headers[key] = line.substring(colon + 1).trim(' ', '\t')
        }
        val host = headers["host"]
        if (host.isNullOrEmpty() || host.any { it.isWhitespace() }) throw MeshHttpException(400, "Host required")
        if ("transfer-encoding" in headers || "upgrade" in headers) throw MeshHttpException(400, "Unsupported framing")
        if ("expect" in headers) throw MeshHttpException(417, "Expectation unsupported")
        val lengthText = headers["content-length"]
        if (lengthText == null && request[0] in setOf("POST", "PUT", "PATCH"))
            throw MeshHttpException(411, "Content-Length required")
        val length = if (lengthText == null) 0L else {
            if (lengthText.length !in 1..10 || lengthText.any { it !in '0'..'9' })
                throw MeshHttpException(400, "Content-Length")
            lengthText.toLongOrNull() ?: throw MeshHttpException(413, "Body limit")
        }
        if (length > limits.maxBodyBytes) throw MeshHttpException(413, "Body limit")
        return MeshHttpHead(request[0], request[1], headers.toMap(), length.toInt())
    }

    internal fun token(text: String): Boolean = text.isNotEmpty() && text.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~"
    }

    internal fun boundary(bytes: ByteArray, count: Int, start: Int = 0): Int {
        for (i in start..count - 4) if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
            bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()) return i + 4
        return -1
    }
}

/** Bounded HTTP ingress through the common userspace NIO/uring socket contract. */
class MeshListener private constructor(
    parent: CoroutineScope,
    private val server: ServerSocketChannel,
    private val limits: MeshListenerLimits,
    private val handle: MeshHttpHandler,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MeshListener> {
        fun open(
            scope: CoroutineScope,
            address: String = "127.0.0.1:0",
            limits: MeshListenerLimits = MeshListenerLimits(),
            allowExternalBind: Boolean = false,
            handle: MeshHttpHandler,
        ): MeshListener {
            requireNotNull(scope.coroutineContext[Job]) { "Mesh listener requires an owning Job" }.ensureActive()
            require(allowExternalBind || address.startsWith("127.0.0.1:") || address.startsWith("[::1]:")) {
                "External mesh listener requires an explicit TLS proxy deployment"
            }
            val server = ServerSocketChannel.open(if (address.startsWith('[')) "INET6" else "INET")
            try {
                server.configureBlocking(false)
                server.setOption("SO_REUSEADDR", true)
                server.bind(address, limits.workers + limits.pendingConnections)
                return MeshListener(scope, server, limits, handle)
            } catch (failure: Throwable) {
                runCatching { server.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    val address: String = server.getLocalAddress()
    private val supervisor = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + supervisor + this)
    private val stopping = CompletableDeferred<Unit>()
    private val clients = Channel<SocketChannel>(limits.pendingConnections, onUndeliveredElement = { runCatching { it.close() } })
    private data class Request(val client: SocketChannel, val head: MeshHttpHead, val body: ByteArray)
    private class Lane(capacity: Int) {
        val requests = Channel<Request>(capacity)
        val admission = Mutex()
        var bytes = 0
    }
    private val application = Lane(limits.pendingConnections)
    private val control = Lane(limits.pendingConnections)
    private val workers = listOf(application, control).flatMap { lane -> List(limits.workers) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // These queues contain admitted work. Parent cancellation closes admission through
            // the framing fan-in below; it cannot discard a request with possible effects.
            withContext(NonCancellable) {
                for (request in lane.requests) {
                    try { serve(request) } finally {
                        runCatching { request.client.close() }
                        lane.admission.withLock { lane.bytes -= request.body.size }
                    }
                    yield()
                }
            }
        }
    } }
    private val readers = List(limits.workers) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (client in clients) {
                var admitted = false
                try {
                    val (head, payload) = withTimeout(limits.readTimeoutMillis) { read(client) }
                    val request = Request(client, head, payload)
                    val lane = if (control(head, payload)) control else application
                    admitted = lane.admission.withLock {
                        if (stopping.isCompleted || !supervisor.isActive ||
                            payload.size > limits.admittedBytesPerLane - lane.bytes) false
                        else if (lane.requests.trySend(request).isSuccess) { lane.bytes += payload.size; true }
                        else false
                    }
                    if (!admitted) replyError(client, 503)
                } catch (_: TimeoutCancellationException) {
                    replyError(client, 408)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: MeshHttpException) {
                    replyError(client, failure.status)
                } catch (_: Exception) {
                    // A disconnected or malformed client cannot stop the framing stage.
                } finally {
                    if (!admitted) runCatching { client.close() }
                }
                yield()
            }
        }
    }
    private val framing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try { readers.joinAll() } finally {
            withContext(NonCancellable) {
                readers.joinAll()
                application.requests.close()
                control.requests.close()
            }
        }
    }
    private val acceptor = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            var pause = 1L
            while (isActive && !stopping.isCompleted) {
                val client = server.accept()
                if (client == null) { delay(pause); pause = minOf(16, pause * 2); continue }
                pause = 1
                try {
                    client.configureBlocking(false)
                    // Refusal is immediate: never create a coroutine or read a body for a full queue.
                    if (clients.trySend(client).isFailure) client.close()
                } catch (failure: Throwable) {
                    runCatching { client.close() }
                    throw failure
                }
                yield()
            }
        } finally {
            stopping.complete(Unit)
            try { server.close() } finally {
                if (!isActive) clients.cancel() else clients.close()
            }
        }
    }

    /** Classification chooses a bounded queue, never authenticates a peer or authorizes effects. */
    private fun control(head: MeshHttpHead, bytes: ByteArray): Boolean {
        if (head.method != "POST" || head.target != MeshEnvelope.PATH) return false
        return try {
            val envelope = MeshEnvelope.decode(bytes)
            !envelope.response && envelope.verb in setOf("mesh.retain", "mesh.find")
        } catch (_: IllegalArgumentException) { false }
    }

    private suspend fun serve(request: Request) {
        try {
            val head = request.head
            val response = try { handle(head.method, head.target, head.headers, request.body) ?: error(404, "not_found") }
            catch (failure: MeshHttpException) { error(failure.status, "invalid_request") }
            catch (_: Exception) { error(503, "unavailable") }
            withTimeout(limits.writeTimeoutMillis) { write(request.client, response, head.method == "HEAD") }
        } catch (_: Exception) {
            // A reply timeout or disconnected socket cannot stop the execution stage.
        }
    }

    private suspend fun replyError(client: SocketChannel, status: Int) {
        if (!stopping.isCompleted) runCatching {
            withContext(NonCancellable) {
                withTimeout(limits.writeTimeoutMillis) { write(client, error(status, "invalid_request"), false) }
            }
        }
    }

    private fun error(status: Int, code: String) = WireHttpResponse(status, "{\"code\":\"$code\"}")

    private suspend fun read(client: SocketChannel): Pair<MeshHttpHead, ByteArray> {
        val header = ByteBuffer(limits.maxHeaderBytes)
        var end = -1
        var pause = 1L
        while (end < 0) {
            checkReading()
            if (!header.hasRemaining()) throw MeshHttpException(431, "Header limit")
            val previous = header.position()
            when (client.read(header)) {
                -1 -> throw MeshHttpException(400, "Incomplete header")
                0 -> { delay(pause); pause = minOf(16, pause * 2) }
                else -> {
                    pause = 1
                    end = MeshHttp.boundary(header.array(), header.position(), maxOf(0, previous - 3))
                }
            }
        }
        val head = MeshHttp.parseHead(header.array().copyOf(end), limits)
        val present = header.position() - end
        if (present > head.length) throw MeshHttpException(400, "Pipelining unsupported")
        // Only the validated declared size allocates a body. Pending sockets have no body allocation.
        val body = ByteArray(head.length)
        header.array().copyInto(body, 0, end, end + present)
        var at = present
        while (at < body.size) {
            checkReading()
            val size = minOf(limits.strokeBytes, body.size - at)
            val buffer = ByteBuffer(body, at, size)
            when (val count = client.read(buffer)) {
                -1 -> throw MeshHttpException(400, "Incomplete body")
                0 -> { delay(pause); pause = minOf(16, pause * 2) }
                else -> { at += count; pause = 1; yield() }
            }
        }
        return head to body
    }

    private suspend fun checkReading() {
        currentCoroutineContext().ensureActive()
        if (stopping.isCompleted) throw MeshHttpException(503, "Mesh listener draining")
    }

    private suspend fun write(client: SocketChannel, response: WireHttpResponse, head: Boolean) {
        val body = response.payloadBytes
        require(body.size <= limits.maxResponseBytes) { "Mesh response limit" }
        require(response.status in 100..599)
        val headers = buildString {
            append("HTTP/1.1 ").append(response.status).append(" Mesh\r\n")
            append("Content-Length: ").append(body.size).append("\r\nConnection: close\r\n")
            require(response.contentType.all { it.code in 32..126 })
            append("Content-Type: ").append(response.contentType).append("\r\n")
            for ((name, value) in response.headers) {
                require(MeshHttp.token(name) && value.all { it.code in 32..126 })
                require(name.lowercase() !in setOf("content-length", "transfer-encoding", "connection", "content-type"))
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }.encodeToByteArray()
        require(headers.size <= limits.maxHeaderBytes)
        writeBytes(client, headers)
        if (!head) writeBytes(client, body)
    }

    private suspend fun writeBytes(client: SocketChannel, bytes: ByteArray) {
        var at = 0
        var pause = 1L
        while (at < bytes.size) {
            val buffer = ByteBuffer(bytes, at, minOf(limits.strokeBytes, bytes.size - at))
            when (val count = client.write(buffer)) {
                0 -> { delay(pause); pause = minOf(16, pause * 2) }
                else -> { check(count > 0) { "Mesh connection closed" }; at += count; pause = 1; yield() }
            }
        }
    }

    /** Stop accepting, close partial reads, settle admitted requests, and close every socket. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        stopping.complete(Unit)
        acceptor.join()
        framing.join()
        workers.forEach { it.join() }
        supervisor.complete()
        supervisor.join()
    }
}
