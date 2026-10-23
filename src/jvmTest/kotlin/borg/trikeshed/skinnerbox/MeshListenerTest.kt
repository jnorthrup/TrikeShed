package borg.trikeshed.skinnerbox

import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.SocketChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class MeshListenerTest {
    private data class Reply(val status: Int, val head: String, val body: ByteArray)

    private suspend fun request(address: String, header: String, body: ByteArray = byteArrayOf()): Reply = withTimeout(10_000) {
        val socket = SocketChannel.openWithRemote(address)
        socket.configureBlocking(false)
        try {
            for (bytes in listOf(header.encodeToByteArray(), body)) {
                val buffer = ByteBuffer(bytes)
                while (buffer.hasRemaining()) if (socket.write(buffer) == 0) delay(1)
            }
            val response = ByteBuffer(200 * 1024)
            while (true) {
                check(response.hasRemaining())
                when (socket.read(response)) { -1 -> break; 0 -> delay(1) }
            }
            val bytes = response.array().copyOf(response.position())
            val end = MeshHttp.boundary(bytes, bytes.size)
            check(end >= 0)
            val head = bytes.decodeToString(0, end)
            Reply(head.substringAfter(' ').substringBefore(' ').toInt(), head, bytes.copyOfRange(end, bytes.size))
        } finally { socket.close() }
    }

    @Test
    fun commonNioListenerPreservesBinaryFramesAndRejectsOversizedDeclaration() = runBlocking<Unit> {
        withTimeout(20_000) {
            var calls = 0
            val listener = MeshListener.open(this) { method, path, headers, bytes ->
                calls++
                assertEquals("POST", method)
                assertEquals("/echo", path)
                assertEquals("application/octet-stream", headers["content-type"])
                WireHttpResponse(200, "", "application/octet-stream", bytes)
            }
            try {
                val bytes = ByteArray(48 * 1024 + 7) { (it % 253).toByte() }
                val response = request(listener.address,
                    "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n\r\n", bytes)
                assertEquals(200, response.status)
                assertContentEquals(bytes, response.body)
                assertEquals(413, request(listener.address,
                    "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2147483647\r\n\r\n").status)
                assertEquals(400, request(listener.address,
                    "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n").status)
                assertEquals(1, calls)
            } finally { listener.drain() }
        }
    }

    @Test
    fun drainInterruptsPartialUnadmittedFramesAndSettlesAdmittedReply() = runBlocking<Unit> {
        withTimeout(20_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val listener = MeshListener.open(this) { _, _, _, _ ->
                calls++; entered.complete(Unit); release.await(); WireHttpResponse(200, "settled")
            }
            val partial = SocketChannel.openWithRemote(listener.address)
            partial.configureBlocking(false)
            try {
                partial.write(ByteBuffer("POST / HTTP/1.1\r\nHost: local".encodeToByteArray()))
                val complete = async { request(listener.address, "GET /ready HTTP/1.1\r\nHost: localhost\r\n\r\n") }
                entered.await()
                val drain = async { listener.drain() }
                delay(30)
                assertFalse(drain.isCompleted)
                release.complete(Unit)
                assertEquals("settled", complete.await().body.decodeToString())
                withTimeout(1500) { drain.await() }
                assertEquals(1, calls)
                listener.drain()
            } finally { release.complete(Unit); partial.close(); listener.drain() }
        }
    }

    @Test
    fun handlerFailureDoesNotKillAdmissionAndHeadOmitsPayload() = runBlocking<Unit> {
        withTimeout(20_000) {
            val listener = MeshListener.open(this) { _, path, _, _ ->
                if (path == "/bad") error("do not leak handler details")
                WireHttpResponse(200, "body")
            }
            try {
                val bad = request(listener.address, "GET /bad HTTP/1.1\r\nHost: localhost\r\n\r\n")
                assertEquals(503, bad.status)
                assertFalse(bad.body.decodeToString().contains("leak"))
                val good = request(listener.address, "HEAD /good HTTP/1.1\r\nHost: localhost\r\n\r\n")
                assertEquals(200, good.status)
                assertContains(good.head, "Content-Length: 4\r\n")
                assertContentEquals(byteArrayOf(), good.body)
            } finally { listener.drain() }
        }
    }

    @Test
    fun replicaAndRoutingControlsProgressWhileAllApplicationWorkersWait() = runBlocking<Unit> {
        withTimeout(20_000) {
            val entered = Channel<Unit>(3)
            val release = CompletableDeferred<Unit>()
            val listener = MeshListener.open(this) { _, path, _, body ->
                if (path == MeshEnvelope.PATH) {
                    val verb = MeshEnvelope.decode(body).verb
                    if (verb == "mesh.retain") release.complete(Unit)
                    WireHttpResponse(200, verb)
                } else {
                    entered.send(Unit)
                    release.await()
                    WireHttpResponse(200, "settled")
                }
            }
            try {
                val ordinary = List(3) { async { request(listener.address, "GET /wait HTTP/1.1\r\nHost: localhost\r\n\r\n") } }
                repeat(3) { entered.receive() }
                for (verb in listOf("mesh.find", "mesh.retain")) {
                    val body = MeshEnvelope.request(meshPeer(1), meshPeer(2), MeshTask("tenant", verb, byteArrayOf()),
                        meshIdentity(1), 100_000, ByteArray(32)).bytes()
                    val reply = withTimeout(2000) { request(listener.address,
                        "POST ${MeshEnvelope.PATH} HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.size}\r\n\r\n", body) }
                    assertEquals(verb, reply.body.decodeToString())
                }
                ordinary.forEach { assertEquals("settled", it.await().body.decodeToString()) }
            } finally { release.complete(Unit); listener.drain(); entered.close() }
        }
    }

    @Test
    fun immediateParentCancellationClosesListenerAndWorkers() = runBlocking<Unit> {
        withTimeout(10_000) {
            val parent = SupervisorJob(coroutineContext[Job])
            val listener = MeshListener.open(CoroutineScope(coroutineContext + parent)) { _, _, _, _ -> WireHttpResponse(200, "unused") }
            parent.cancel()
            listener.drain()
            parent.join()
            assertFailsWith<Exception> { SocketChannel.openWithRemote(listener.address).close() }
        }
    }
}
