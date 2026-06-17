package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.FanoutDispatcherElement
import borg.trikeshed.userspace.FanoutEvent
import borg.trikeshed.userspace.UringCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Registration handle for the HTX -> Hermes Kanban conduit.
 */
data class InstalledHtxKanbanConduit(
    val conduit: HtxKanbanConduit,
    val handler: (FanoutEvent) -> Unit,
)

fun CoroutineScope.installHtxKanbanConduit(
    fanout: FanoutDispatcherElement,
    conduit: HtxKanbanConduit,
    eventType: Int = HTX_PLANNING_EVENT_TYPE,
): InstalledHtxKanbanConduit {
    val handler: (FanoutEvent) -> Unit = { event ->
        launch {
            conduit.onEvent(event)
        }
    }
    // Adapt FanoutEvent handler to UringCompletion handler expected by FanoutDispatcherElement
    val adaptedHandler: (UringCompletion) -> Unit = { completion ->
        // Convert UringCompletion to FanoutEvent if possible
        // For now, just ignore - this is a placeholder
    }
    fanout.registerHandler(eventType.toLong(), adaptedHandler)
    return InstalledHtxKanbanConduit(conduit, handler)
}

fun CoroutineScope.installHtxKanbanConduit(
    fanout: FanoutDispatcherElement,
    workspaceRoot: Path = Paths.get(".").toAbsolutePath().normalize(),
    board: String = "tshed",
    assignee: String = "kanban-worker",
    projector: HtxPlanningSignalProjector = HtxPlanningSignalProjector(),
): InstalledHtxKanbanConduit {
    val hermes = HermesKanbanCli(
        workspaceRoot = workspaceRoot,
        board = board,
        assignee = assignee,
    )
    return installHtxKanbanConduit(
        fanout = fanout,
        conduit = HtxKanbanConduit(projector = projector, hermes = hermes),
        eventType = HTX_PLANNING_EVENT_TYPE,
    )
}
