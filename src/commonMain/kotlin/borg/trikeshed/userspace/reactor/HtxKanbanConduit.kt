package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * CommonMain HTX planning conduit.
 *
 * This is the root CCEK element that accepts generic fanout events, projects
 * HTX planning signals, and hands them to the installed sink service.
 */
class HtxKanbanConduit(
    private val projector: HtxPlanningSignalProjector = HtxPlanningSignalProjector(),
    private val sink: HtxPlanningSignalSink,
    parentJob: kotlinx.coroutines.Job? = null,
    private val ownedSupervisor: NioSupervisor? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob), FanoutEventSubscriber {
    companion object Key : AsyncContextKey<HtxKanbanConduit>()

    override val key: CoroutineContext.Key<*> get() = Key

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            state = ElementState.ACTIVE
        }
    }

    override suspend fun onFanoutEvent(event: FanoutEvent) {
        onEvent(event)
    }

    suspend fun onEvent(event: FanoutEvent): Boolean {
        val signal = projector.project(event) ?: return false
        return accept(signal)
    }

    suspend fun accept(signal: HtxPlanningSignal): Boolean =
        sink.accept(signal)

    override suspend fun close() {
        if (ownedSupervisor != null && ownedSupervisor.state.isLessThan(ElementState.CLOSED)) {
            ownedSupervisor.close()
        }
        super.close()
    }
}

suspend fun openHtxKanbanConduit(
    sink: HtxPlanningSignalSink,
    projector: HtxPlanningSignalProjector = HtxPlanningSignalProjector(),
    parentJob: kotlinx.coroutines.Job? = null,
): HtxKanbanConduit =
    HtxKanbanConduit(
        projector = projector,
        sink = sink,
        parentJob = parentJob,
    ).also { it.open() }

suspend fun openHtxKanbanConduit(
    nioSupervisor: NioSupervisor? = null,
    projector: HtxPlanningSignalProjector = HtxPlanningSignalProjector(),
    parentJob: kotlinx.coroutines.Job? = null,
): HtxKanbanConduit {
    val contextSupervisor = currentCoroutineContext()[NioSupervisor.Key]
    val activeSupervisor = nioSupervisor ?: contextSupervisor ?: NioSupervisor()
    val ownsSupervisor = nioSupervisor == null && contextSupervisor == null

    if (activeSupervisor.state == ElementState.CREATED) {
        activeSupervisor.open()
    }

    val sink = activeSupervisor.service<HtxPlanningSignalSink>()
        ?: error("HtxKanbanConduit requires HtxPlanningSignalSink in NioSupervisor. Open the supervisor before installing the HTX conduit.")

    return HtxKanbanConduit(
        projector = projector,
        sink = sink,
        parentJob = parentJob,
        ownedSupervisor = activeSupervisor.takeIf { ownsSupervisor },
    ).also { it.open() }
}
