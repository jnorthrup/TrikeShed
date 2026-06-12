package borg.trikeshed.acpmcp

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/**
 * JVM actual: pointcut proxy transport.
 *
 * On JVM, the classfile harness is local — the "proxy" is just
 * a direct in-process pass-through. This actual exists so the
 * commonTest choreography parity test compiles and runs on JVM too.
 */
actual fun createPointcutProxyTransport(
    protocol: AcpmcpProtocol,
    peer: String,
): PointcutProxyTransport = DirectJvmProxyTransport(protocol, peer)

private class DirectJvmProxyTransport(
    override val protocol: AcpmcpProtocol,
    private val peer: String,
) : PointcutProxyTransport {
    override suspend fun send(frame: AcpmcpFrame): AcpmcpFrame =
        frame.copy(payload = "direct:$peer:${frame.method}")

    override suspend fun close() {}
}

actual class PointcutProxyFacade actual constructor() {
    actual suspend fun routeViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        sampleEvents: PointcutRouteEventSeries,
    ): PointcutRouteReport {
        for (i in 0 until sampleEvents.a) {
            val event = sampleEvents.b(i)
            val frame = AcpmcpFrame(
                protocol = transport.protocol,
                peer = "jvm-direct",
                method = "pointcut.${event.jvmOpcode}.${event.phase.name}",
                payload = "${event.sourceFile}:${event.sourceLine}",
                seq = event.methodIdx,
            )
            transport.send(frame)
            reactor.record(event)
        }
        return reportFrom(reactor)
    }

    actual suspend fun requestScanViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        request: PointcutScanRequest,
    ): PointcutRouteReport {
        // On JVM, delegate directly to the classfile facade.
        // This is the in-process shortcut — no network round-trip needed.
        val facade = classfilePointcutReactorFacade()
        return facade.routeJvmValuePointcuts(reactor)
    }
}

actual fun pointcutProxyFacade(): PointcutProxyFacade = PointcutProxyFacade()

private fun reportFrom(reactor: PointcutReactorElement): PointcutRouteReport {
    val events = reactor.events()
    return PointcutRouteReport(
        routed = events.a,
        opcodes = events.view.map { it.jvmOpcode }.toSet().toList().toSeries(),
        phases = events.view.map { it.phase }.toSet().toList().toSeries(),
    )
}
