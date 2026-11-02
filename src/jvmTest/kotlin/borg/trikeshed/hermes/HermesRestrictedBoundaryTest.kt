package borg.trikeshed.hermes

import borg.trikeshed.agent.modelmux.Airgap
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.vm.Teleported
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behavioral probes of the restricted Python port, not a proof of OS-level isolation. */
class HermesRestrictedBoundaryTest {
    @Test
    fun guestKeepsVfsAccessButCannotReadHostFileSpawnShellOrConnectInAirgap() = runBlocking {
        val directory = Files.createTempDirectory("hermes-boundary-")
        val sentinel = directory.resolve("host-only.txt")
        val marker = directory.resolve("process-ran.txt")
        Files.writeString(sentinel, "host-only sentinel")
        try {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
                listener.soTimeout = 500
                val board = ConfixBlackboard.empty()
                val scope = CoroutineScope(coroutineContext + Airgap())
                HermesPythonPort(blackboard = board, pen = HermesPen(board), socketScope = scope).use { port ->
                    val sources = _m["entry" j ("entry.py" to "import socket")]
                    port.importInVm(port.inventorySources(sources), "entry")

                    val vfs = port.evalInVm("""
                        with open('/workspace/allowed.txt', 'w') as f:
                            f.write('inside')
                        open('/workspace/allowed.txt').read()
                    """.trimIndent())
                    assertEquals(Teleported.Str("inside"), vfs)
                    assertFails { port.evalInVm("open(${jsonOf(sentinel.toString())}).read()") }
                    assertEquals("host-only sentinel", Files.readString(sentinel))

                    val socketResult = port.evalInVm("""
                        import json, socket
                        direct = json.loads(host.call('socket', 'open', 2))
                        assert direct['verdict'] == 'socket-error' and direct['errno'] == 13
                        try:
                            socket.create_connection(('127.0.0.1', ${listener.localPort}), timeout=0.25)
                        except OSError as failure:
                            assert failure.errno == 13
                        else:
                            raise AssertionError('airgap socket connection was admitted')
                        True
                    """.trimIndent())
                    assertEquals(Teleported.Bool(true), socketResult)

                    // An import failure or denial is acceptable; a connection to the host is not.
                    runCatching { port.evalInVm("""
                        import _socket
                        raw = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
                        try:
                            raw.settimeout(0.25)
                            raw.connect(('127.0.0.1', ${listener.localPort}))
                        finally:
                            raw.close()
                    """.trimIndent()) }
                    assertFailsWith<SocketTimeoutException> { listener.accept().close() }

                    val shellPath = marker.toString().replace("'", "'\\''")
                    val command = "printf marker > '$shellPath'"
                    val shell = runCatching { port.evalInVm("import os\nos.system(${jsonOf(command)})") }
                    assertTrue(shell.isFailure || shell.getOrNull() != Teleported.Num(0L), "shell reported success")
                    assertFalse(Files.exists(marker), "guest command created a host file")
                }
            }
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(sentinel)
            Files.deleteIfExists(directory)
        }
    }
}
