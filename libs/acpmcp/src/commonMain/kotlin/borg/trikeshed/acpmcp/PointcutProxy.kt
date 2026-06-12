package borg.trikeshed.acpmcp

/**
 * Network proxy that routes ACP/MCP frames over a transport
 * when running on non-JVM targets (JS, native).
 *
 * On JVM the classfile harness routes pointcuts directly into the reactor.
 * On non-JVM targets, this proxy sends pointcut events over ACP/MCP network
 * to a JVM-side reactor that performs the actual classfile scanning.
 *
 * The choreography (ACCEPT → DISPATCH → COMPLETE) is the same regardless of
 * transport — only the latency differs.
 */
interface PointcutProxyTransport {
    val protocol: AcpmcpProtocol
    suspend fun send(frame: AcpmcpFrame): AcpmcpFrame
    suspend fun close()
}

/**
 * Factory for creating the platform-specific proxy transport.
 * Each platform provides an `actual` that knows how to reach the
 * JVM-side classfile reactor over the network.
 */
expect fun createPointcutProxyTransport(
    protocol: AcpmcpProtocol,
    peer: String,
): PointcutProxyTransport

/**
 * Non-JVM facade: routes pointcut events through ACP/MCP network proxy
 * to a JVM-side reactor that performs classfile scanning.
 *
 * The proxy maintains the same PointcutRouteEvent choreography as the
 * direct JVM facade, but events traverse the network instead of being
 * produced in-process.
 */
expect class PointcutProxyFacade() {
    suspend fun routeViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        sampleEvents: PointcutRouteEventSeries,
    ): PointcutRouteReport

    /**
     * Request a pointcut scan from the JVM-side reactor over the proxy.
     * Sends a scan request frame, receives response frames, and feeds
     * events into the local reactor.
     */
    suspend fun requestScanViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        request: PointcutScanRequest,
    ): PointcutRouteReport
}

expect fun pointcutProxyFacade(): PointcutProxyFacade
