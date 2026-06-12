package borg.trikeshed.acpmcp

import borg.trikeshed.lib.toSeries

/**
 * JS actual for ClassfilePointcutReactorFacade.
 *
 * JS cannot scan JVM classfiles directly — it routes through the
 * ACP/MCP network proxy to a JVM-side reactor that performs the
 * actual classfile scanning and returns pointcut events.
 *
 * The facade sends a PointcutScanRequest over the proxy transport,
 * receives a scan response, and feeds events into the local reactor.
 */
actual class ClassfilePointcutReactorFacade actual constructor() {
    actual suspend fun routeJvmValuePointcuts(reactor: PointcutReactorElement): PointcutRouteReport {
        // JS cannot run Jep484ClassfileScanner — route through ACP/MCP proxy.
        val transport = createPointcutProxyTransport(AcpmcpProtocol.ACP, "classfile-reactor")
        val facade = pointcutProxyFacade()
        val request = PointcutScanRequest(scanId = 1, opcodeFilter = "value", language = "jvm")
        return try {
            facade.requestScanViaProxy(reactor, transport, request)
        } finally {
            transport.close()
        }
    }
}

actual fun classfilePointcutReactorFacade(): ClassfilePointcutReactorFacade =
    ClassfilePointcutReactorFacade()

/**
 * JS codec: decodes scan response payload into events.
 * Uses regex-based JSON parsing to avoid kotlinx.serialization dependency.
 */
actual fun decodePointcutScanResponse(payload: String): PointcutScanResponse {
    val scanIdMatch = Regex("\"scanId\"\\s*:\\s*(\\d+)").find(payload)
    val scanId = scanIdMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

    val eventRegex = Regex(
        "\\{\"phase\"\\s*:\\s*\"(BEFORE|AFTER)\"\\s*,\\s*\"jvmOpcode\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*" +
        "\"methodIdx\"\\s*:\\s*(\\d+)\\s*,\\s*\"addr\"\\s*:\\s*(\\d+)\\s*,\\s*" +
        "\"templateIdx\"\\s*:\\s*(\\d+)\\s*,\\s*\"sourceFile\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*" +
        "\"sourceLine\"\\s*:\\s*(\\d+)\\s*,\\s*\"sourceLanguage\"\\s*:\\s*\"([^\"]*)\"\\s*\\}"
    )
    val events = eventRegex.findAll(payload).map { match ->
        PointcutRouteEvent(
            phase = PointcutRoutePhase.valueOf(match.groupValues[1]),
            jvmOpcode = match.groupValues[2],
            methodIdx = match.groupValues[3].toInt(),
            addr = match.groupValues[4].toInt(),
            templateIdx = match.groupValues[5].toInt(),
            sourceFile = match.groupValues[6],
            sourceLine = match.groupValues[7].toInt(),
            sourceLanguage = match.groupValues[8],
        )
    }.toList().toSeries()
    return PointcutScanResponse(scanId, events)
}

actual fun encodePointcutScanRequest(request: PointcutScanRequest): String =
    "{\"scanId\":${request.scanId},\"opcodeFilter\":\"${request.opcodeFilter}\",\"language\":\"${request.language}\"}"
