package borg.trikeshed.net.channelization

import borg.trikeshed.context.IoCapability
import borg.trikeshed.context.IoPreference
import borg.trikeshed.ccek.transport.QuicChannelService
import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.spi.IngressSteeringDecision
import borg.trikeshed.net.spi.SteeringSource
import borg.trikeshed.net.spi.TransportBackendKind
import borg.trikeshed.net.spi.TransportBackendService
import borg.trikeshed.net.spi.TransportCapabilities
import borg.trikeshed.net.spi.TransportSpi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelizationSelectionTest {

    @Test
    fun prefersLowestCostExplicitProvider() = runTest {
        val expensive = object : ChannelizationProvider {
            override val name: String = "expensive"

            override fun supports(request: ChannelizationRequest): Boolean =
                request.protocol == ProtocolId.HTTP

            override fun estimateCost(
                request: ChannelizationRequest,
                capabilities: TransportCapabilities?,
            ): Int = 40

            override suspend fun plan(
                request: ChannelizationRequest,
                capabilities: TransportCapabilities?,
            ): ChannelizationPlan = ChannelizationPlan(
                protocol = request.protocol,
                semantics = ChannelSemantics.BYTE_STREAM,
                path = ChannelizationPath.DIRECT_SERVICE,
                provider = name,
                estimatedCost = 40,
            )
        }
        val lean = object : ChannelizationProvider {
            override val name: String = "lean"

            override fun supports(request: ChannelizationRequest): Boolean =
                request.protocol == ProtocolId.HTTP

            override fun estimateCost(
                request: ChannelizationRequest,
                capabilities: TransportCapabilities?,
            ): Int = 5

            override suspend fun plan(
                request: ChannelizationRequest,
                capabilities: TransportCapabilities?,
            ): ChannelizationPlan = ChannelizationPlan(
                protocol = request.protocol,
                semantics = ChannelSemantics.BYTE_STREAM,
                path = ChannelizationPath.DIRECT_SERVICE,
                provider = name,
                estimatedCost = 5,
            )
        }

        val selected = withContext(
            ChannelizationService(listOf(expensive, lean)) + IoPreference(IoCapability.NIO),
        ) {
            selectChannelization(ChannelizationRequest(ProtocolId.HTTP))
        }

        assertEquals("lean", selected.provider)
        assertEquals(ChannelizationPath.DIRECT_SERVICE, selected.path)
        assertEquals(ChannelSemantics.BYTE_STREAM, selected.semantics)
    }

    @Test
    fun prefersInstalledQuicServiceOverTransportBackend() = runTest {
        val selected = withContext(
            QuicChannelService() +
                TransportBackendService(FakeTransportSpi(TransportBackendKind.LINUX_NATIVE)) +
                IoPreference(IoCapability.URING),
        ) {
            selectChannelization(ChannelizationRequest(ProtocolId.QUIC))
        }

        assertEquals("quic-service", selected.provider)
        assertEquals(ChannelizationPath.DIRECT_SERVICE, selected.path)
        assertEquals(ChannelSemantics.MESSAGE_STREAM, selected.semantics)
        assertEquals(TransportBackendKind.LINUX_NATIVE, selected.backendKind)
    }

    @Test
    fun fallsBackToTransportBackendForHttp() = runTest {
        val selected = withContext(
            TransportBackendService(FakeTransportSpi(TransportBackendKind.SELECTOR)) +
                IoPreference(IoCapability.NIO),
        ) {
            selectChannelization(ChannelizationRequest(ProtocolId.HTTP))
        }

        assertEquals("transport-backend", selected.provider)
        assertEquals(ChannelizationPath.TRANSPORT_BACKEND, selected.path)
        assertEquals(ChannelSemantics.BYTE_STREAM, selected.semantics)
        assertEquals(TransportBackendKind.SELECTOR, selected.backendKind)
    }
}

private class FakeTransportSpi(
    override val kind: TransportBackendKind,
) : TransportSpi {
    override fun capabilities(): TransportCapabilities = TransportCapabilities(
        backendKind = kind,
        nativeLinux = kind == TransportBackendKind.LINUX_NATIVE,
        ioUringRequested = kind == TransportBackendKind.LINUX_NATIVE,
        ioUringAvailable = kind == TransportBackendKind.LINUX_NATIVE,
        xdpSteeringRequested = false,
        xdpAvailable = false,
    )

    override fun classifyIngress(payload: ByteArray): IngressSteeringDecision = IngressSteeringDecision(
        protocol = ProtocolId.UNKNOWN,
        queueId = 0,
        workerId = 0,
        source = SteeringSource.SOFTWARE_FALLBACK,
    )
}
