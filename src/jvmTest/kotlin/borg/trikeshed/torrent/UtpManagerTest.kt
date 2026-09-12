package borg.trikeshed.torrent

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UringTrace
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.*
import java.util.Collections
import kotlin.random.Random
import kotlin.test.*

/** The UDP fixture also enters through the common facade; no DatagramChannel bypass. */
class UtpManagerTest {
    @Test fun connectedDatagramsDeliverPeerWireAndDrainDescriptors() = runBlocking {
        withTimeout(15000) {
            val completions = Collections.synchronizedList(mutableListOf<Pair<UringOp, Int>>())
            val trace = object : UringTrace {
                override fun channel(id: Long, availability: String, nativeCapabilities: Long) = Unit
                override fun submit(channel: Long, submission: UringSubmission) = Unit
                override fun complete(channel: Long, submission: UringSubmission, result: Int) {
                    completions.add(submission.opcode to result)
                }
                override fun failed(channel: Long, submission: UringSubmission, failure: String) = Unit
            }
            val owner = SupervisorJob(currentCoroutineContext()[Job])
            val scope = CoroutineScope(currentCoroutineContext() + owner + trace)
            val ring = TorrentSocketRing(scope)
            var fd = -1
            var server: Job? = null
            val stop = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<Unit>()
            val clientPort = Random.nextInt(40000, 45000)
            val manager = UtpManager(scope, bindPort = clientPort,
                limits = UtpLimits(connectTimeoutMillis = 3000, closeTimeoutMillis = 1500))
            try {
                fd = ring.execute(UringOp.SOCKET, 2, length = 17, offset = (2 or 0x800).toLong())
                assertTrue(fd >= 0)
                var serverPort = 0
                for (attempt in 0 until 20) {
                    val candidate = Random.nextInt(45000, 60000)
                    val result = ring.execute(UringOp.BIND, fd, ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), candidate)))
                    if (result == 0) { serverPort = candidate; break }
                    assertEquals(-98, result, "Only EADDRINUSE permits retrying a fixture port")
                }
                assertTrue(serverPort != 0)
                assertEquals(0, ring.execute(UringOp.CONNECT, fd,
                    ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), clientPort))))
                val hash = ByteArray(20) { it.toByte() }
                val peerId = ByteArray(20) { (it + 20).toByte() }
                val block = ByteArray(8192) { (it * 11).toByte() }
                val expected = PeerHandshake(reservedBytes = ByteArray(8), infoHash = hash, peerId = peerId).encode() + PeerWireMessage.Piece(0, 0, block).encode()
                server = scope.launch {
                    val protocol = UtpSocket(65535, false, initialSequence = 41000)
                    val input = ByteBuffer(65507)
                    val received = ByteArray(expected.size)
                    var offset = 0; var echoed = false; var wire: ByteArray? = null
                    try {
                        while (!stop.isCompleted) {
                            input.clear()
                            val count = ring.execute(UringOp.RECV, fd, input)
                            if (count >= 0) {
                                val packet = decodeUtpPacket(input.array().copyOf(count))
                                if (packet != null) {
                                    if (protocol.state == UtpState.IDLE) protocol.accept(packet) else protocol.receive(packet)
                                }
                            } else check(count == -11 || count == -4) { "Fixture RECV: $count" }
                            if (protocol.state != UtpState.IDLE) {
                                protocol.tick()
                                if (offset < expected.size) protocol.read(expected.size - offset)?.let { bytes ->
                                    bytes.copyInto(received, offset); offset += bytes.size
                                }
                                if (offset == expected.size && !echoed) {
                                    assertContentEquals(expected, received)
                                    assertEquals(expected.size, protocol.send(received)); echoed = true
                                }
                                repeat(32) {
                                    val bytes = wire ?: protocol.takePacket()
                                    if (bytes != null) {
                                        wire = bytes
                                        val sent = ring.execute(UringOp.SEND, fd, ByteBuffer(bytes))
                                        if (sent >= 0) { assertEquals(bytes.size, sent); wire = null }
                                        else check(sent == -11 || sent == -4) { "Fixture SEND: $sent" }
                                    }
                                }
                                if (protocol.receivedEof && wire == null) break
                            }
                            delay(2)
                        }
                        outcome.complete(Unit)
                    } catch (failure: Throwable) { outcome.completeExceptionally(failure) }
                }
                val stream = manager.connect(PeerAddress("127.0.0.1", serverPort), 65535)
                stream.write(expected)
                val framer = PeerWireFramer(hash, peerId)
                val messages = mutableListOf<PeerWireMessage>()
                var receivedBytes = 0
                while (receivedBytes < expected.size) {
                    val bytes = stream.read(733)
                    assertTrue(bytes.isNotEmpty())
                    receivedBytes += bytes.size
                    framer.feed(bytes) { messages.add(it) }
                }
                assertContentEquals(peerId, assertNotNull(framer.handshake).peerId)
                val piece = assertIs<PeerWireMessage.Piece>(messages.single())
                assertContentEquals(block, piece.data)
                stream.close(); outcome.await()
                assertContentEquals(byteArrayOf(), stream.read(1))
            } finally {
                stop.complete(Unit)
                withContext(NonCancellable) {
                    try { manager.close() }
                    finally {
                        server?.join()
                        try { if (fd >= 0) assertEquals(0, ring.execute(UringOp.CLOSE, fd)) }
                        finally { ring.close(); owner.complete(); owner.join() }
                    }
                }
            }
            val observed = synchronized(completions) { completions.toList() }
            assertTrue(observed.any { it.first == UringOp.SEND && it.second > 20 })
            assertTrue(observed.any { it.first == UringOp.RECV && it.second > 20 })
            assertEquals(observed.count { it.first == UringOp.SOCKET && it.second >= 0 },
                observed.count { it.first == UringOp.CLOSE && it.second == 0 })
        }
        Unit
    }

    @Test fun invalidEndpointAllocatesNothingAndManagerStillCloses() = runBlocking {
        val manager = UtpManager(this)
        try {
            assertFailsWith<IllegalArgumentException> { manager.connect(PeerAddress("example.invalid", 6881)) }
            assertFailsWith<IllegalArgumentException> { manager.connect(PeerAddress("127.0.0.1", 0)) }
        } finally { manager.close() }
        Unit
    }

    @Test fun handshakeTimeoutSettlesItsOwnerWithoutCancellingTheManager() = runBlocking {
        withTimeout(5000) {
            val ring = TorrentSocketRing(this)
            var fd = -1
            val manager = UtpManager(this, limits = UtpLimits(connectTimeoutMillis = 150, closeTimeoutMillis = 100))
            try {
                fd = ring.execute(UringOp.SOCKET, 2, length = 17, offset = (2 or 0x800).toLong())
                assertTrue(fd >= 0)
                var port = 0
                for (attempt in 0 until 20) {
                    val candidate = Random.nextInt(45000, 60000)
                    val result = ring.execute(UringOp.BIND, fd, ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), candidate)))
                    if (result == 0) { port = candidate; break }
                    assertEquals(-98, result)
                }
                assertTrue(port > 0)
                assertFailsWith<TimeoutCancellationException> { manager.connect(PeerAddress("127.0.0.1", port)) }
                assertTrue(manager.supervisor.isActive)
                assertFalse(manager.supervisor.children.any())
            } finally {
                withContext(NonCancellable) {
                    try { manager.close() }
                    finally {
                        try { if (fd >= 0) assertEquals(0, ring.execute(UringOp.CLOSE, fd)) }
                        finally { ring.close() }
                    }
                }
            }
        }
        Unit
    }
}
