package borg.trikeshed.reactor

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.toSeries
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.litebike.tunnel.Tunnel
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockTlsEndpoint : TlsEndpoint {
    override val route: TlsRoute = TlsRole.CLIENT j ("localhost" j 443)
    override val flowState: TlsFlowState = TlsFlowState(route)
    override val isHandshakeComplete: Boolean = true

    var handshakeCalled = false
    var closeCalled = false
    val upstreamPayloads = mutableListOf<TlsPayload>()
    val downstreamPayloads = mutableListOf<TlsPayload>()

    override suspend fun handshake(): TlsFrames {
        handshakeCalled = true
        return emptyTlsFrames()
    }

    override suspend fun upstream(payload: TlsPayload): TlsFrames {
        upstreamPayloads.add(payload)
        return emptyTlsFrames()
    }

    override suspend fun downstream(payload: TlsPayload): TlsFrames {
        downstreamPayloads.add(payload)
        return emptyTlsFrames()
    }

    override suspend fun close(): TlsFrames {
        closeCalled = true
        return emptyTlsFrames()
    }
}

class SshMeshTransportTest {

    @Test
    fun testImplementsTunnel() {
        val endpoint = MockTlsEndpoint()
        val transport = SshMeshTransport("test-id", endpoint, Protocol.Ssh, "localhost", 22)
        assertTrue(transport is Tunnel)
        assertEquals("test-id", transport.id)
        assertEquals(Protocol.Ssh, transport.protocol)
        assertEquals("localhost", transport.remoteHost)
        assertEquals(22, transport.remotePort)
    }

    @Test
    fun testConnectCallsHandshake() = runTest {
        val endpoint = MockTlsEndpoint()
        val transport = SshMeshTransport("test-id", endpoint, Protocol.Ssh, "localhost", 22)
        transport.connect()
        assertTrue(endpoint.handshakeCalled)
    }

    @Test
    fun testCloseCallsClose() = runTest {
        val endpoint = MockTlsEndpoint()
        val transport = SshMeshTransport("test-id", endpoint, Protocol.Ssh, "localhost", 22)
        transport.close()
        assertTrue(endpoint.closeCalled)
    }

    @Test
    fun testWriteCallsUpstream() = runTest {
        val endpoint = MockTlsEndpoint()
        val transport = SshMeshTransport("test-id", endpoint, Protocol.Ssh, "localhost", 22)
        val data = byteArrayOf(1, 2, 3)
        transport.write(data)
        
        assertEquals(1, endpoint.upstreamPayloads.size)
        val payload = endpoint.upstreamPayloads[0]
        assertEquals(3, payload.limit)
    }

    @Test
    fun testSendConfixDocCallsUpstream() = runTest {
        val endpoint = MockTlsEndpoint()
        val transport = SshMeshTransport("test-id", endpoint, Protocol.Ssh, "localhost", 22)
        val docStr = "{\"hello\":\"world\"}"
        val doc = confixDoc(docStr)
        transport.sendConfixDoc(doc)
        
        assertEquals(1, endpoint.upstreamPayloads.size)
        val payload = endpoint.upstreamPayloads[0]
        assertTrue(payload.limit > 0)
    }
}
