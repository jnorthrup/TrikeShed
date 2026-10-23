package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.ServerSocketChannel
import borg.trikeshed.userspace.nio.channels.SocketChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/** Authenticated loopback commands against the filesystem already owned by a mount. */
class BtrfsMountControl private constructor(
    parent: CoroutineScope,
    private val server: ServerSocketChannel,
    private val token: String,
    private val execute: suspend (List<String>) -> String,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<BtrfsMountControl> {
        private const val MAX_FRAME = 4096
        private val commands = setOf("info", "add", "balance", "compression")

        fun open(scope: CoroutineScope, token: String, execute: suspend (List<String>) -> String): BtrfsMountControl {
            requireNotNull(scope.coroutineContext[Job]) { "Mount control requires an owning Job" }.ensureActive()
            require(token.length >= 32 && token.all { it.isLetterOrDigit() }) { "A random alphanumeric control token is required" }
            val server = ServerSocketChannel.open()
            try {
                server.configureBlocking(false)
                server.bind("127.0.0.1:0", 4)
                return BtrfsMountControl(scope, server, token, execute)
            } catch (failure: Throwable) {
                runCatching { server.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                throw failure
            }
        }

        /** One command per connection. The server serializes these with filesystem operations. */
        suspend fun request(address: String, token: String, arguments: List<String>): String {
            require(address.startsWith("127.0.0.1:") && address.substringAfter(':').toIntOrNull() in 1..65535) {
                "Mount control must connect to 127.0.0.1:PORT"
            }
            validate(arguments)
            require(token.length >= 32 && token.all { it.isLetterOrDigit() }) { "Invalid control token" }
            val client = SocketChannel.open()
            try {
                client.configureBlocking(false)
                client.connect(address)
                withTimeout(5000) { writeFrame(client, (listOf(token) + arguments).joinToString(" ")) }
                val response = readFrame(client)
                check(response.startsWith("OK\n")) { response.removePrefix("ERR\n") }
                return response.removePrefix("OK\n")
            } finally { client.close() }
        }

        private fun validate(arguments: List<String>) {
            require(arguments.firstOrNull() in commands) { "Live commands: info, add, balance, compression" }
            require(arguments.size <= 4 && arguments.all { value ->
                value.isNotEmpty() && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
            }) { "Invalid mount control operands" }
        }

        private suspend fun readFrame(socket: SocketChannel, stop: () -> Boolean = { false }): String {
            val header = ByteBuffer(4)
            readFully(socket, header, stop)
            val size = header.getInt(0)
            require(size in 1..MAX_FRAME) { "Invalid control frame size" }
            val payload = ByteBuffer(size)
            readFully(socket, payload, stop)
            return payload.array().decodeToString(throwOnInvalidSequence = true)
        }

        private suspend fun readFully(socket: SocketChannel, buffer: ByteBuffer, stop: () -> Boolean) {
            while (buffer.hasRemaining()) {
                currentCoroutineContext().ensureActive()
                check(!stop()) { "Mount control is draining" }
                when (socket.read(buffer)) {
                    -1 -> error("Mount control connection closed")
                    0 -> delay(10)
                }
            }
        }

        private suspend fun writeFrame(socket: SocketChannel, message: String) {
            val bytes = message.encodeToByteArray()
            require(bytes.size in 1..MAX_FRAME) { "Control response exceeds $MAX_FRAME bytes" }
            val frame = ByteBuffer(4 + bytes.size).putInt(bytes.size).put(bytes).flip()
            while (frame.hasRemaining()) {
                currentCoroutineContext().ensureActive()
                if (socket.write(frame) == 0) delay(10)
            }
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key
    val address: String = server.getLocalAddress()
    private val supervisor = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val scope = CoroutineScope(parent.coroutineContext + supervisor + this)
    private val stop = CompletableDeferred<Unit>()
    private data class Request(val arguments: List<String>, val reply: CompletableDeferred<String>)
    private val requests = Channel<Request>(1)
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) {
            for (request in requests) {
                val response = try { "OK\n${execute(request.arguments)}" }
                catch (failure: Throwable) { "ERR\n${failure.message ?: failure::class.simpleName}" }
                request.reply.complete(response.take(MAX_FRAME / 2))
            }
        }
    }
    private val listener = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            while (isActive && !stop.isCompleted) {
                val client = server.accept()
                if (client == null) { delay(10); continue }
                try {
                    client.configureBlocking(false)
                    val input = withTimeout(5000) { readFrame(client) { stop.isCompleted } }.split(' ')
                    require(input.firstOrNull() == token) { "Invalid mount control token" }
                    val arguments = input.drop(1)
                    validate(arguments)
                    check(!stop.isCompleted) { "Mount control is draining" }
                    val reply = CompletableDeferred<String>()
                    requests.send(Request(arguments, reply))
                    val response = reply.await()
                    withTimeout(5000) { writeFrame(client, response) }
                } catch (_: TimeoutCancellationException) {
                    // A slow local client must not terminate the mount's control listener.
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    runCatching { withTimeout(5000) { writeFrame(client, "ERR\n${failure.message ?: "Invalid request"}") } }
                } finally { client.close() }
            }
        } finally {
            requests.close()
            server.close()
        }
    }

    /** Stop admission, finish any admitted command/reply, then join and close owned work. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        stop.complete(Unit)
        listener.join()
        worker.join()
        supervisor.complete()
        supervisor.join()
    }
}
