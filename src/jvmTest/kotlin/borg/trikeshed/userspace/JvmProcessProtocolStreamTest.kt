package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JvmProcessProtocolStreamTest {
    private suspend fun bytes(read: suspend (ByteArray) -> Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private fun command(mode: String): List<String> {
        // The Gradle worker's java.class.path need not contain the tested classes.
        val classpath = listOf(
            JvmProcessProtocolStreamChild::class.java, JvmProcessProtocolStream::class.java,
            ProcessProtocolStream::class.java, Unit::class.java, Job::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        return listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            JvmProcessProtocolStreamChild::class.java.name, mode)
    }

    private suspend fun child(mode: String, test: suspend (JvmProcessProtocolPipes) -> Unit) {
        val pipes = JvmProcessProtocolPipes.open({ _, _ -> command(mode) }, emptyMap())
        try { withTimeout(15_000) { test(pipes) } }
        finally { pipes.close() }
    }

    private suspend fun exit(pipes: JvmProcessProtocolPipes): String {
        val code = pipes.awaitExit()
        val stderr = bytes { pipes.read(ProcessOutput.Stderr, it) }.decodeToString()
        assertEquals(0, code, stderr)
        assertFalse(pipes.isAlive)
        return stderr
    }

    /** The raw peer does not use the adapter under test or the authenticated process transport. */
    private suspend fun sockets(test: suspend (JvmProcessProtocolStream, Socket, Socket) -> Unit) {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = Socket()
        var owned: Socket? = null
        var stream: JvmProcessProtocolStream? = null
        try {
            listener.soTimeout = 5_000
            peer.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), listener.localPort), 5_000)
            peer.soTimeout = 5_000
            val accepted = listener.accept().also { owned = it }
            listener.close()
            val endpoint = JvmProcessProtocolStream.socket(accepted).also { stream = it }
            withTimeout(10_000) { test(endpoint, accepted, peer) }
        } finally {
            withContext(NonCancellable) {
                // Close the raw peer even on an assertion failure so native test
                // effects cannot survive the fixture. Never touch runner stdio.
                try { peer.close() }
                finally {
                    try { stream?.closeProtocol() }
                    finally {
                        try { owned?.close() }
                        finally { listener.close() }
                    }
                }
            }
        }
    }

    @Test fun stdioRoundTripsFragmentedUtf8BytesAndRealEof(): Unit = runBlocking {
        child("echo") { pipes ->
            val message = "protocol: λ🙂 café\r\n".repeat(4096).encodeToByteArray()
            val sending = async {
                pipes.writeProtocol(message)
                pipes.closeProtocolInput()
            }
            assertContentEquals(message, bytes(pipes::readProtocol))
            sending.await()
            assertEquals(-1, pipes.readProtocol(ByteArray(1)))
            assertEquals("", exit(pipes))
        }
    }

    @Test fun stdioOutputHalfClosePreservesInput(): Unit = runBlocking {
        child("half-close") { pipes ->
            // Child stdout reaches EOF before it has received any stdin bytes.
            assertEquals("output: λ🙂", bytes(pipes::readProtocol).decodeToString())
            pipes.writeProtocol("input after half-close: café🙂".encodeToByteArray())
            pipes.closeProtocolInput()
            assertEquals("input received\n", exit(pipes))
        }
    }

    @Test fun stdioCancellationJoinsBlockedReadWithoutParentEof(): Unit = runBlocking {
        child("cancel-read") { pipes ->
            // Keep the parent's stdin pipe open: no EOF is supplied to rescue
            // the child's blocked read. The adapter must revoke its descriptor.
            assertEquals("read pending", bytes(pipes::readProtocol).decodeToString())
            assertEquals("read cancelled\n", exit(pipes))
        }
    }

    @Test fun stdioCancellationJoinsBlockedWriteWithoutParentReading(): Unit = runBlocking {
        child("cancel-write") { pipes ->
            // Do not consume stdout until cancellation has finished. A finite
            // pipe cannot accept the fixture's entire write without a reader.
            assertEquals("write cancelled\n",
                bytes { pipes.read(ProcessOutput.Stderr, it) }.decodeToString())
            val written = bytes(pipes::readProtocol)
            assertTrue(written.isNotEmpty())
            assertTrue(written.size < 16 * 1024 * 1024)
            assertEquals("", exit(pipes))
        }
    }

    @Test fun stdioEndpointCloseJoinsReadAndIsNotEof(): Unit = runBlocking {
        child("close-read") { pipes ->
            assertEquals("read pending", bytes(pipes::readProtocol).decodeToString())
            assertEquals("read closed\n", exit(pipes))
        }
    }

    @Test fun socketUtf8AndOutputHalfClosePreserveInput(): Unit = runBlocking {
        sockets { stream, owned, peer ->
            val output = "output: λ🙂\r\ncafé".encodeToByteArray()
            stream.writeProtocol(output)
            stream.closeProtocolInput()
            stream.closeProtocolInput()
            assertTrue(owned.isOutputShutdown)
            assertFalse(owned.isInputShutdown)
            assertFalse(owned.isClosed)
            assertContentEquals(output, withContext(Dispatchers.IO) { peer.getInputStream().readAllBytes() })
            assertFailsWith<IOException> { stream.writeProtocol(byteArrayOf(1)) }

            val input = "input after half-close: café🙂".encodeToByteArray()
            withContext(Dispatchers.IO) {
                // Split UTF8 code points deliberately; the SPI must not decode.
                input.forEach { peer.getOutputStream().write(it.toInt() and 255) }
                peer.getOutputStream().flush()
                peer.shutdownOutput()
            }
            assertContentEquals(input, bytes(stream::readProtocol))
            assertEquals(-1, stream.readProtocol(ByteArray(1)))
            stream.closeProtocol()
            stream.closeProtocol()
            assertTrue(owned.isClosed)
            assertFalse(peer.isClosed)
            assertFailsWith<IOException> { stream.readProtocol(ByteArray(1)) }
        }
    }

    @Test fun socketCancellationJoinsBlockedReadAndRejectsSecondReader(): Unit = runBlocking {
        sockets { stream, owned, peer ->
            val reading = async(start = CoroutineStart.UNDISPATCHED) { stream.readProtocol(ByteArray(1)) }
            try {
                delay(100)
                assertFalse(reading.isCompleted, "An open idle socket is not EOF")
                assertFailsWith<IllegalStateException> { stream.readProtocol(ByteArray(1)) }
                withTimeout(5_000) { reading.cancelAndJoin() }
                assertFailsWith<CancellationException> { reading.await() }
                assertTrue(owned.isClosed)
                assertEquals(-1, withContext(Dispatchers.IO) { peer.getInputStream().read() })
                assertFailsWith<IOException> { stream.readProtocol(ByteArray(1)) }
                stream.closeProtocol()
                stream.closeProtocol()
            } finally {
                withContext(NonCancellable) {
                    stream.closeProtocol()
                    reading.cancelAndJoin()
                }
            }
        }
    }

    @Test fun socketWriteCancellationAlsoJoinsConcurrentNativeRead(): Unit = runBlocking {
        sockets { stream, owned, peer ->
            owned.sendBufferSize = 1024
            peer.receiveBufferSize = 1024
            val reading = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { stream.readProtocol(ByteArray(1)) }
            }
            val writing = async(start = CoroutineStart.UNDISPATCHED) {
                stream.writeProtocol(ByteArray(16 * 1024 * 1024) { 42 })
            }
            try {
                assertEquals(42, withContext(Dispatchers.IO) { peer.getInputStream().read() })
                delay(100)
                assertFalse(writing.isCompleted, "The unread peer must apply backpressure")
                assertFalse(reading.isCompleted)
                assertFailsWith<IllegalStateException> { stream.writeProtocol(byteArrayOf(1)) }
                withTimeout(5_000) { writing.cancelAndJoin() }
                assertFailsWith<CancellationException> { writing.await() }
                assertIs<IOException>(reading.await().exceptionOrNull(), "Local revocation is not remote EOF")
                assertTrue(owned.isClosed)
                assertFailsWith<IOException> { stream.writeProtocol(byteArrayOf(1)) }
            } finally {
                withContext(NonCancellable) {
                    stream.closeProtocol()
                    writing.cancelAndJoin()
                    reading.cancelAndJoin()
                }
            }
        }
    }

    @Test fun socketEndpointCloseJoinsNativeReadAndIsIdempotent(): Unit = runBlocking {
        sockets { stream, owned, _ ->
            assertFailsWith<IllegalArgumentException> { stream.readProtocol(ByteArray(0)) }
            val reading = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { stream.readProtocol(ByteArray(1)) }
            }
            try {
                delay(100)
                assertFalse(reading.isCompleted)
                withTimeout(5_000) {
                    val first = async { stream.closeProtocol() }
                    val second = async { stream.closeProtocol() }
                    first.await()
                    second.await()
                }
                assertIs<IOException>(reading.await().exceptionOrNull())
                assertTrue(owned.isClosed)
                stream.closeProtocol()
                assertFailsWith<IOException> { stream.readProtocol(ByteArray(1)) }
                assertFailsWith<IOException> { stream.writeProtocol(byteArrayOf(1)) }
            } finally {
                withContext(NonCancellable) {
                    stream.closeProtocol()
                    reading.cancelAndJoin()
                }
            }
        }
    }

    @Test fun socketConstructionFailureClosesTransferredHandle() {
        val socket = Socket()
        assertFailsWith<IOException> { JvmProcessProtocolStream.socket(socket) }
        assertTrue(socket.isClosed)
    }
}

