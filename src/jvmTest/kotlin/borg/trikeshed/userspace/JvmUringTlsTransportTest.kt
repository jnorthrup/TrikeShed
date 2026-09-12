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
import borg.trikeshed.reactor.TlsEndpoint
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
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.nio.file.Files
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real TCP ciphertext in both directions; no JDK socket moves test payloads. */
@Timeout(30)
class JvmUringTlsTransportTest {
    @Test
    fun tls13SocketHandshakePayloadAndCloseNotify() = runBlocking {
        exchange(TlsProtocol.TLS13)
    }

    @Test
    fun tls12SocketHandshakePayloadAndCloseNotify() = runBlocking {
        exchange(TlsProtocol.TLS12)
    }

    @Test
    fun untrustedCertificateFailsAndDrainsSocketRing() = runBlocking {
        assertFailsWith<SSLException> { exchange(TlsProtocol.TLS13, trustCertificate = false) }
    }

    @Test
    fun wrongCertificateHostnameFailsAndDrainsSocketRing() = runBlocking {
        assertFailsWith<SSLException> { exchange(TlsProtocol.TLS13, peerName = "wrong.invalid") }
    }

    private suspend fun exchange(
        protocol: TlsProtocol,
        trustCertificate: Boolean = true,
        peerName: String = "localhost",
    ): Unit = coroutineScope {
        // Reserve an ephemeral fixture port; all socket lifecycle and payload IO below are SQEs.
        val port = ServerSocket(0).use { it.localPort }
        val directory = Files.createTempDirectory("uring-tls-")
        val certificate = directory.resolve("certificate.pem")
        val key = directory.resolve("key.pem")
        Files.writeString(certificate, JvmTlsCodecBackendTest.TEST_CERTIFICATE_PEM)
        Files.writeString(key, JvmTlsCodecBackendTest.TEST_PRIVATE_KEY_PEM)
        val ring = FunctionalUringFacade.create(this, 16)
        val descriptors = mutableListOf<Int>()
        var token = 0L
        var admitted = 0L
        var settled = 0L
        suspend fun submit(op: UringOp, fd: Int, bytes: ByteBuffer? = null, len: Int = bytes?.remaining() ?: 0, offset: Long = 0): Int {
            val id = ++token
            val sqe = UringSubmission(op, fd, 0, len, offset, userData = id, buffer = bytes)
            admitted++
            val result = ring.batchEnqueue(1 j { sqe })
            assertEquals(1, result.size)
            assertEquals(id, result[0].userData)
            settled++
            return result[0].res
        }
        suspend fun socket(): Int = submit(UringOp.SOCKET, 2, len = 6, offset = 1).also {
            assertTrue(it >= 0, "SOCKET failed: $it; ${ring.availability}")
            descriptors.add(it)
        }
        val clientElement = openTlsElement(
            TlsConfig(trustStore = certificate.toString().takeIf { trustCertificate }, protocols = s_[protocol],
                alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1], hostnameVerification = true),
            JvmTlsCodecBackend(), parentJob = coroutineContext[Job],
        )
        val serverElement = openTlsElement(
            TlsConfig(certificateFile = certificate.toString(), privateKeyFile = key.toString(), protocols = s_[protocol],
                alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1]),
            JvmTlsCodecBackend(), parentJob = coroutineContext[Job],
        )
        try {
            withTimeout(20_000) {
                val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port))
                val listener = socket()
                assertEquals(0, submit(UringOp.BIND, listener, address))
                assertEquals(0, submit(UringOp.LISTEN, listener, len = 8))
                val clientFd = socket()
                assertEquals(0, submit(UringOp.CONNECT, clientFd, address))
                var serverFd: Int
                do {
                    serverFd = submit(UringOp.ACCEPT, listener)
                    if (serverFd == -11) delay(1)
                } while (serverFd == -11)
                assertTrue(serverFd >= 0, "ACCEPT failed: $serverFd")
                descriptors.add(serverFd)

                val client = clientElement.clientEndpoint(peerName, port)
                val server = serverElement.serverEndpoint("127.0.0.1", port)
                val pending = mutableMapOf(clientFd to 0, serverFd to 0)
                val plaintext = mutableMapOf(clientFd to mutableListOf<Byte>(), serverFd to mutableListOf<Byte>())
                suspend fun emit(fd: Int, peer: Int, frames: TlsFrames) {
                    for (index in 0 until frames.size) {
                        val frame = frames[index]
                        when (frame.stage) {
                            TlsFlowStage.DOWNSTREAM_PLAINTEXT -> plaintext.getValue(fd).addAll(frame.payload.toArray().asList())
                            TlsFlowStage.UPSTREAM_CIPHERTEXT, TlsFlowStage.CLOSE_NOTIFY -> {
                                val bytes = ByteBuffer(frame.payload.toArray())
                                while (bytes.hasRemaining()) {
                                    val count = submit(UringOp.WRITE, fd, bytes, offset = -1)
                                    if (count == -11) delay(1) else {
                                        assertTrue(count > 0, "Ciphertext WRITE failed: $count")
                                        pending[peer] = pending.getValue(peer) + count
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                suspend fun receive(fd: Int, peer: Int, endpoint: TlsEndpoint) {
                    if (pending.getValue(fd) == 0) return
                    // Fragment records across many READ CQEs, including their five-byte header.
                    val bytes = ByteBuffer(minOf(113, pending.getValue(fd)))
                    val count = submit(UringOp.READ, fd, bytes, offset = -1)
                    if (count == -11) { delay(1); return }
                    assertTrue(count > 0, "Ciphertext READ failed or reached unexpected EOF: $count")
                    pending[fd] = pending.getValue(fd) - count
                    emit(fd, peer, endpoint.downstream(ByteSeries(bytes.array().copyOf(count))))
                }
                suspend fun drainCiphertext() {
                    while (pending.values.any { it > 0 }) {
                        receive(serverFd, clientFd, server)
                        receive(clientFd, serverFd, client)
                    }
                }

                emit(serverFd, clientFd, server.handshake())
                emit(clientFd, serverFd, client.handshake())
                drainCiphertext()
                assertTrue(client.isHandshakeComplete && server.isHandshakeComplete)
                assertEquals(protocol, client.session?.protocol)
                assertEquals(protocol, server.session?.protocol)

                val request = ByteArray(32769) { (it % 251).toByte() }
                val response = ByteArray(8193) { (250 - it % 251).toByte() }
                emit(clientFd, serverFd, client.upstream(ByteSeries(request)))
                drainCiphertext()
                assertContentEquals(request, plaintext.getValue(serverFd).toByteArray())
                emit(serverFd, clientFd, server.upstream(ByteSeries(response)))
                drainCiphertext()
                assertContentEquals(response, plaintext.getValue(clientFd).toByteArray())

                emit(clientFd, serverFd, client.close())
                while (pending.getValue(serverFd) > 0) receive(serverFd, clientFd, server)
                assertEquals(TlsConnectionState.CLOSED, server.flowState.lifecycle)
                emit(serverFd, clientFd, server.close())
                assertEquals(TlsConnectionState.CLOSED, client.flowState.lifecycle)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    clientElement.drain()
                    serverElement.drain()
                    assertEquals(ElementState.CLOSED, clientElement.state)
                    assertEquals(ElementState.CLOSED, serverElement.state)
                } finally {
                    try {
                        for (fd in descriptors.asReversed()) assertEquals(0, submit(UringOp.CLOSE, fd))
                    } finally {
                        ring.drain()
                        assertEquals(admitted, settled, "Every admitted socket/transport SQE must settle")
                        Files.deleteIfExists(key)
                        Files.deleteIfExists(certificate)
                        Files.deleteIfExists(directory)
                    }
                }
            }
        }
    }
}
