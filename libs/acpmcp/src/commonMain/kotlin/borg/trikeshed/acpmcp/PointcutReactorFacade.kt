package borg.trikeshed.acpmcp

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import kotlin.coroutines.CoroutineContext

enum class PointcutRoutePhase {
    BEFORE,
    AFTER,
}

data class PointcutRouteEvent(
    val phase: PointcutRoutePhase,
    val jvmOpcode: String,
    val methodIdx: Int,
    val addr: Int,
    val templateIdx: Int,
    val sourceFile: String,
    val sourceLine: Int,
    val sourceLanguage: String,
)

typealias PointcutRouteEventSeries = Series<PointcutRouteEvent>

data class PointcutRouteReport(
    val routed: Int,
    val opcodes: Series<String>,
    val phases: Series<PointcutRoutePhase>,
)

/**
 * Protocol-level request frame for pointcut scan operations.
 * JS sends this to JVM-side reactor over the proxy transport;
 * JVM-side responds with a series of PointcutRouteEvent frames.
 */
data class PointcutScanRequest(
    val scanId: Int,
    val opcodeFilter: String = "value",
    val language: String = "jvm",
)

/**
 * Protocol-level response from JVM reactor after a pointcut scan.
 * Carries the events back to the non-JVM caller.
 */
data class PointcutScanResponse(
    val scanId: Int,
    val events: PointcutRouteEventSeries,
)

class PointcutReactorElement(
    private val downstream: List<PointcutReactorElement> = emptyList(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<PointcutReactorElement>()

    override val key: CoroutineContext.Key<*> get() = Key
    override val fanoutSubscribers: List<AsyncContextElement> get() = downstream

    private val eventLog = mutableListOf<PointcutRouteEvent>()

    fun record(event: PointcutRouteEvent): PointcutRouteEvent {
        eventLog += event
        fanoutSubscribers.forEach { subscriber ->
            (subscriber as? PointcutReactorElement)?.receiveFanout(event)
        }
        return event
    }

    fun events(): PointcutRouteEventSeries = eventLog.toList().toSeries()

    private fun receiveFanout(event: PointcutRouteEvent) {
        eventLog += event
    }
}

/**
 * Platform facade for routing JVM classfile harness pointcuts into a reactor.
 *
 * On JVM: scans classfiles directly using Jep484ClassfileScanner.
 * On JS/native: routes through ACP/MCP proxy to JVM-side reactor.
 * 
 * Use ClassfilePointcutReactorFacadeJvm for JVM implementation.
 */
open class ClassfilePointcutReactorFacade {
    open suspend fun routeJvmValuePointcuts(reactor: PointcutReactorElement): PointcutRouteReport {
        throw NotImplementedError("Override in platform implementation")
    }
}

/**
 * Factory to create the platform-specific implementation.
 * On JVM: returns ClassfilePointcutReactorFacadeJvm
 */
fun classfilePointcutReactorFacade(): ClassfilePointcutReactorFacade = ClassfilePointcutReactorFacade()

/**
 * Decode a PointcutScanResponse payload (JSON) into events.
 */
fun decodePointcutScanResponse(payload: String): PointcutScanResponse {
    // Platform-specific implementation
    throw NotImplementedError("Override in platform implementation")
}

/**
 * Encode a PointcutScanRequest into a payload string (JSON).
 */
fun encodePointcutScanRequest(request: PointcutScanRequest): String {
    throw NotImplementedError("Override in platform implementation")
}
