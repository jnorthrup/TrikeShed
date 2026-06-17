package borg.trikeshed.acpmcp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import kotlin.coroutines.CoroutineContext

enum class AcpmcpProtocol {
    ACP,
    MCP,
}

enum class ReactorChoreographyPhase {
    ACCEPT,
    DISPATCH,
    COMPLETE,
}

data class AcpmcpCall(
    val protocol: AcpmcpProtocol,
    val peer: String,
    val method: String,
    val payload: String,
) {
    fun frame(seq: Int): AcpmcpFrame = AcpmcpFrame(
        protocol = protocol,
        peer = peer,
        method = method,
        payload = payload,
        seq = seq,
    )

    companion object {
        fun acp(session: String, method: String, payload: String): AcpmcpCall = AcpmcpCall(
            protocol = AcpmcpProtocol.ACP,
            peer = session,
            method = method,
            payload = payload,
        )

        fun mcp(server: String, method: String, payload: String): AcpmcpCall = AcpmcpCall(
            protocol = AcpmcpProtocol.MCP,
            peer = server,
            method = method,
            payload = payload,
        )
    }
}

data class AcpmcpFrame(
    val protocol: AcpmcpProtocol,
    val peer: String,
    val method: String,
    val payload: String,
    val seq: Int,
)

data class ReactorChoreographyStep(
    val phase: ReactorChoreographyPhase,
    val frame: AcpmcpFrame,
    val resultPayload: String? = null,
)

typealias AcpmcpCallSeries = Series<AcpmcpCall>
typealias AcpmcpFrameSeries = Series<AcpmcpFrame>
typealias ReactorChoreography = Series<ReactorChoreographyStep>

interface AcpmcpEndpoint {
    val protocol: AcpmcpProtocol
    suspend fun invoke(frame: AcpmcpFrame): AcpmcpFrame
}

class AcpmcpReactorElement(
    private val subscribers: List<AcpmcpReactorElement> = emptyList(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<AcpmcpReactorElement>()

    override val key: CoroutineContext.Key<*> get() = Key
    override val fanoutSubscribers: List<AsyncContextElement> get() = subscribers

    private val frameLog = mutableListOf<AcpmcpFrame>()
    private val choreographyLog = mutableListOf<ReactorChoreographyStep>()

    fun accept(frame: AcpmcpFrame): ReactorChoreographyStep {
        if (frameLog.none { it.seq == frame.seq }) {
            frameLog += frame
        }
        return record(ReactorChoreographyStep(ReactorChoreographyPhase.ACCEPT, frame))
    }

    fun dispatch(frame: AcpmcpFrame): ReactorChoreographyStep = record(
        ReactorChoreographyStep(ReactorChoreographyPhase.DISPATCH, frame),
    )

    fun complete(frame: AcpmcpFrame, resultPayload: String): ReactorChoreographyStep = record(
        ReactorChoreographyStep(ReactorChoreographyPhase.COMPLETE, frame, resultPayload),
    )

    fun frames(): AcpmcpFrameSeries = frameLog.toList().toSeries()

    fun choreography(): ReactorChoreography = choreographyLog.toList().toSeries()

    private fun record(step: ReactorChoreographyStep): ReactorChoreographyStep {
        choreographyLog += step
        fanoutSubscribers.forEach { subscriber ->
            (subscriber as? AcpmcpReactorElement)?.receiveFanout(step)
        }
        return step
    }

    private fun receiveFanout(step: ReactorChoreographyStep) {
        if (step.phase == ReactorChoreographyPhase.ACCEPT && frameLog.none { it.seq == step.frame.seq }) {
            frameLog += step.frame
        }
        choreographyLog += step
    }
}

/**
 * General API shell bridging ACP and MCP access to TrikeShed reactor choreography.
 *
 * Dispatches interleaved ACP/MCP calls through the reactor lifecycle
 * (ACCEPT → DISPATCH → COMPLETE) and returns the full choreography.
 *
 * Optionally integrates with the pointcut reactor facade for classfile
 * harness pointcutting events routed through the same choreography.
 */
class AcpmcpShell(
    private val reactor: AcpmcpReactorElement,
    private val acp: AcpmcpEndpoint,
    private val mcp: AcpmcpEndpoint,
) {
    /**
     * Dispatch a series of ACP/MCP calls through reactor choreography.
     * Each call goes through ACCEPT → DISPATCH → endpoint.invoke → COMPLETE.
     */
    suspend fun dispatch(calls: AcpmcpCallSeries): ReactorChoreography {
        for (i in 0 until calls.a) {
            val frame = calls.b(i).frame(i)
            val endpoint = endpointFor(frame.protocol)
            reactor.accept(frame)
            reactor.dispatch(frame)
            val result = endpoint.invoke(frame)
            reactor.complete(frame, result.payload)
        }
        return reactor.choreography()
    }

    /**
     * Dispatch a pointcut scan request through ACP reactor choreography.
     * Bridges classfile harness pointcutting into the same ACCEPT → DISPATCH → COMPLETE
     * lifecycle as regular ACP/MCP calls.
     *
     * The scan request is framed as an ACP call, dispatched to the endpoint,
     * and the result is routed through the pointcut reactor element.
     */
    suspend fun dispatchPointcutScan(
        pointcutReactor: PointcutReactorElement,
        request: PointcutScanRequest,
        pointcutFacade: ClassfilePointcutReactorFacade = classfilePointcutReactorFacade(),
    ): Pair<ReactorChoreography, PointcutRouteReport> {
        // Frame the pointcut scan as an ACP call for reactor choreography.
        val scanPayload = encodePointcutScanRequest(request)
        val call = AcpmcpCall.acp("classfile-reactor", "pointcut.scan", scanPayload)
        val frame = call.frame(request.scanId)

        reactor.accept(frame)
        reactor.dispatch(frame)

        // Execute the platform-specific pointcut scan.
        val report = pointcutFacade.routeJvmValuePointcuts(pointcutReactor)

        reactor.complete(frame, "{\"routed\":${report.routed}}")
        return reactor.choreography() to report
    }

    private fun endpointFor(protocol: AcpmcpProtocol): AcpmcpEndpoint = when (protocol) {
        AcpmcpProtocol.ACP -> acp
        AcpmcpProtocol.MCP -> mcp
    }
}
