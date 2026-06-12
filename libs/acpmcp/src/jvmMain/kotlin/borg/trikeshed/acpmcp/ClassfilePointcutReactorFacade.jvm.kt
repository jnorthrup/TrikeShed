package borg.trikeshed.acpmcp

import borg.trikeshed.classfile.pointcut.JvmPointcutCommand
import borg.trikeshed.classfile.pointcut.JvmValuePointcutFixture
import borg.trikeshed.classfile.pointcut.PointcutPhase
import borg.trikeshed.classfile.pointcut.RecordingPointcutSink
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view

actual class ClassfilePointcutReactorFacade actual constructor() {
    actual suspend fun routeJvmValuePointcuts(reactor: PointcutReactorElement): PointcutRouteReport {
        val sink = RecordingPointcutSink()
        val activations = JvmPointcutCommand().execute(JvmValuePointcutFixture.allValueOpcodes(), sink)
        for (i in 0 until activations.a) {
            reactor.record(activations.b(i).toRouteEvent())
        }
        val events = reactor.events()
        return PointcutRouteReport(
            routed = events.a,
            opcodes = events.view.map { it.jvmOpcode }.toSet().toList().toSeries(),
            phases = events.view.map { it.phase }.toSet().toList().toSeries(),
        )
    }

    private fun borg.trikeshed.classfile.pointcut.PointcutActivation.toRouteEvent(): PointcutRouteEvent = PointcutRouteEvent(
        phase = when (phase) {
            PointcutPhase.BEFORE -> PointcutRoutePhase.BEFORE
            PointcutPhase.AFTER -> PointcutRoutePhase.AFTER
        },
        jvmOpcode = coordinate.jvmOpcode,
        methodIdx = methodIdx,
        addr = addr,
        templateIdx = templateIdx,
        sourceFile = coordinate.source.sourceFile,
        sourceLine = coordinate.source.line,
        sourceLanguage = coordinate.source.language,
    )
}

actual fun classfilePointcutReactorFacade(): ClassfilePointcutReactorFacade = ClassfilePointcutReactorFacade()

/** JVM codec: encodes/decodes scan requests and responses as JSON strings. */
actual fun decodePointcutScanResponse(payload: String): PointcutScanResponse {
    // Minimal JSON parsing for scan responses on JVM.
    // Format: {"scanId":N,"events":[{"phase":"BEFORE","jvmOpcode":"GETFIELD",...},...]}
    val scanIdMatch = Regex("\"scanId\"\\s*:\\s*(\\d+)").find(payload)
    val scanId = scanIdMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

    val eventRegex = Regex(
        """\{"phase"\s*:\s*"(BEFORE|AFTER)"\s*,\s*"jvmOpcode"\s*:\s*"([^"]+)"\s*,\s*""" +
        """"methodIdx"\s*:\s*(\d+)\s*,\s*"addr"\s*:\s*(\d+)\s*,\s*""" +
        """"templateIdx"\s*:\s*(\d+)\s*,\s*"sourceFile"\s*:\s*"([^"]*)"\s*,\s*""" +
        """"sourceLine"\s*:\s*(\d+)\s*,\s*"sourceLanguage"\s*:\s*"([^"]*)"\s*\}"""
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
