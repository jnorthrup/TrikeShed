package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.PythonSocketElement
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.vm.Teleported
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlin.test.*

class HermesSocketProviderTest {
    private fun server() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
    private fun sources(source: String, name: String = "entry") = mapOf(name to ("$name.py" to source))
    private fun port(scope: CoroutineScope, board: ConfixBlackboard = ConfixBlackboard.empty()) =
        HermesPythonPort(blackboard = board, pen = HermesPen(board, contextId = { "socket-fixture" }), socketScope = scope)

    @Test
    fun realGuestTcpCarriesBinaryChunksAndFileOwnership() = runBlocking(Dispatchers.IO) {
        val board = ConfixBlackboard.empty()
        server().use { listener ->
            val peer = async {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val payload = socket.getInputStream().readAllBytes()
                    assertContentEquals(ByteArray(131_072) { it.toByte() }, payload)
                    socket.getOutputStream().write(byteArrayOf(0, -128, -1, 42))
                }
            }
            port(this, board).use { port ->
                val inventory = port.inventorySources(sources("import socket"))
                assertEquals(HermesModuleStatus.READY, inventory.modules.getValue("entry").status)
                assertTrue(inventory.modules.getValue("socket").sleeved)
                port.importInVm(inventory, "entry")
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import socket
                    connection = socket.create_connection(('localhost', ${listener.localPort}), timeout=2)
                    assert connection.getpeername() == ('127.0.0.1', ${listener.localPort})
                    assert connection.getsockname()[1] > 0
                    connection.sendall(bytes(range(256)) * 512)
                    connection.shutdown(socket.SHUT_WR)
                    stream = connection.makefile('rb')
                    connection.close()
                    assert stream.read() == bytes([0, 128, 255, 42])
                    stream.close()
                    try: connection.recv(1)
                    except OSError as error: assert error.errno == 9
                    else: raise AssertionError('closed descriptor remained available')
                    True
                """.trimIndent()))
            }
            withTimeout(5_000) { peer.await() }
        }
        val receipts = board.snapshot().values.filterKeys { it.startsWith("hermes/python/pen/socket/") }.values
        assertTrue(receipts.size >= 10)
        assertTrue(receipts.all { (it as Map<*, *>)["contextId"] == "socket-fixture" })
        assertTrue(receipts.all { (it as Map<*, *>)["ok"] == true })
    }

    @Test
    fun readTimeoutCancelsItsPollAndKeepsSocketUsable() = runBlocking(Dispatchers.IO) {
        server().use { listener ->
            val peer = async {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    assertEquals(7, socket.getInputStream().read())
                    socket.getOutputStream().write(255)
                }
            }
            port(this).use { port ->
                port.importInVm(port.inventorySources(sources("import socket")), "entry")
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import socket
                    with socket.create_connection(('127.0.0.1', ${listener.localPort}), timeout=2) as connection:
                        connection.settimeout(0.05)
                        try: connection.recv(1)
                        except TimeoutError as error: assert error.errno == 110
                        else: raise AssertionError('idle read did not time out')
                        connection.setblocking(False)
                        try: connection.recv(1)
                        except BlockingIOError as error: assert error.errno == 11
                        else: raise AssertionError('idle nonblocking read did not report EAGAIN')
                        connection.settimeout(2)
                        connection.sendall(bytes([7]))
                        assert connection.recv(1) == bytes([255])
                    True
                """.trimIndent()))
            }
            withTimeout(5_000) { peer.await() }
        }
    }

    @Test
    fun egressRefusalPrecedesDnsAndNativeCapabilitiesRemainRefused() = runBlocking(Dispatchers.IO) {
        var resolutions = 0
        val operations = object : ChannelOperations by JvmChannelOperations() {
            override fun resolve(host: String): ByteArray? { resolutions++; error("denied host reached DNS") }
        }
        val board = ConfixBlackboard.empty()
        port(CoroutineScope(coroutineContext + operations), board).use { port ->
            port.importInVm(port.inventorySources(sources("import socket")), "entry")
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                import socket
                try: socket.getaddrinfo('unleased-hermes-socket.invalid', 443)
                except OSError as error: assert error.errno == 13
                else: raise AssertionError('unleased egress admitted')
                with socket.socket() as connection:
                    for operation in (lambda: connection.fileno(), lambda: connection.bind(('127.0.0.1', 0)),
                                      lambda: connection.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)):
                        try: operation()
                        except OSError as error: assert error.errno == 95
                        else: raise AssertionError('unimplemented native capability pretended to work')
                try: socket.socket(type=socket.SOCK_DGRAM)
                except OSError as error: assert error.errno == 95
                else: raise AssertionError('unleased datagram socket created')
                True
            """.trimIndent()))
        }
        assertEquals(0, resolutions)
        val refusal = board.get("hermes/python/pen/socket/1") as Map<*, *>
        assertEquals(false, refusal["ok"])
        assertEquals("socket-error", refusal["verdict"])
    }

    @Test
    fun failedCandidateClosesItsSocketsAndKeepsTheOldVmUntilReplacement() = runBlocking(Dispatchers.IO) {
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        try {
            server().use { original ->
                server().use { candidate ->
                    port(scope).use { port ->
                        port.importInVm(port.inventorySources(sources("""
                            import socket
                            connection = socket.create_connection(('127.0.0.1', ${original.localPort}))
                        """.trimIndent(), "original")), "original")
                        original.accept().use { retained ->
                            retained.soTimeout = 5_000
                            assertFails {
                                port.importInVm(port.inventorySources(sources("""
                                    import socket
                                    connection = socket.create_connection(('127.0.0.1', ${candidate.localPort}))
                                    raise RuntimeError('candidate failure')
                                """.trimIndent(), "candidate")), "candidate")
                            }
                            candidate.accept().use { abandoned ->
                                abandoned.soTimeout = 5_000
                                assertEquals(-1, abandoned.getInputStream().read(), "failed import closes its TCP descriptors")
                            }
                            assertTrue(port.vmStarted)
                            port.evalInVm("import original\noriginal.connection.sendall(b'A')\nTrue")
                            assertEquals(65, retained.getInputStream().read(), "old VM survived candidate failure")
                            port.importInVm(port.inventorySources(sources("import socket")), "entry")
                            assertEquals(-1, retained.getInputStream().read(), "successful replacement closes previous VM sockets")
                        }
                    }
                }
            }
            assertTrue(owner.children.none(), "closed VMs leave no socket workers or ring consumers")
        } finally { owner.complete(); withTimeout(5_000) { owner.join() } }
    }

    @Test
    fun socketInjectionRequiresOwnershipAndRejectsSourceReplacement() = runBlocking(Dispatchers.IO) {
        HermesPythonPort().use { unleased ->
            assertEquals(HermesModuleStatus.BLOCKED_NATIVE,
                unleased.inventorySources(sources("import socket")).modules.getValue("entry").status)
        }
        port(this).use { leased ->
            assertFailsWith<IllegalArgumentException> { leased.inventorySources(sources("pass", "socket")) }
            assertFailsWith<IllegalArgumentException> {
                leased.inventorySources(sources("import socket"), sleeveSources = sources("pass", "socket"))
            }
        }
        Unit
    }

    @Test
    fun sslClientUsesOnlyTheOwnedTransportAndCannotDisableVerification() = runBlocking(Dispatchers.IO) {
        HermesPythonPort().use { unleased ->
            assertEquals(HermesModuleStatus.BLOCKED_NATIVE,
                unleased.inventorySources(sources("import ssl")).modules.getValue("entry").status)
        }
        port(this).use { leased ->
            val inventory = leased.inventorySources(sources("import ssl"))
            assertEquals(HermesModuleStatus.READY, inventory.modules.getValue("entry").status)
            assertTrue(inventory.modules.getValue("ssl").sleeved)
            leased.importInVm(inventory, "entry")
            assertEquals(Teleported.Bool(true), leased.evalInVm("""
                import ssl, socket
                context = ssl.create_default_context()
                assert context.check_hostname is True
                assert context.verify_mode == ssl.CERT_REQUIRED
                context.set_alpn_protocols(['http/1.1'])
                for change in (lambda: setattr(context, 'check_hostname', False),
                               lambda: setattr(context, 'verify_mode', ssl.CERT_NONE),
                               lambda: context.load_verify_locations(cafile='/workspace/unapproved.pem'),
                               lambda: context.set_alpn_protocols(['h2']),
                               lambda: ssl.create_default_context(cadata='unapproved'),
                               lambda: ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)):
                    try: change()
                    except (ValueError, OSError): pass
                    else: raise AssertionError('TLS policy override admitted')
                with socket.socket() as raw:
                    try: context.wrap_socket(raw)
                    except ValueError: pass
                    else: raise AssertionError('TLS without peer identity admitted')
                True
            """.trimIndent()))
            assertFailsWith<IllegalArgumentException> { leased.inventorySources(sources("pass", "ssl")) }
            assertFailsWith<IllegalArgumentException> {
                leased.inventorySources(sources("import ssl"), sleeveSources = sources("pass", "ssl"))
            }
        }
        Unit
    }

    @Test
    fun drainFinishesAcceptedReadBeforeClosingThePeer() = runBlocking(Dispatchers.IO) {
        val owner = SupervisorJob(coroutineContext[Job])
        val sockets = PythonSocketElement(CoroutineScope(coroutineContext + owner))
        try {
            server().use { listener ->
                val id = sockets.socket(2)
                sockets.connect(id, sockaddrIpv4(byteArrayOf(127, 0, 0, 1), listener.localPort), 2_000)
                listener.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val reading = async(start = CoroutineStart.UNDISPATCHED) { sockets.read(id, 1, 2_000) }
                    val draining = async { sockets.drain() }
                    delay(30)
                    assertFalse(reading.isCompleted)
                    assertFalse(draining.isCompleted, "normal drain must not cancel the admitted read")
                    peer.getOutputStream().write(42)
                    withTimeout(5_000) {
                        assertContentEquals(byteArrayOf(42), reading.await())
                        draining.await()
                    }
                    assertEquals(-1, peer.getInputStream().read())
                    assertFails { sockets.socket(2) }
                }
            }
            assertTrue(owner.children.none())
        } finally { sockets.drain(); owner.complete(); withTimeout(5_000) { owner.join() } }
    }

    @Test
    fun ownerAbortSettlesActiveAndQueuedCallsAndClosesDescriptors() = runBlocking(Dispatchers.IO) {
        val owner = SupervisorJob(coroutineContext[Job])
        val sockets = PythonSocketElement(CoroutineScope(coroutineContext + owner))
        try {
            server().use { listener ->
                val id = sockets.socket(2)
                sockets.connect(id, sockaddrIpv4(byteArrayOf(127, 0, 0, 1), listener.localPort), 2_000)
                listener.accept().use { peer ->
                    peer.soTimeout = 5_000
                    val reading = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sockets.read(id, 1, 2_000) } }
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { sockets.socket(2) } }
                    delay(30)
                    assertFalse(reading.isCompleted)
                    assertFalse(queued.isCompleted)
                    owner.cancel()
                    withTimeout(5_000) {
                        assertTrue(reading.await().isFailure)
                        assertTrue(queued.await().isFailure)
                        sockets.drain()
                        owner.join()
                    }
                    assertEquals(-1, peer.getInputStream().read())
                }
            }
            assertTrue(owner.children.none())
        } finally { owner.cancel(); sockets.drain(); withTimeout(5_000) { owner.join() } }
    }
}
