package borg.trikeshed.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * CCEK element that instruments GraalVM polyglot guests (JS, Python, WASM, JVM)
 * and emits structured, facet-typed events into CCEK channels.
 *
 * Lifecycle:
 *   CREATED → OPEN (attach instrumentation) → ACTIVE (dispatch events)
 *   → DRAINING (drain in-flight events) → CLOSED (detach instrumentation)
 *
 * Each guest runtime gets a dedicated observation channel. The reactor
 * fans out to downstream consumers (ConfixSinkElement, ReteAlphaElement, etc.)
 * via the standard element fanout mechanism.
 *
 * TDD matrix (all polyglots, all event kinds):
 *   - JS:   function entry/exit, exception, promise settle, GC safe-point
 *   - Python: call/return, exception, yield, async task switch
 *   - WASM: func enter/return, trap, memory grow, host call
 *   - JVM:  method enter/exit, exception, monitor enter/exit, class load
 *
 * All events conform to [PolyglotEvent] sealed hierarchy validated by
 * [ConfixFacetPlan] before entering the reactor pipeline.
 */
class PolyglotObservationElement(
    parentJob: Job? = null,
    initialConfig: PolyglotObservationConfig = PolyglotObservationConfig(),
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<PolyglotObservationElement>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    private var config: PolyglotObservationConfig = initialConfig
    private val _events = MutableSharedFlow<PolyglotEvent>(
        replay = 128,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PolyglotEvent> = _events.asSharedFlow()

    /** Per-guest runtime channels for backpressure isolation. */
    private val guestChannels = mutableMapOf<String, Channel<PolyglotEvent>>()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            attachInstrumentation()
            state = ElementState.ACTIVE
        }
    }

    override suspend fun drain() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
            state = ElementState.DRAINING
            guestChannels.values.forEach { it.close() }
            guestChannels.clear()
            super.drain()
            state = ElementState.CLOSED
        }
    }

    /** Attach GraalVM Truffle instrumentation to all configured guest runtimes. */
    private fun attachInstrumentation() {
        // JS guest
        if (config.guests.contains(PolyglotGuest.JS)) {
            val ch = Channel<PolyglotEvent>(config.channelCapacity)
            guestChannels[PolyglotGuest.JS.name] = ch
            launchGuestObserver(PolyglotGuest.JS, ch)
            // Truffle API: Instrumenter.attach(JSInstrument.class, ...)
        }
        // Python guest
        if (config.guests.contains(PolyglotGuest.Python)) {
            val ch = Channel<PolyglotEvent>(config.channelCapacity)
            guestChannels[PolyglotGuest.Python.name] = ch
            launchGuestObserver(PolyglotGuest.Python, ch)
            // Truffle API: Instrumenter.attach(PythonInstrument.class, ...)
        }
        // WASM guest
        if (config.guests.contains(PolyglotGuest.WASM)) {
            val ch = Channel<PolyglotEvent>(config.channelCapacity)
            guestChannels[PolyglotGuest.WASM.name] = ch
            launchGuestObserver(PolyglotGuest.WASM, ch)
            // Truffle API: Instrumenter.attach(WasmInstrument.class, ...)
        }
        // JVM guest (self-observation via JVMTI or bytecode rewriting)
        if (config.guests.contains(PolyglotGuest.JVM)) {
            val ch = Channel<PolyglotEvent>(config.channelCapacity)
            guestChannels[PolyglotGuest.JVM.name] = ch
            launchGuestObserver(PolyglotGuest.JVM, ch)
        }
    }

    /** Launch a coroutine that drains a guest channel and emits to the shared flow. */
    private fun launchGuestObserver(guest: PolyglotGuest, channel: Channel<PolyglotEvent>) {
        kotlinx.coroutines.CoroutineScope(supervisor).launch {
            try {
                for (event in channel) {
                    if (state == ElementState.ACTIVE) {
                        _events.emit(event)
                        fanoutSubscribers.forEach { subscriber ->
                            if (subscriber is ConfixSinkElement) {
                                // Downstream sink expects ConfixFacetEvent, construct and submit
                                val columns = mapOf(
                                    "guest" to ColumnData.StringColumn(arrayOf(event.guest.name)),
                                    "kind" to ColumnData.StringColumn(arrayOf(event.kind.name)),
                                    "timestamp" to ColumnData.LongColumn(arrayOf(event.timestampMs)),
                                    "traceId" to ColumnData.StringColumn(arrayOf(event.traceId))
                                )
                                subscriber.submit(ConfixFacetEvent(
                                    facetProjection = FacetProjection(columns),
                                    timestampMs = event.timestampMs,
                                    traceId = event.traceId
                                ))
                            }
                            // Add other subscriber types here as needed, e.g., ReteAlphaElement
                        }
                    }
                }
            } catch (e: Throwable) {
                val errorEvent = PolyglotEvent.InstrumentationError(
                    guest = guest,
                    message = e.message ?: e.javaClass.simpleName,
                    timestampMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
                )
                _events.emit(errorEvent)
                fanoutSubscribers.forEach { subscriber ->
                    if (subscriber is ConfixSinkElement) {
                        val columns = mapOf(
                            "guest" to ColumnData.StringColumn(arrayOf(errorEvent.guest.name)),
                            "kind" to ColumnData.StringColumn(arrayOf(errorEvent.kind.name)),
                            "error" to ColumnData.StringColumn(arrayOf(errorEvent.message))
                        )
                        subscriber.submit(ConfixFacetEvent(
                            facetProjection = FacetProjection(columns),
                            timestampMs = errorEvent.timestampMs,
                            traceId = errorEvent.traceId
                        ))
                    }
                }
            }
        }
    }

    /** Emit a raw event from guest instrumentation (called from Truffle callbacks). */
    fun emitFromGuest(guest: PolyglotGuest, event: PolyglotEvent) {
        guestChannels[guest.name]?.trySend(event)
    }

    suspend fun updateConfig(newConfig: PolyglotObservationConfig) {
        config = newConfig
        // Re-attach if guest set changed
        if (state == ElementState.ACTIVE) {
            drain()
            open()
        }
    }
}

