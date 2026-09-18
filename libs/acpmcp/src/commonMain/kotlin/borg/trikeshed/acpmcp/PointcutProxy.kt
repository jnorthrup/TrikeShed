package borg.trikeshed.acpmcp

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries

/**
 * Network proxy that routes ACP/MCP frames over a transport.
 */
interface PointcutProxyTransport {
    val protocol: AcpmcpProtocol
    suspend fun send(frame: AcpmcpFrame): AcpmcpFrame
    suspend fun close()
}

/**
 * Factory for creating the proxy transport.
 */
fun createPointcutProxyTransport(
    protocol: AcpmcpProtocol,
    peer: String,
): PointcutProxyTransport = PointcutProxyTransportImpl(protocol, peer)

/**
 * Default implementation - direct pass-through.
 */
class PointcutProxyTransportImpl(
    override val protocol: AcpmcpProtocol,
    private val peer: String,
) : PointcutProxyTransport {
    override suspend fun send(frame: AcpmcpFrame): AcpmcpFrame =
        frame.copy(payload = "direct:$peer:${frame.method}")

    override suspend fun close() {}
}

/**
 * Facade for routing pointcut events.
 */
open class PointcutProxyFacade {
    open suspend fun routeViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        sampleEvents: PointcutRouteEventSeries,
    ): PointcutRouteReport {
        for (i in 0 until sampleEvents.a) {
            val event = sampleEvents.b(i)
            reactor.record(event)
        }
        return PointcutRouteReport(
            routed = sampleEvents.a,
            opcodes = emptyList<String>().toSeries(),
            phases = emptyList<PointcutRoutePhase>().toSeries()
        )
    }

    open suspend fun requestScanViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        request: PointcutScanRequest,
    ): PointcutRouteReport {
        return PointcutRouteReport(0, emptyList<String>().toSeries(), emptyList<PointcutRoutePhase>().toSeries())
    }
}

fun pointcutProxyFacade(): PointcutProxyFacade = PointcutProxyFacade()