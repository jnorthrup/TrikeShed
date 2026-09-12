package borg.trikeshed.userspace

import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.s_
import borg.trikeshed.reactor.JvmTlsCodecBackend
import borg.trikeshed.reactor.JvmTlsCodecBackendTest
import borg.trikeshed.reactor.TlsApplicationProtocol
import borg.trikeshed.reactor.TlsConfig
import borg.trikeshed.reactor.TlsConnectionState
import borg.trikeshed.reactor.TlsElement
import borg.trikeshed.reactor.TlsFlowStage
import borg.trikeshed.reactor.TlsFrames
import borg.trikeshed.reactor.TlsProtocol
import borg.trikeshed.reactor.openTlsElement
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit two-process fixture. Bind/connect only loopback SSH-forward endpoints. */
@Timeout(90)
class JvmUringTlsInteropTest {
    @Test
    fun crossHostTlsPeer() = runBlocking {
        val role = System.getenv("TRIKESHED_TLS_ROLE")
        assumeTrue(role != null, "Set TRIKESHED_TLS_ROLE=client or server for the cross-host fixture")
        require(role == "client" || role == "server")
        val host = System.getenv("TRIKESHED_TLS_HOST") ?: "127.0.0.1"
        require(host == "127.0.0.1") { "Use an SSH forward bound to 127.0.0.1" }
        val port = requireNotNull(System.getenv("TRIKESHED_TLS_PORT")) { "Set TRIKESHED_TLS_PORT" }.toInt()
        require(port in 1024..65535)
        val protocol = when (System.getenv("TRIKESHED_TLS_PROTOCOL") ?: "TLS13") {
            "TLS13" -> TlsProtocol.TLS13
            "TLS12" -> TlsProtocol.TLS12
            else -> error("TRIKESHED_TLS_PROTOCOL must be TLS12 or TLS13")
        }
        peer(role, port, protocol)
    }

