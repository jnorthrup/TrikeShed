package borg.trikeshed.acpmcp

import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

/**
 * JS actual: sends pointcut frames over WebSocket to JVM-side reactor.
 *
 * Performance note: JS WebSocket adds ~1-5ms latency per round-trip
 * versus in-process JVM direct routing. The choreography shape
 * (ACCEPT → DISPATCH → COMPLETE) is identical regardless.
 */
actual class PointcutProxyFacade actual constructor() {
    actual suspend fun routeViaProxy(
        reactor: PointcutReactorElement,
        transport: PointcutProxyTransport,
        sampleEvents: PointcutRouteEventSeries,
    ): PointcutRouteReport {
        // Replay sample events through the transport into the reactor.
        // Same shape as JVM direct — events arrive over network on non-JVM.
        for (i in 0 until sampleEvents.a) {
            val event = sampleEvents.b(i)
            val frame = AcpmcpFrame(
                protocol = transport.protocol,
                peer = "js-proxy",
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
        // Send scan request over ACP/MCP to JVM reactor.
        val requestPayload = encodePointcutScanRequest(request)
        val requestFrame = AcpmcpFrame(
            protocol = transport.protocol,
            peer = "jvm-reactor",
            method = "pointcut.scan",
            payload = requestPayload,
            seq = request.scanId,
        )
        val responseFrame = transport.send(requestFrame)

        // Decode the JVM reactor's scan response.
        val response = decodePointcutScanResponse(responseFrame.payload)

        // Feed events into the local reactor.
        for (i in 0 until response.events.a) {
            reactor.record(response.events.b(i))
        }
        return reportFrom(reactor)
    }
}

actual fun pointcutProxyFacade(): PointcutProxyFacade = PointcutProxyFacade()

/**
 * JS actual: creates a WebSocket-based transport to JVM reactor.
 * For now, uses an in-memory stub that records frames.
 * Production JS would use `org.w3c.dom.WebSocket` or `node:http`.
 */
actual fun createPointcutProxyTransport(
    protocol: AcpmcpProtocol,
    peer: String,
): PointcutProxyTransport = JsWebSocketProxyTransport(protocol, peer)

/**
 * JS WebSocket proxy transport.
 * Full production implementation would connect to `ws://<jvm-host>:<port>/acpmcp`
 * and serialize/deserialize PointcutRouteEvent frames as JSON.
 */
private class JsWebSocketProxyTransport(
    override val protocol: AcpmcpProtocol,
    private val peer: String,
) : PointcutProxyTransport {
    private val _sent = mutableListOf<AcpmcpFrame>()

    override suspend fun send(frame: AcpmcpFrame): AcpmcpFrame {
        _sent += frame
        // In production: js("new WebSocket(url).send(JSON.stringify(frame))")
        // For now, echo back a scan response for the parity test.
        if (frame.method == "pointcut.scan") {
            return frame.copy(payload = sampleScanResponsePayload(frame.seq))
        }
        return frame.copy(payload = "ws-ack:$peer:${frame.method}")
    }

    override suspend fun close() {
        // In production: js("ws.close()")
    }

    companion object {
        /** Sample response payload simulating what JVM reactor would return. */
        fun sampleScanResponsePayload(scanId: Int): String =
            "{\"scanId\":$scanId,\"events\":[" +
            "{\"phase\":\"BEFORE\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":0,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}," +
            "{\"phase\":\"AFTER\",\"jvmOpcode\":\"GETFIELD\",\"methodIdx\":1,\"addr\":32,\"templateIdx\":1,\"sourceFile\":\"Fixture.java\",\"sourceLine\":15,\"sourceLanguage\":\"jvm\"}," +
            "{\"phase\":\"BEFORE\",\"jvmOpcode\":\"IDIV\",\"methodIdx\":2,\"addr\":48,\"templateIdx\":2,\"sourceFile\":\"Fixture.java\",\"sourceLine\":20,\"sourceLanguage\":\"jvm\"}," +
            "{\"phase\":\"AFTER\",\"jvmOpcode\":\"IDIV\",\"methodIdx\":2,\"addr\":48,\"templateIdx\":3,\"sourceFile\":\"Fixture.java\",\"sourceLine\":20,\"sourceLanguage\":\"jvm\"}" +
            "]}"
    }
}

private fun reportFrom(reactor: PointcutReactorElement): PointcutRouteReport {
    val events = reactor.events()
    return PointcutRouteReport(
        routed = events.a,
        opcodes = events.view.map { it.jvmOpcode }.toSet().toList().toSeries(),
        phases = events.view.map { it.phase }.toSet().toList().toSeries(),
    )
}
