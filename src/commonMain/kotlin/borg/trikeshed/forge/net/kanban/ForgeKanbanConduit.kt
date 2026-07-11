package borg.trikeshed.forge.net.kanban

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.FanoutEventSubscriber
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * CommonMain CCEK element that accepts generic fanout events, projects
 * Forge Kanban signals, and hands them to the installed sink service.
 */
class ForgeKanbanConduit(
    private val projector: ForgeKanbanSignalProjector = ForgeKanbanSignalProjector(),
    private val sink: ForgeKanbanSignalSink,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob), FanoutEventSubscriber {

    companion object Key : AsyncContextKey<ForgeKanbanConduit>()

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

    suspend fun accept(signal: ForgeKanbanSignal): Boolean =
        sink.accept(signal)

    override suspend fun close() {
        super.close()
    }
}

suspend fun openForgeKanbanConduit(
    sink: ForgeKanbanSignalSink,
    projector: ForgeKanbanSignalProjector = ForgeKanbanSignalProjector(),
    parentJob: Job? = null,
): ForgeKanbanConduit =
    ForgeKanbanConduit(
        projector = projector,
        sink = sink,
        parentJob = parentJob,
    ).also { it.open() }