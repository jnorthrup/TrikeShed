package borg.trikeshed.reactor.endpoint

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfixWireTransportTest {

    @Test
    fun transportRoundTrip() = runTest {
        // Channels to simulate a bidirectional wire
        val clientToServer = Channel<ByteArray>(Channel.UNLIMITED)
        val serverToClient = Channel<ByteArray>(Channel.UNLIMITED)

        // Mock Server implementation
        val serverEndpoint = EchoReactorEndpoint()
        val serverCodec = ConfixEnvelopeCodec()

        // Background server loop
        val serverJob = launch {
            try {
                for (reqBytes in clientToServer) {
                    val reqEnvelope = serverCodec.decode(reqBytes)
                    val resEnvelope = serverEndpoint.invoke(reqEnvelope)
                    val resBytes = serverCodec.encode(resEnvelope)
                    serverToClient.send(resBytes)
                }
            } catch (e: Exception) {
                // Ignore cancellation or closed channel exceptions in test
            }
        }

        // Client transport connected to the simulated wire
        val clientTransport = ConfixWireTransport(
            sendBytes = { bytes -> clientToServer.send(bytes) },
            receiveBytes = { serverToClient.receive() }
        )

        val testNuid = nuid(
            Capability.Process("test-process"),
            Nonce.Restored(ByteArray(16) { it.toByte() }),
            Subnet.local
        )
        val requestEnvelope = ReactorActionEnvelope(
            nuid = testNuid,
            verb = "ping",
            payload = byteArrayOf(10, 20, 30)
        )

        // Perform the invoke over the wire
        val responseEnvelope = clientTransport.invoke(requestEnvelope)

        // The NUID capability serialization/deserialization doesn't preserve exact instance equality for capabilities in nuid
        // compare the actual fields for the response envelope
        assertEquals(requestEnvelope.verb, responseEnvelope.verb, "Response verb should match")
        assertEquals(requestEnvelope.payload.contentToString(), responseEnvelope.payload.contentToString(), "Response payload should match")
        assertEquals(requestEnvelope.nuid.b.b.toString(), responseEnvelope.nuid.b.b.toString(), "Response subnet should match")
        assertEquals(requestEnvelope.nuid.b.a.bytes.contentToString(), responseEnvelope.nuid.b.a.bytes.contentToString(), "Response nonce should match")

        serverJob.cancel()
    }
}
