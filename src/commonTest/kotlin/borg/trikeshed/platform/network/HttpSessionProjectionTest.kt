package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that an HTTP-like byte-stream session can be expressed
 * using the channelization session/block/projection core.
 *
 * This test validates that the current core types are sufficient
 * to represent the semantic units and lifecycle of a simple HTTP exchange
 * using the HttpLikeSessionBuilder proof helpers.
 */
class HttpSessionProjectionTest {

    @Test
    fun proveHttpSessionBuilderComposition() {
        val sessionId = ChannelSessionId("http-proof-session")
        val builder = HttpLikeSessionBuilder(sessionId)
        
        assertEquals(ChannelSessionState.Initialized, builder.state)
        
        builder.activate()
        assertEquals(ChannelSessionState.Active, builder.state)
        
        // 1. Create a request
        val request = HttpLikeRequest(
            method = "GET",
            path = "/index.html",
            headers = mapOf("Accept" to "text/html")
        )
        
        // 2. Build envelope (Egress for request)
        val requestEnvelope = builder.buildRequestEnvelope(request)
        
        assertEquals(TransferDirection.Egress, requestEnvelope.direction)
        assertEquals(ProtocolId.HTTP, requestEnvelope.protocol)
        assertEquals(sessionId, requestEnvelope.block.session)
        assertEquals(0L, requestEnvelope.block.sequence.value)
        
        val payloadStr = requestEnvelope.block.payload.decodeToString()
        assertTrue(payloadStr.startsWith("GET /index.html HTTP/1.1"))
        assertTrue(payloadStr.contains("Accept: text/html"))
        
        // 3. Create a response
        val response = HttpLikeResponse(
            status = 200,
            statusText = "OK",
            body = "Hello".encodeToByteArray()
        )
        
        // 4. Build response envelope (Ingress for response)
        val responseEnvelope = builder.buildResponseEnvelope(response)
        
        assertEquals(TransferDirection.Ingress, responseEnvelope.direction)
        assertEquals(1L, responseEnvelope.block.sequence.value)
        
        val decodedResponse = HttpLikeResponse.decodeFromBlock(responseEnvelope.block)
        assertEquals(200, decodedResponse.status)
        assertEquals("OK", decodedResponse.statusText)
        assertEquals("Hello", decodedResponse.body.decodeToString())
        
        builder.terminate()
        assertEquals(ChannelSessionState.Terminated, builder.state)
    }

    @Test
    fun proveProjectionToHttpSession() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "test-provider",
            estimatedCost = 10,
        )
        
        val projection = plan.projectToSessionShape()
        val builder = projection.toHttpLikeSession()
        
        assertTrue(projection.sessionShape.sessionId.raw.startsWith("projected-HTTP"))
        assertEquals(ChannelSessionState.Initialized, builder.state)
    }
}
