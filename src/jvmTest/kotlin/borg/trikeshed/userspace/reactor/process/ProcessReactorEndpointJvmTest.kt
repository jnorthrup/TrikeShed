package borg.trikeshed.userspace.reactor.process

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Join
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import borg.trikeshed.userspace.reactor.process.ProcessReactorEndpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FakeProcessOperationsForEndpoint : ProcessOperations {
    val executedCommands = mutableListOf<List<String>>()

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>
    ): ProcessResult {
        executedCommands.add(listOf(command) + args)
        if (command == "echo") {
            return ProcessResult(0, args.joinToString(" ").encodeToByteArray(), ByteArray(0))
        }
        return ProcessResult(-1, ByteArray(0), "Command not found".encodeToByteArray())
    }
}

class ProcessReactorEndpointJvmTest {

    @Test
    fun testExecEcho() = runTest {
        val processOps = FakeProcessOperationsForEndpoint()
        val endpoint = ProcessReactorEndpoint(processOps)

        val cap = Capability.Process("echo")
        val testNuid = nuid(cap, Nonce.RandomBytes(), Subnet.local)

        // Payload is newline separated args
        val action: ReactorAction = testNuid j ("exec" j "hello\nworld".encodeToByteArray())

        val result = endpoint.invoke(action)

        assertEquals(1, processOps.executedCommands.size)
        assertEquals(listOf("echo", "hello", "world"), processOps.executedCommands[0])

        assertEquals("ok", result.b.a)
        assertEquals("hello world", result.b.b.decodeToString())
    }

    @Test
    fun testInvalidCapability() = runTest {
        val processOps = FakeProcessOperationsForEndpoint()
        val endpoint = ProcessReactorEndpoint(processOps)

        val cap = Capability.Cas("read")
        val testNuid = nuid(cap, Nonce.RandomBytes(), Subnet.local)

        val action: ReactorAction = testNuid j ("exec" j "hello".encodeToByteArray())

        val result = endpoint.invoke(action)

        assertEquals(0, processOps.executedCommands.size)
        assertEquals("error", result.b.a)
        assertEquals("Invalid capability for ProcessReactorEndpoint", result.b.b.decodeToString())
    }
}