    private suspend fun peer(role: String, port: Int, protocol: TlsProtocol): Unit = coroutineScope {
        val directory = Files.createTempDirectory("uring-tls-peer-")
        val certificate = directory.resolve("certificate.pem")
        val key = directory.resolve("key.pem")
        var ring: FunctionalUringFacade? = null
        var tls: TlsElement? = null
        val descriptors = mutableListOf<Int>()
        var admitted = 0L
        var settled = 0L
        suspend fun submit(op: UringOp, fd: Int, buffer: ByteBuffer? = null,
                           length: Int = buffer?.remaining() ?: 0, offset: Long = 0): Int {
            val id = ++admitted
            val result = requireNotNull(ring).batchEnqueue(1 j {
                UringSubmission(op, fd, 0, length, offset, userData = id, buffer = buffer)
            })
            assertEquals(1, result.size)
            assertEquals(id, result[0].userData)
            settled++
            return result[0].res
        }
        suspend fun socket(): Int = submit(UringOp.SOCKET, 2, length = 6, offset = 1).also {
            assertTrue(it >= 0, "SOCKET failed: $it")
            descriptors.add(it)
        }
        try {
            Files.writeString(certificate, JvmTlsCodecBackendTest.TEST_CERTIFICATE_PEM)
            Files.writeString(key, JvmTlsCodecBackendTest.TEST_PRIVATE_KEY_PEM)
            ring = FunctionalUringFacade.create(this, 16)
            tls = openTlsElement(TlsConfig(
                trustStore = certificate.toString(),
                certificateFile = certificate.toString().takeIf { role == "server" },
                privateKeyFile = key.toString().takeIf { role == "server" },
                protocols = s_[protocol], alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1],
                hostnameVerification = true,
            ), JvmTlsCodecBackend(), parentJob = coroutineContext[Job])
            withTimeout(60_000) {
                val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))
                val fd = if (role == "server") {
                    val listener = socket()
                    assertEquals(0, submit(UringOp.BIND, listener, address))
                    assertEquals(0, submit(UringOp.LISTEN, listener, length = 1))
                    println("URING_TLS_READY role=server host=127.0.0.1 port=$port protocol=$protocol")
                    var accepted: Int
                    do {
                        accepted = submit(UringOp.ACCEPT, listener)
                        if (accepted == -11) delay(2)
                    } while (accepted == -11)
                    assertTrue(accepted >= 0, "ACCEPT failed: $accepted")
                    descriptors.add(accepted)
                    accepted
                } else {
                    socket().also { assertEquals(0, submit(UringOp.CONNECT, it, address)) }
                }
                val endpoint = if (role == "server") requireNotNull(tls).serverEndpoint("127.0.0.1", port)
                    else requireNotNull(tls).clientEndpoint("localhost", port)
                val plaintext = ArrayList<Byte>()
                suspend fun emit(frames: TlsFrames) {
                    for (index in 0 until frames.size) {
                        val frame = frames[index]
                        when (frame.stage) {
                            TlsFlowStage.DOWNSTREAM_PLAINTEXT -> {
                                assertTrue(plaintext.size + frame.payload.rem <= 65536, "Plaintext exceeds fixture bound")
                                plaintext.addAll(frame.payload.toArray().asList())
                            }
                            TlsFlowStage.UPSTREAM_CIPHERTEXT, TlsFlowStage.CLOSE_NOTIFY -> {
                                val bytes = ByteBuffer(frame.payload.toArray())
                                while (bytes.hasRemaining()) {
                                    val count = submit(UringOp.WRITE, fd, bytes, offset = -1)
                                    if (count == -11) delay(2)
                                    else assertTrue(count > 0, "Ciphertext WRITE failed: $count")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                suspend fun readCiphertext(): ByteArray {
                    val bytes = ByteBuffer(4096)
                    while (true) {
                        val count = submit(UringOp.READ, fd, bytes, offset = -1)
                        if (count == -11) { delay(2); continue }
                        assertTrue(count >= 0, "Ciphertext READ failed: $count")
                        return bytes.array().copyOf(count)
                    }
                }
                suspend fun receive() {
                    val bytes = readCiphertext()
                    assertTrue(bytes.isNotEmpty(), "TLS transport EOF before close_notify")
                    emit(endpoint.downstream(ByteSeries(bytes)))
                }
                suspend fun expectPlaintext(expected: ByteArray) {
                    while (plaintext.size < expected.size) receive()
                    assertContentEquals(expected, plaintext.toByteArray())
                    plaintext.clear()
                }
                emit(endpoint.handshake())
                while (!endpoint.isHandshakeComplete) receive()
                assertEquals(protocol, endpoint.session?.protocol)
                val payload = ByteArray(8193) { (it % 251).toByte() }
                val request = "POST /uring-tls HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${payload.size}\r\n\r\n".encodeToByteArray() + payload
                val response = "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".encodeToByteArray() + payload
                if (role == "client") {
                    emit(endpoint.upstream(ByteSeries(request)))
                    expectPlaintext(response)
                    emit(endpoint.close())
                    assertEquals(0, submit(UringOp.SHUTDOWN, fd, length = 1))
                    // Endpoint.close ends its codec API. Consume peer ciphertext to TCP EOF;
                    // server role separately verifies receipt of the authenticated client alert.
                    while (readCiphertext().isNotEmpty()) Unit
                } else {
                    expectPlaintext(request)
                    emit(endpoint.upstream(ByteSeries(response)))
                    while (endpoint.flowState.lifecycle != TlsConnectionState.CLOSED) receive()
                    emit(endpoint.close())
                    assertEquals(0, submit(UringOp.SHUTDOWN, fd, length = 1))
                }
                assertEquals(TlsConnectionState.CLOSED, endpoint.flowState.lifecycle)
            }
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                suspend fun release(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = failure
                        else requireNotNull(cleanupFailure).addSuppressed(failure)
                    }
                }
                tls?.let { element -> release {
                    element.drain()
                    assertEquals(ElementState.CLOSED, element.state)
                } }
                for (fd in descriptors.asReversed()) release { assertEquals(0, submit(UringOp.CLOSE, fd)) }
                release { ring?.drain() }
                release { assertEquals(admitted, settled) }
                release { Files.deleteIfExists(key) }
                release { Files.deleteIfExists(certificate) }
                release { Files.deleteIfExists(directory) }
                cleanupFailure?.let { throw it }
            }
        }
        println("URING_TLS_VERIFIED role=$role protocol=$protocol admitted=$admitted settled=$settled " +
            "backend=${ring?.availability} nativeCapabilities=${ring?.nativeCapabilities}")
    }
}
