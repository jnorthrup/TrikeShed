package borg.trikeshed.hermes

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.vm.Teleported
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@Timeout(30)
class HermesTlsTest {
    @TempDir lateinit var directory: Path

    private fun config(protocol: TlsProtocol = TlsProtocol.TLS13, trust: Boolean = true): TlsConfig {
        val certificate = directory.resolve("server.pem")
        Files.writeString(certificate, JvmTlsCodecBackendTest.TEST_CERTIFICATE_PEM)
        return TlsConfig(trustStore = certificate.toString().takeIf { trust }, protocols = s_[protocol],
            alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1])
    }

    private fun serverContext(trustFixture: Boolean = false): SSLContext {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(JvmTlsCodecBackendTest.TEST_CERTIFICATE_PEM.toByteArray()))
        val pem = JvmTlsCodecBackendTest.TEST_PRIVATE_KEY_PEM.lineSequence().filterNot { it.startsWith("---") }.joinToString("") { it.trim() }
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("fixture", key, CharArray(0), arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val trust = if (trustFixture) TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers else null
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trust, null) }
    }

    private fun server(protocol: TlsProtocol = TlsProtocol.TLS13): SSLServerSocket {
        return (serverContext().serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply {
            enabledProtocols = arrayOf(if (protocol == TlsProtocol.TLS13) "TLSv1.3" else "TLSv1.2")
            soTimeout = 5_000
        }
    }

    private suspend fun PythonSocketElement.connected(port: Int): Int = socket(2).also {
        connect(it, sockaddrIpv4(byteArrayOf(127, 0, 0, 1), port), 2_000)
    }

    @Test fun tls13PayloadAndOwnedDrain() = exchange(TlsProtocol.TLS13)
    @Test fun tls12PayloadAndOwnedDrain() = exchange(TlsProtocol.TLS12)

    private fun exchange(protocol: TlsProtocol) = runBlocking(Dispatchers.IO) {
        val payload = ByteArray(65_536) { it.toByte() }
        val response = ByteArray(40_000) { (it * 3).toByte() }
        server(protocol).use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    assertContentEquals(payload, socket.inputStream.readNBytes(payload.size))
                    socket.outputStream.write(response)
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                val session = sockets.tls(id, "localhost", listener.localPort, 5_000, config(protocol), JvmTlsCodecBackend())
                assertTrue(session.verified)
                assertEquals(protocol, session.protocol)
                assertEquals(payload.size, sockets.write(id, payload, 5_000))
                val received = ArrayList<Byte>()
                while (received.size < response.size) received.addAll(sockets.read(id, 113, 5_000).asList())
                assertContentEquals(response, received.toByteArray())
                // Deliberately leave the socket open: the owner's drain must close TLS and TCP.
            } finally { sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun untrustedPeerFailsAndClosesDescriptor() = refused(trust = false)
    @Test fun wrongHostnameFailsAndClosesDescriptor() = refused(hostname = "wrong.invalid")

    private fun refused(trust: Boolean = true, hostname: String = "localhost") = runBlocking(Dispatchers.IO) {
        server().use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    runCatching { socket.startHandshake(); socket.inputStream.read() }
                }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                assertFailsWith<SSLException> { sockets.tls(id, hostname, listener.localPort, 5_000, config(trust = trust), JvmTlsCodecBackend()) }
                assertEquals(9, assertFailsWith<PythonSocketElement.SocketError> { sockets.name(id, true) }.errno)
            } finally { sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun idleTlsReadTimeoutPreservesTheVerifiedConnection() = runBlocking(Dispatchers.IO) {
        val respond = CompletableDeferred<Unit>()
        server().use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    respond.await()
                    socket.outputStream.write(42)
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                sockets.tls(id, "localhost", listener.localPort, 5_000, config(), JvmTlsCodecBackend())
                assertEquals(110, assertFailsWith<PythonSocketElement.SocketError> { sockets.read(id, 1, 50) }.errno)
                respond.complete(Unit)
                assertContentEquals(byteArrayOf(42), sockets.read(id, 1, 5_000))
            } finally { respond.complete(Unit); sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun handshakeDeadlineClosesTheUnusableSocket() = runBlocking(Dispatchers.IO) {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val peer = async {
                listener.accept().use { socket -> socket.soTimeout = 5_000; assertTrue(socket.inputStream.readAllBytes().isNotEmpty()) }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                assertEquals(110, assertFailsWith<PythonSocketElement.SocketError> {
                    sockets.tls(id, "localhost", listener.localPort, 100, config(), JvmTlsCodecBackend())
                }.errno)
                assertEquals(9, assertFailsWith<PythonSocketElement.SocketError> { sockets.name(id, true) }.errno)
            } finally { sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun guestTlsCapabilityUsesTheHostTrustLeaseAndExistingSocketFileWrapper() = runBlocking(Dispatchers.IO) {
        server().use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    assertEquals("ping", socket.inputStream.readNBytes(4).decodeToString())
                    socket.outputStream.write("pong\n".encodeToByteArray())
                    assertEquals(-1, socket.inputStream.read())
                }
            }
            PythonSocketProvider(this, config()).use { provider ->
                GraalBtrfsSupervisor("tls-fixture", VmFacet.GRAAL_PYTHON).use { guest ->
                    guest.delegate("socket") { args -> Teleported.Str(jsonOf(provider.call(args).b)) }
                    guest.eval(PythonSocketProvider.source, "host-owned-socket.py")
                    assertEquals(Teleported.Bool(true), guest.eval("""
                        connection = create_connection(('localhost', ${listener.localPort}), timeout=5)
                        session = connection._start_tls('localhost')
                        assert session['verified'] and session['protocol'] == 'TLS13'
                        assert session['alpn'] is None  # offered HTTP/1.1 is not a negotiated protocol
                        connection.sendall(b'ping')
                        stream = connection.makefile('rb')
                        connection.close()
                        assert stream.readline() == b'pong\n'
                        stream.close()
                        True
                    """.trimIndent(), "tls-guest.py"))
                }
            }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun guestStdlibHttpsUsesVerifiedTlsAndNegotiatedAlpn() = guestHttps(TlsProtocol.TLS13)
    @Test fun guestStdlibHttpsAlsoPreservesTls12() = guestHttps(TlsProtocol.TLS12)

    private fun guestHttps(protocol: TlsProtocol) = runBlocking(Dispatchers.IO) {
        val payload = ByteArray(131_072) { it.toByte() }
        server(protocol).use { listener ->
            listener.sslParameters = listener.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.inputStream.bufferedReader()
                    assertEquals("GET /curation HTTP/1.1", reader.readLine())
                    while (reader.readLine().isNotEmpty()) { }
                    assertEquals("http/1.1", socket.applicationProtocol)
                    socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\n\r\n".encodeToByteArray())
                    socket.outputStream.write(payload)
                    assertEquals(-1, socket.inputStream.read(), "client close drains TLS close_notify")
                }
            }
            val board = ConfixBlackboard.empty()
            val trust = serverContext(trustFixture = true)
            val backend = JvmTlsCodecBackend { profile, state ->
                trust.createSSLEngine(state.route.b.a, state.route.b.b).apply {
                    useClientMode = true
                    sslParameters = sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        applicationProtocols = profile.alpnProtocols.view.map { it.wireName }.toTypedArray()
                    }
                }
            }
            HermesPythonPort(board, pen = HermesPen(board), socketScope = CoroutineScope(coroutineContext + backend)).use { port ->
                val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to "import ssl\nimport http.client")))
                port.importInVm(inventory, "entry")
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import http.client, ssl, socket
                    originals = []
                    create_connection = socket.create_connection
                    def connected(*args, **kwargs):
                        raw = create_connection(*args, **kwargs)
                        originals.append(raw)
                        return raw
                    socket.create_connection = connected
                    connection = http.client.HTTPSConnection('localhost', ${listener.localPort}, timeout=5)
                    try:
                        connection.request('GET', '/curation')
                        assert isinstance(connection.sock, ssl.SSLSocket)
                        assert connection.sock.version() == '${if (protocol == TlsProtocol.TLS13) "TLSv1.3" else "TLSv1.2"}'
                        assert connection.sock.selected_alpn_protocol() == 'http/1.1'
                        assert len(originals) == 1 and originals[0]._closed
                        try: originals[0].recv(1)
                        except OSError as error: assert error.errno == 9
                        else: raise AssertionError('TLS left the original socket usable')
                        originals[0].close()  # ownership moved; this must not close TLS
                        response = connection.getresponse()
                        assert response.status == 200
                        assert response.read() == bytes(range(256)) * 512
                    finally:
                        connection.close()
                    True
                """.trimIndent()))
            }
            peer.await()
            val tls = board.snapshot().values.values.filterIsInstance<Map<*, *>>().single { it["operation"] == "tls" }
            assertEquals(true, tls["ok"])
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun guestSslCertificateFailureIsTypedAndClosesTheOriginalSocket() = runBlocking(Dispatchers.IO) {
        server().use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    assertFails { socket.startHandshake(); socket.inputStream.read() }
                }
            }
            val board = ConfixBlackboard.empty()
            HermesPythonPort(board, pen = HermesPen(board), socketScope = this).use { port ->
                port.importInVm(port.inventorySources(mapOf("entry" to ("entry.py" to "import ssl"))), "entry")
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import ssl, socket
                    raw = socket.create_connection(('localhost', ${listener.localPort}), timeout=5)
                    try:
                        ssl.create_default_context().wrap_socket(raw, server_hostname='localhost')
                    except ssl.SSLCertVerificationError:
                        assert raw._closed
                        try: raw.recv(1)
                        except OSError as error: assert error.errno == 9
                        else: raise AssertionError('certificate failure left a usable descriptor')
                    else: raise AssertionError('untrusted peer was admitted')
                    True
                """.trimIndent()))
            }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun disablingHostnameVerificationIsRejectedBeforeTlsAdmission() = runBlocking(Dispatchers.IO) {
        val sockets = PythonSocketElement(this)
        try {
            val id = sockets.socket(2)
            assertFailsWith<IllegalArgumentException> {
                sockets.tls(id, "localhost", 443, 1_000, config().copy(hostnameVerification = false), JvmTlsCodecBackend())
            }
        } finally { sockets.drain() }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun authenticatedPeerClosureReturnsEof() = runBlocking(Dispatchers.IO) {
        server().use { listener ->
            val peer = async {
                (listener.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.outputStream.write(42)
                }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                sockets.tls(id, "localhost", listener.localPort, 5_000, config(), JvmTlsCodecBackend())
                assertContentEquals(byteArrayOf(42), sockets.read(id, 1, 5_000))
                assertContentEquals(byteArrayOf(), sockets.read(id, 1, 5_000))
            } finally { sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }

    @Test fun tcpEofWithoutCloseNotifyIsTruncationAndClosesTheDescriptor() = runBlocking(Dispatchers.IO) {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 5_000
            val context = serverContext()
            val peer = async {
                listener.accept().use { raw ->
                    val tls = context.socketFactory.createSocket(raw, "localhost", raw.port, false) as SSLSocket
                    try {
                        tls.useClientMode = false
                        tls.soTimeout = 5_000
                        tls.startHandshake()
                        tls.outputStream.write(42)
                        raw.close() // Deliberately omit the authenticated TLS close_notify.
                    } finally { runCatching { tls.close() } }
                }
            }
            val sockets = PythonSocketElement(this)
            try {
                val id = sockets.connected(listener.localPort)
                sockets.tls(id, "localhost", listener.localPort, 5_000, config(), JvmTlsCodecBackend())
                assertContentEquals(byteArrayOf(42), sockets.read(id, 1, 5_000))
                assertEquals(71, assertFailsWith<PythonSocketElement.SocketError> { sockets.read(id, 1, 5_000) }.errno)
                assertEquals(9, assertFailsWith<PythonSocketElement.SocketError> { sockets.name(id, true) }.errno)
            } finally { sockets.drain() }
            peer.await()
        }
        assertEquals(0, coroutineContext[Job]!!.children.count())
    }
}
