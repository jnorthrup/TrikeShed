package borg.trikeshed.graal.subvm

import borg.trikeshed.userspace.nio.channels.spi.*
import borg.trikeshed.userspace.nio.process.ProcessProtocolChannel
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Speak the wire directly: the parent GuestIsolate lock otherwise defers close until eval returns. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class ChildProtocolTest {
    @Test fun restrictedChildDrainsQueuedCloseAfterTheOutstandingCallback() = queuedClose(false)
    @Test fun stockPythonDrainsQueuedCloseAfterTheOutstandingCallback() = queuedClose(true)

    private fun queuedClose(stockPython: Boolean): Unit = runBlocking {
        withTimeout(45_000) {
            val pipes = JvmProcessProtocolPipes.open(command = { port, token ->
                val java = File(System.getProperty("java.home"), "bin/java").path
                val classpath = System.getProperty("java.class.path")
                if (stockPython) listOf(java, "--enable-native-access=ALL-UNNAMED", "-Xss4m", "-cp", classpath,
                    PythonWorkerMain::class.java.name, "queued-close-python", PythonWorker().encode(),
                    requireNotNull(port).toString(), requireNotNull(token))
                else listOf(java, "-Xss4m", "-cp", classpath, SubVmMain::class.java.name,
                    "js", "queued-close-js", "0", "0")
            }, environment = GuestEnvironment.curated(), protocolTimeoutMillis = if (stockPython) 15_000 else null)
            val received = Channel<Teleported.Obj>(16)
            val channel = ProcessProtocolChannel(coroutineContext + Dispatchers.Default, pipes,
                onLine = { received.send(SubVmProtocol.decode(it)) })
            suspend fun send(message: Teleported.Obj) = channel.send(SubVmProtocol.encode(message))
            try {
                send(Teleported.obj("id" to 1, "op" to "delegate", "name" to "hold"))
                assertEquals(Teleported.Bool(true), received.receive().field("ok"))
                send(Teleported.obj("id" to 2, "op" to "eval", "source" to "host.call('hold')"))
                val callback = received.receive()
                assertEquals("host", callback.str("op"))
                assertEquals("hold", callback.str("name"))
                if (stockPython) channel.closeInput()
                send(Teleported.obj("id" to 3, "op" to "close"))
                assertNull(withTimeoutOrNull(150) { received.receive() },
                    "Close cannot acknowledge before the admitted callback and guest operation finish")
                assertTrue(pipes.isAlive)
                send(Teleported.obj("id" to callback.int("id"), "value" to Teleported.Num(42)))
                val result = received.receive()
                assertEquals(2, result.int("id"))
                assertEquals(Teleported.Num(42), result.field("value"))
                val closed = received.receive()
                assertEquals(3, closed.int("id"))
                assertEquals(Teleported.Bool(true), closed.field("ok"))
                channel.closeProtocolInput()
                channel.drain()
                assertFalse(pipes.isAlive)
                assertFalse(ProcessHandle.of(pipes.processId).map { it.isAlive }.orElse(false))
            } finally {
                withContext(NonCancellable) {
                    if (pipes.isAlive) pipes.revoke(0)
                    runCatching { channel.drain() }
                    received.cancel()
                }
            }
        }
    }
}
