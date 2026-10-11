package borg.trikeshed.loom

import borg.trikeshed.litebike.*
import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.*

/** A loopback HTTP/1.1 fake on the litebike listener: logs each request's bytes and answers it with the next scripted reply. */
class LoopbackFake(replies: List<ByteArray>) {
    val replies = ArrayDeque(replies)
    val log = mutableListOf<ByteArray>()
    val port = ServerSocket(0).use { it.localPort }
    val base = "http://127.0.0.1:$port/"
    val listener = LitebikeListenerElement()
    val connections = ConnectionRegistry()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(): LoopbackFake {
        listener.open()
        val slot = listener.register(Protocol.Http)
        scope.launch {
            while (true) {
                val message = slot.consume()
                val reply = synchronized(log) {
                    log += message.payload
                    replies.removeFirstOrNull()
                } ?: reply(500, emptyList(), "unscripted request".encodeToByteArray())
                message.respond?.invoke(reply)
            }
        }
        scope.launch { JvmLitebikeBindAdapter.bindAndServe(listener, port, "127.0.0.1", connections) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) {
            check(System.nanoTime() < deadline) { "loopback fake did not bind" }
            delay(20)
        }
        return this
    }

    suspend fun stop() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        listener.close()
        connections.closeAll()
    }

    companion object {
        /** The reply shape of the Rust capture harness's fake: status line, headers, content-length, connection: close. */
        fun reply(status: Int, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
            val reason = mapOf(200 to "OK", 403 to "Forbidden", 404 to "Not Found", 412 to "Precondition Failed",
                500 to "Internal Server Error", 503 to "Service Unavailable")[status] ?: "Status"
            val head = StringBuilder("HTTP/1.1 $status $reason\r\n")
            for ((name, value) in headers) head.append("$name: $value\r\n")
            head.append("content-length: ${body.size}\r\nconnection: close\r\n\r\n")
            return head.toString().encodeToByteArray() + body
        }

        fun json(status: Int, body: String): ByteArray = reply(status, listOf("content-type" to "application/json; charset=UTF-8"), body.encodeToByteArray())

        /** Request line, headers (names lowercased) and body of one raw HTTP/1.1 request. */
        fun parse(raw: ByteArray): Triple<String, List<Pair<String, String>>, ByteArray> {
            val end = (0..raw.size - 4).first { raw[it] == 13.toByte() && raw[it + 1] == 10.toByte() && raw[it + 2] == 13.toByte() && raw[it + 3] == 10.toByte() }
            val lines = raw.decodeToString(0, end).split("\r\n")
            val headers = lines.drop(1).map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Triple(lines[0], headers, raw.copyOfRange(end + 4, raw.size))
        }

        /**
         * The request Rust sent equals ours: request line, body, and headers once each HTTP stack's own are set
         * aside: `host` (each fake's port) on both, reqwest's default `accept: * / *`, and HTX's
         * `connection: close` plus the `accept-encoding: identity` it adds where the caller set none.
         */
        fun assertSameRequest(rust: ByteArray, ours: ByteArray, name: String) {
            val (rustLine, rustHeaders, rustBody) = parse(rust)
            val (ourLine, ourHeaders, ourBody) = parse(ours)
            assertEquals(rustLine, ourLine, name)
            assertContentEquals(rustBody, ourBody, name)
            assertEquals("127.0.0.1", ourHeaders.single { it.first == "host" }.second.substringBefore(':'), name)
            val expected = rustHeaders.filterNot { it.first == "host" || it == ("accept" to "*/*") }.toMutableList()
            if (expected.none { it.first == "accept-encoding" }) expected += "accept-encoding" to "identity"
            val actual = ourHeaders.filterNot { it.first == "host" || it == ("connection" to "close") }
            assertEquals(expected.sortedBy { it.first }, actual.sortedBy { it.first }, name)
        }
    }
}