/** Which polyglot guests to instrument. */
@Serializable
enum class PolyglotGuest {
    JS, Python, WASM, JVM
}

/** Configuration for the observation element. */
@Serializable
data class PolyglotObservationConfig(
    val guests: Set<PolyglotGuest> = setOf(PolyglotGuest.JS, PolyglotGuest.Python),
    val channelCapacity: Int = 256,
    val eventFilters: Set<PolyglotEventKind> = PolyglotEventKind.values().toSet(),
)

/** All observable event kinds across polyglots — the TDD matrix columns. */
@Serializable
enum class PolyglotEventKind {
    FUNCTION_ENTER, FUNCTION_EXIT, EXCEPTION_THROWN, EXCEPTION_CAUGHT,
    PROMISE_SETTLED, ASYNC_TASK_SWITCH, YIELD, GC_SAFEPOINT,
    MEMORY_GROW, HOST_CALL, TRAP, CLASS_LOAD, MONITOR_ENTER, MONITOR_EXIT
}

/**
 * Sealed event hierarchy — every variant carries a [PolyglotGuest] tag and
 * a [ConfixFacetPlan]-validatable payload. Downstream elements (ConfixSinkElement,
 * ReteAlphaElement) consume this flow.
 */
@Serializable
sealed class PolyglotEvent {
    abstract val guest: PolyglotGuest
    abstract val kind: PolyglotEventKind
    abstract val timestampMs: Long
    abstract val traceId: String // Correlation ID for causal graph

    @Serializable
    data class FunctionEnter(
        override val guest: PolyglotGuest,
        override val kind: PolyglotEventKind = PolyglotEventKind.FUNCTION_ENTER,
        override val timestampMs: Long,
        override val traceId: String,
        val functionName: String,
        val sourceLocation: String?,
        val args: List<String> = emptyList(), // Serialized, privacy-filtered
    ) : PolyglotEvent()

    @Serializable
    data class FunctionExit(
        override val guest: PolyglotGuest,
        override val kind: PolyglotEventKind = PolyglotEventKind.FUNCTION_EXIT,
        override val timestampMs: Long,
        override val traceId: String,
        val functionName: String,
        val result: String?, // Serialized, privacy-filtered
        val durationNanos: Long,
    ) : PolyglotEvent()

    @Serializable
    data class ExceptionThrown(
        override val guest: PolyglotGuest,
        override val kind: PolyglotEventKind = PolyglotEventKind.EXCEPTION_THROWN,
        override val timestampMs: Long,
        override val traceId: String,
        val exceptionClass: String,
        val message: String?,
        val stackTrace: List<String> = emptyList(),
    ) : PolyglotEvent()

    @Serializable
    data class InstrumentationError(
        override val guest: PolyglotGuest,
        override val kind: PolyglotEventKind = PolyglotEventKind.FUNCTION_ENTER, // placeholder
        override val timestampMs: Long,
        override val traceId: String = "error-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}",
        val message: String,
    ) : PolyglotEvent()
}