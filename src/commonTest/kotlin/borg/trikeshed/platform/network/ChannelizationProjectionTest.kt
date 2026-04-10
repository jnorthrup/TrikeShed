package borg.trikeshed.net.channelization

import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.spi.TransportBackendKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelizationProjectionTest {

    @Test
    fun planProjectsToCorrectSessionShape() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "test-provider",
            estimatedCost = 10,
            backendKind = TransportBackendKind.SELECTOR,
        )

        val projection = plan.projectToSessionShape()

        assertEquals(plan, projection.plan)
        assertEquals(ChannelSemantics.BYTE_STREAM, projection.sessionShape.semantics)
        assertEquals(ChannelizationPath.TRANSPORT_BACKEND, projection.sessionShape.path)
        assertTrue(projection.sessionShape.sessionId.raw.startsWith("projected-HTTP"))
    }

    @Test
    fun projectionIncludesProtocolIdentity() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.QUIC,
            semantics = ChannelSemantics.MESSAGE_STREAM,
            path = ChannelizationPath.DIRECT_SERVICE,
            provider = "quic-service",
            estimatedCost = 0,
        )

        val projection = plan.projectToSessionShape()

        assertEquals(ProtocolId.QUIC, projection.plan.protocol)
        assertEquals("projected-QUIC", projection.sessionShape.sessionId.raw)
    }

    @Test
    fun selectAndProjectCombinesSelectionAndProjection() = runTest {
        val provider = object : ChannelizationProvider {
            override val name: String = "test-provider"
            override fun supports(request: ChannelizationRequest): Boolean = true
            override suspend fun plan(
                request: ChannelizationRequest,
                capabilities: borg.trikeshed.net.spi.TransportCapabilities?,
            ): ChannelizationPlan = ChannelizationPlan(
                protocol = request.protocol,
                semantics = ChannelSemantics.BYTE_STREAM,
                path = ChannelizationPath.DIRECT_SERVICE,
                provider = name,
                estimatedCost = 5,
            )
        }

        val projection = withContext(ChannelizationService(listOf(provider))) {
            selectAndProjectChannelization(ChannelizationRequest(ProtocolId.HTTP))
        }

        assertEquals("test-provider", projection.plan.provider)
        assertEquals(ProtocolId.HTTP, projection.plan.protocol)
        assertEquals("projected-HTTP", projection.sessionShape.sessionId.raw)
        assertEquals(ChannelSemantics.BYTE_STREAM, projection.sessionShape.semantics)
    }

    @Test
    fun blockCanReferenceProjectedSessionId() {
        val plan = ChannelizationPlan(
            protocol = ProtocolId.HTTP,
            semantics = ChannelSemantics.BYTE_STREAM,
            path = ChannelizationPath.TRANSPORT_BACKEND,
            provider = "test-provider",
            estimatedCost = 10,
        )

        val projection = plan.projectToSessionShape()
        val sessionId = projection.sessionShape.sessionId

        val block = ChannelBlock(
            id = ChannelBlockId(123L),
            session = sessionId,
            sequence = BlockSequence(1L),
            payload = byteArrayOf(1, 2, 3)
        )

        assertEquals(sessionId, block.session)
        assertEquals(123L, block.id.raw)
    }
}
