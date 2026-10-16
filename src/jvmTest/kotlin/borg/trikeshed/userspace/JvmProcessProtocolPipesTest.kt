package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JvmProcessProtocolPipesTest {
    @Test fun revocationReleasesObservedDescendantsBeforeReturning(): Unit = runBlocking {
        for (grace in listOf(0L, 250L)) descendantCleanup { it.revoke(grace) }
    }

    @Test fun closingLivePipesReleasesObservedDescendants(): Unit = runBlocking {
        descendantCleanup { it.close() }
    }

    @Test fun cancellingNativeReadersReleasesObservedDescendants(): Unit = runBlocking {
        descendantCleanup { pipes ->
            val owner = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + owner)
            val read = scope.async(start = CoroutineStart.UNDISPATCHED) { pipes.readProtocol(ByteArray(1)) }
            val stderr = scope.async(start = CoroutineStart.UNDISPATCHED) { pipes.read(ProcessOutput.Stderr, ByteArray(1)) }
            delay(100)
            assertFalse(read.isCompleted || stderr.isCompleted)
            owner.cancelAndJoin()
            assertTrue(read.isCompleted && stderr.isCompleted)
        }
    }

    private suspend fun descendantCleanup(release: suspend (JvmProcessProtocolPipes) -> Unit) {
        val pipes = JvmProcessProtocolPipes.open({ port, token -> command("descendants", port, token) }, emptyMap())
        val children = mutableListOf<ProcessHandle>()
        try {
            repeat(2) {
                val line = ByteArrayOutputStream()
                val byte = ByteArray(1)
                while (pipes.readProtocol(byte) > 0 && byte[0] != 10.toByte()) line.write(byte[0].toInt())
                children += ProcessHandle.of(line.toString(Charsets.UTF_8).trim().toLong()).orElseThrow()
            }
            assertEquals(pipes.processId, children[0].parent().orElseThrow().pid())
            assertEquals(children[0], children[1].parent().orElseThrow())
            assertTrue(children.all { it.isAlive })
            release(pipes)
            assertFalse(pipes.isAlive)
            assertTrue(children.none { it.isAlive }, "Revocation left descendant processes alive: " +
                children.filter { it.isAlive }.map { it.pid() })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                // Cleanup is restricted to the exact disposable processes reported by this fixture.
                for (child in children.asReversed()) {
                    if (child.isAlive) child.destroyForcibly()
                    child.onExit().get(5, TimeUnit.SECONDS)
                }
                pipes.close()
            }
        }
    }

    private suspend fun bytes(read: suspend (ByteArray) -> Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    private fun command(mode: String, port: Int?, token: String?, marker: String = ""): List<String> {
        // Gradle's java.class.path may contain only its worker jar; use the actual class locations.
        val classpath = listOf(
            JvmProcessProtocolChild::class.java, JvmProcessProtocolPipes::class.java,
            ProcessProtocolPipes::class.java, Unit::class.java, Job::class.java,
        ).map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        return listOf(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            JvmProcessProtocolChild::class.java.name, mode, port.toString(), token.orEmpty(), marker)
    }

    @Test fun stdioProtocolRoundTripsUtf8AndExitsAtEof(): Unit = runBlocking {
        withTimeout(10_000) {
            val pipes = JvmProcessProtocolPipes.open({ port, token ->
                assertNull(port); assertNull(token)
                listOf("/bin/cat")
            }, emptyMap())
            try {
                assertFalse(pipes.separateProtocol)
                assertEquals(setOf(ProcessOutput.Stderr), pipes.terminalOutputs)
                val message = "protocol: λ🙂\nsecond line\n".encodeToByteArray()
                pipes.writeProtocol(message)
                pipes.closeProtocolInput()
                assertContentEquals(message, bytes(pipes::readProtocol))
                assertEquals(-1, pipes.read(ProcessOutput.Stderr, ByteArray(1)))
                assertEquals(0, pipes.awaitExit())
                assertFalse(pipes.isAlive)
            } finally { pipes.close() }
            pipes.close()
        }
    }

    @Test fun ownerCancellationJoinsBlockedNativeReadsAndReapsChild(): Unit = runBlocking {
        val pipes = JvmProcessProtocolPipes.open({ _, _ -> listOf("/bin/cat") }, emptyMap())
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner)
        try {
            val protocol = scope.async(start = CoroutineStart.UNDISPATCHED) { pipes.readProtocol(ByteArray(1)) }
            val stderr = scope.async(start = CoroutineStart.UNDISPATCHED) { pipes.read(ProcessOutput.Stderr, ByteArray(1)) }
            delay(100)
            assertFalse(protocol.isCompleted)
            assertFalse(stderr.isCompleted)
            withTimeout(5_000) { owner.cancelAndJoin() }
            assertTrue(owner.isCompleted)
            assertTrue(protocol.isCompleted && stderr.isCompleted)
            assertFalse(pipes.isAlive)
            assertFalse(ProcessHandle.of(pipes.processId).map { it.isAlive }.orElse(false))
        } finally {
            withContext(NonCancellable) {
                owner.cancel()
                pipes.close()
                owner.join()
            }
        }
    }

    @Test fun socketProtocolPreservesPhysicalStdinAndTerminalOutputs(): Unit = runBlocking {
        withTimeout(15_000) {
            val pipes = JvmProcessProtocolPipes.open(
                { port, token -> command("echo", port, token) },
                mapOf("OWN_API_KEY" to "explicit-fixture"), protocolTimeoutMillis = 5_000,
            )
            try {
                assertTrue(pipes.separateProtocol)
                assertEquals(setOf(ProcessOutput.Stdout, ProcessOutput.Stderr), pipes.terminalOutputs)
                pipes.write("terminal-input: λ🙂".encodeToByteArray())
                pipes.closeInput()
                pipes.writeProtocol("protocol-input: café🙂".encodeToByteArray())
                pipes.closeProtocolInput()
                assertEquals("terminal-input: λ🙂|explicit-fixture|protocol-input: café🙂",
                    bytes(pipes::readProtocol).decodeToString())
                assertEquals("terminal-stdout: λ🙂\n",
                    bytes { pipes.read(ProcessOutput.Stdout, it) }.decodeToString())
                assertEquals("terminal-stderr: café🙂\n",
                    bytes { pipes.read(ProcessOutput.Stderr, it) }.decodeToString())
                assertEquals(0, pipes.awaitExit())
                assertFalse(pipes.isAlive)
            } finally { pipes.close() }
        }
    }

    @Test fun badIdentityPropagatesAndReapsChild(): Unit = runBlocking {
        withTimeout(15_000) {
            val marker = UUID.randomUUID().toString()
            var child: ProcessHandle? = null
            var opened: JvmProcessProtocolPipes? = null
            val opening = async {
                runCatching {
                    JvmProcessProtocolPipes.open(
                        { port, token -> command("bad-identity", port, token, marker) },
                        emptyMap(), protocolTimeoutMillis = 5_000,
                    ).also { opened = it }
                }
            }
            try {
                while (child == null) {
                    child = ProcessHandle.current().children().use { children ->
                        children.filter { it.info().arguments().orElse(emptyArray()).contains(marker) }
                            .findFirst().orElse(null)
                    }
                    if (child == null) {
                        check(!opening.isCompleted) { "Child was not observed: ${opening.await()}" }
                        delay(10)
                    }
                }
                val failure = opening.await().exceptionOrNull()
                assertIs<IllegalArgumentException>(failure)
                assertContains(failure.message.orEmpty(), "identity mismatch")
                assertFalse(requireNotNull(child).isAlive, "Failed construction must reap the child before returning")
            } finally {
                withContext(NonCancellable) {
                    opening.cancelAndJoin()
                    opened?.close()
                    withContext(Dispatchers.IO) {
                        child?.let {
                            if (it.isAlive) it.destroyForcibly()
                            it.onExit().get(5, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        }
    }
}

/** Child fixture is a JVM entry point so no shell scripts or temporary files are needed. */
object JvmProcessProtocolChild {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        if (args[0] == "descendants" || args[0] == "descendant") {
            if (args[0] == "descendant") check(System.`in`.read() == 1)
            val command = if (args[0] == "descendant") listOf("/bin/sleep", "60") else listOf(
                File(System.getProperty("java.home"), "bin/java").path, "-cp", System.getProperty("java.class.path"),
                JvmProcessProtocolChild::class.java.name, "descendant",
            )
            val builder = ProcessBuilder(command).inheritIO()
            if (args[0] == "descendants") builder.redirectInput(ProcessBuilder.Redirect.PIPE)
            val child = builder.start()
            println(child.pid())
            System.out.flush()
            if (args[0] == "descendants") child.outputStream.apply { write(1); flush() }
            awaitCancellation()
        }
        val badIdentity = args[0] == "bad-identity"
        // Leave time for the test to observe the PID before authentication rejects it.
        if (badIdentity) delay(500)
        val socket = JvmProcessProtocolSocket.connect(args[1].toInt(), args[2] + if (badIdentity) "-wrong" else "")
        socket.use {
            if (badIdentity) awaitCancellation()
            withContext(Dispatchers.IO) {
                System.out.write("terminal-stdout: λ🙂\n".encodeToByteArray())
                System.out.flush()
                System.err.write("terminal-stderr: café🙂\n".encodeToByteArray())
                System.err.flush()
                val terminal = System.`in`.readAllBytes().decodeToString()
                val protocol = socket.getInputStream().readAllBytes().decodeToString()
                socket.getOutputStream().apply {
                    write("$terminal|${System.getenv("OWN_API_KEY")}|$protocol".encodeToByteArray())
                    flush()
                }
            }
        }
    }
}
