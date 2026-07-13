package borg.trikeshed.couch

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.coroutines.CoroutineContext

sealed interface CouchReportEvent {
    val timestampMs: Long

    data class MapEmitted(
        val viewName: String,
        val docId: String,
        val key: String,
        override val timestampMs: Long = nowMs(),
    ) : CouchReportEvent

    data class Reduced(
        val viewName: String,
        val count: Long,
        override val timestampMs: Long = nowMs(),
    ) : CouchReportEvent

    data class PointcutObserved(
        val vmFacet: String,
        val coordinate: String,
        val propertyName: String,
        val newValue: String,
        override val timestampMs: Long = nowMs(),
    ) : CouchReportEvent
}

data class CouchReportState(
    val mapEmissions: Long = 0,
    val reductions: Long = 0,
    val pointcuts: Long = 0,
    val lastViewName: String = "",
    val lastTimestampMs: Long = 0,
)

/** CCEK owner for report execution and pointcut observation. */
class CouchReportReactorElement(
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<CouchReportReactorElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val _events = MutableSharedFlow<CouchReportEvent>(
        replay = 256,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CouchReportEvent> = _events.asSharedFlow()

    private val _reportState = MutableStateFlow(CouchReportState())
    val reportState: StateFlow<CouchReportState> = _reportState.asStateFlow()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    fun ingest(event: CouchReportEvent) {
        _reportState.value = when (event) {
            is CouchReportEvent.MapEmitted -> _reportState.value.copy(
                mapEmissions = _reportState.value.mapEmissions + 1,
                lastViewName = event.viewName,
                lastTimestampMs = event.timestampMs,
            )
            is CouchReportEvent.Reduced -> _reportState.value.copy(
                reductions = _reportState.value.reductions + 1,
                lastViewName = event.viewName,
                lastTimestampMs = event.timestampMs,
            )
            is CouchReportEvent.PointcutObserved -> _reportState.value.copy(
                pointcuts = _reportState.value.pointcuts + 1,
                lastTimestampMs = event.timestampMs,
            )
        }
        _events.tryEmit(event)
    }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