/** Owns fd 0/1 only inside this short-lived child JVM, never in the test runner. */
object JvmProcessProtocolStreamChild {
    private suspend fun input(stream: ProcessProtocolStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(3)
        while (true) {
            val count = stream.readProtocol(buffer)
            if (count < 0) return bytes.toByteArray()
            bytes.write(buffer, 0, count)
        }
    }

    private suspend fun closed(stream: ProcessProtocolStream) {
        check(runCatching { stream.readProtocol(ByteArray(1)) }.exceptionOrNull() is IOException)
        check(runCatching { stream.writeProtocol(byteArrayOf(1)) }.exceptionOrNull() is IOException)
    }

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val stream = JvmProcessProtocolStream.stdio()
        try {
            when (args.single()) {
                "echo" -> {
                    val buffer = ByteArray(257)
                    while (true) {
                        val count = stream.readProtocol(buffer)
                        if (count < 0) break
                        stream.writeProtocol(buffer.copyOf(count))
                    }
                    stream.closeProtocolInput()
                    stream.closeProtocolInput()
                    check(stream.readProtocol(ByteArray(1)) == -1)
                }
                "half-close" -> {
                    stream.writeProtocol("output: λ🙂".encodeToByteArray())
                    stream.closeProtocolInput()
                    stream.closeProtocolInput()
                    check(runCatching { stream.writeProtocol(byteArrayOf(1)) }.exceptionOrNull() is IOException)
                    check(input(stream).decodeToString() == "input after half-close: café🙂")
                    System.err.println("input received")
                }
                "cancel-read" -> supervisorScope {
                    val reading = async(start = CoroutineStart.UNDISPATCHED) { stream.readProtocol(ByteArray(1)) }
                    delay(100)
                    check(!reading.isCompleted)
                    stream.writeProtocol("read pending".encodeToByteArray())
                    withTimeout(5_000) { reading.cancelAndJoin() }
                    check(reading.isCancelled && reading.isCompleted)
                    closed(stream)
                    System.err.println("read cancelled")
                }
                "cancel-write" -> supervisorScope {
                    val writing = async(start = CoroutineStart.UNDISPATCHED) {
                        stream.writeProtocol(ByteArray(16 * 1024 * 1024) { 42 })
                    }
                    delay(100)
                    check(!writing.isCompleted)
                    withTimeout(5_000) { writing.cancelAndJoin() }
                    check(writing.isCancelled && writing.isCompleted)
                    closed(stream)
                    System.err.println("write cancelled")
                }
                "close-read" -> supervisorScope {
                    val reading = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { stream.readProtocol(ByteArray(1)) }
                    }
                    delay(100)
                    check(!reading.isCompleted)
                    stream.writeProtocol("read pending".encodeToByteArray())
                    withTimeout(5_000) { stream.closeProtocol() }
                    check(reading.await().exceptionOrNull() is IOException)
                    closed(stream)
                    System.err.println("read closed")
                }
                else -> error("Unknown fixture mode: ${args.single()}")
            }
        } finally {
            stream.closeProtocol()
            stream.closeProtocol()
        }
        closed(stream)
    }
}
