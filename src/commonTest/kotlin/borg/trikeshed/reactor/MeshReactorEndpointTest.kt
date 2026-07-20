package borg.trikeshed.reactor

import kotlin.test.*

class MeshReactorEndpointTest {

    @Test
    fun meshConfigRejectsNegativeTimeout() {
        assertFailsWith<IllegalArgumentException> {
            MeshConfig(timeoutMs = -1)
        }
    }

    @Test
    fun meshConfigRejectsNegativeRetries() {
        assertFailsWith<IllegalArgumentException> {
            MeshConfig(maxRetries = -1)
        }
    }

    @Test
    fun meshErrorCodeRoundTrip() {
        for (error in MeshErrorCode.entries) {
            assertTrue(error.code < 0)
            assertEquals(error, MeshErrorCode.fromInt(error.code))
        }
    }

    @Test
    fun meshFrameRoundTrip() {
        val payloadBytes = ByteArray(256) { it.toByte() }
        val encodedBytes = MeshActionFrame.encodeBytes(payloadBytes)
        val decoded = MeshActionFrame.decode(encodedBytes)
        assertTrue(payloadBytes.contentEquals(decoded.payload))
    }

    @Test
    fun meshFrameRejectsTruncatedFrame() {
        val bytes = ByteArray(4)
        bytes[0] = 0
        bytes[1] = 0
        bytes[2] = 4
        bytes[3] = 0

        val e = assertFailsWith<IllegalArgumentException> {
            MeshActionFrame.decode(bytes)
        }
        assertEquals("truncated frame", e.message)
    }

    @Test
    fun meshFrameRejectsOversizeFrame() {
        val bytes = ByteArray(4)
        bytes[0] = 0x3B
        bytes[1] = 0x9A.toByte()
        bytes[2] = 0xCA.toByte()
        bytes[3] = 0x00

        val e = assertFailsWith<IllegalArgumentException> {
            MeshActionFrame.decode(bytes)
        }
        assertEquals("payload too large", e.message)
    }

    class FakeKademliaNode(val peersToReturn: List<PeerAddress>) : KademliaNode {
        override suspend fun lookup(subnet: String): List<PeerAddress> = peersToReturn
    }

    class TestMeshReactorEndpoint(
        dht: KademliaNode,
        config: MeshConfig,
        var fakeResponse: MeshActionResult = MeshActionResult.Failed(MeshErrorCode.BAD_FRAME),
        var failsBeforeSuccess: Int = 0
    ) : MeshReactorEndpoint(dht, config) {
        var attempts = 0
        override suspend fun sendFrame(peer: PeerAddress, frame: MeshActionFrame): MeshActionResult {
            attempts++
            if (failsBeforeSuccess > 0) {
                failsBeforeSuccess--
                return MeshActionResult.TimedOut
            }
            return fakeResponse
        }
    }

    @Test
    fun meshEndpointSendsActionAndReturnsOk() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(listOf(PeerAddress("127.0.0.1", 8080), PeerAddress("127.0.0.1", 8081)))
        val ep = TestMeshReactorEndpoint(dht, MeshConfig())
        ep.fakeResponse = MeshActionResult.Ok(ByteArray(10) { 1 })

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Ok)
        assertTrue((result as MeshActionResult.Ok).payload.size > 0)
    }

    @Test
    fun meshEndpointReturnsPeerNotFoundWhenDhtEmpty() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(emptyList())
        val ep = TestMeshReactorEndpoint(dht, MeshConfig())

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Failed)
        assertEquals(MeshErrorCode.PEER_NOT_FOUND, (result as MeshActionResult.Failed).code)
    }

    @Test
    fun meshEndpointReturnsTimedOutAfterRetries() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(listOf(PeerAddress("127.0.0.1", 8080)))
        val config = MeshConfig(maxRetries = 3)
        val ep = TestMeshReactorEndpoint(dht, config)
        ep.fakeResponse = MeshActionResult.TimedOut

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Failed)
        assertEquals(MeshErrorCode.TIMEOUT, (result as MeshActionResult.Failed).code)
        assertEquals(3, ep.attempts)
    }

    @Test
    fun meshEndpointRejectsOversizedPayload() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(listOf(PeerAddress("127.0.0.1", 8080)))
        val config = MeshConfig(maxPayloadBytes = 100)
        val ep = TestMeshReactorEndpoint(dht, config)

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test".padEnd(200, 'x')),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Failed)
        assertEquals(MeshErrorCode.PAYLOAD_TOO_LARGE, (result as MeshActionResult.Failed).code)
    }

    @Test
    fun meshEndpointRetriesOnTimeout() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(listOf(PeerAddress("127.0.0.1", 8080)))
        val ep = TestMeshReactorEndpoint(dht, MeshConfig(maxRetries = 3))
        ep.failsBeforeSuccess = 2
        ep.fakeResponse = MeshActionResult.Ok(ByteArray(1))

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Ok)
        assertEquals(3, ep.attempts)
    }

    @Test
    fun meshEndpointDoesNotRetryOnBadFrame() = kotlinx.coroutines.test.runTest {
        val dht = FakeKademliaNode(listOf(PeerAddress("127.0.0.1", 8080)))
        val ep = TestMeshReactorEndpoint(dht, MeshConfig(maxRetries = 3))
        ep.fakeResponse = MeshActionResult.Failed(MeshErrorCode.BAD_FRAME)

        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Custom("test", "test"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )
        val action = borg.trikeshed.lcnc.reactor.ReactorAction.opened(nuid)

        val result = ep.invoke(action)
        assertTrue(result is MeshActionResult.Failed)
        assertEquals(MeshErrorCode.BAD_FRAME, (result as MeshActionResult.Failed).code)
        assertEquals(1, ep.attempts)
    }
}
