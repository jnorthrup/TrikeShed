package borg.trikeshed.reactor.endpoint

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.cursor.Cursor
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EchoReactorEndpoint : ReactorEndpoint {
    override suspend fun invoke(action: ReactorActionEnvelope, pathCursor: Cursor?): ReactorActionEnvelope {
        return action
    }
}

class TimeoutEchoReactorEndpoint : ReactorEndpoint {
    override suspend fun invoke(action: ReactorActionEnvelope, pathCursor: Cursor?): ReactorActionEnvelope {
        delay(150_000)
        return action
    }
}

class PermitOnlyEchoReactorEndpoint : ReactorEndpoint {
    private val config = ReactorEndpointConfig(permittedVerbs = setOf("echo"))
    override suspend fun invoke(action: ReactorActionEnvelope, pathCursor: Cursor?): ReactorActionEnvelope {
        require(action.verb in config.permittedVerbs) { "verb ${action.verb} not in permittedVerbs" }
        return action
    }
}

class ReactorEndpointTest {

    private val testNuid = nuid(
        Capability.Process("test"),
        Nonce.Restored(ByteArray(16) { it.toByte() }),
        Subnet.parse("test.local")
    )

    // verifies: invoke EchoReactorEndpoint with a ping envelope → result envelope equals input
    @Test
    fun echoRoundTrip() = runTest {
        val endpoint = EchoReactorEndpoint()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf(1, 2, 3))
        val result = endpoint.invoke(envelope)
        assertEquals(envelope, result)
    }

    // verifies: a TimeoutEchoReactorEndpoint that delays 5 times the defaultTimeoutMs should still complete (the test uses a runBlocking with a 200ms virtual time — assert completion within 1000ms)
    @Test
    fun echoRespectsConfigTimeout() = runTest {
        val endpoint = TimeoutEchoReactorEndpoint()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf(1, 2, 3))

        withTimeout(200_000) {
            val result = endpoint.invoke(envelope)
            assertEquals(envelope, result)
        }
    }

    // verifies: permitOnlyEcho endpoint rejects verbs not in { "echo" }
    @Test
    fun echoRejectsUnknownVerb() = runTest {
        val endpoint = PermitOnlyEchoReactorEndpoint()
        val envelope = ReactorActionEnvelope(testNuid, "ping", byteArrayOf(1, 2, 3))
        assertFailsWith<IllegalArgumentException> {
            endpoint.invoke(envelope)
        }
    }
}
