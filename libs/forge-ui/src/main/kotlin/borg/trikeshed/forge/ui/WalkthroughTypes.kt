package borg.trikeshed.forge.ui

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId
import kotlinx.serialization.Serializable

/**
 * Teach-pendant walkthrough script system.
 *
 * A WalkthroughScript is a declarative sequence of timed steps that drive
 * the Forge board FSM. The WalkthroughPlayer replays them, the ScreenRecorder
 * captures frames, and ffmpeg assembles the final video.
 *
 * Robot use-case: record a human operator's session → replay it headlessly →
 * generate a self-documenting video artefact for training/audit.
 */
@Serializable
data class WalkthroughScript(
    val id: WalkthroughScriptId,
    val title: String,
    val description: String = "",
    /** Steps in execution order. Each step fires after the previous completes. */
    val steps: List<WalkthroughStep>,
    /** Target frames-per-second for the output video. */
    val targetFps: Int = 30,
    /** Width × height of the capture window in pixels. */
    val captureWidth: Int = 1280,
    val captureHeight: Int = 800,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WalkthroughScriptId(val value: String) {
    companion object {
        fun generate(): WalkthroughScriptId =
            WalkthroughScriptId("walkthrough-${System.currentTimeMillis().toString(16)}")
    }
}

/**
 * A single named step in a walkthrough.
 *
 * Every step has:
 *  - a [delayMs] pause *before* the action fires (gives the recording
 *    time to show the previous state)
 *  - a [holdMs] pause *after* the action fires (lets the viewer read
 *    the result before the next transition)
 *  - a [narration] string that appears as a subtitle overlay on the video
 *  - an [action] that describes what to do to the board
 */
@Serializable
data class WalkthroughStep(
    val id: String,
    val narration: String = "",
    /** Pause before firing the action (ms). Captures the "before" state. */
    val delayMs: Long = 800,
    /** Hold after firing the action (ms). Captures the "after" state. */
    val holdMs: Long = 1200,
    val action: WalkthroughAction,
)

/**
 * Actions the player can drive. Each maps to one or more ForgeBoardFSM events.
 */
@Serializable
sealed class WalkthroughAction {

    /** Display a narration card — no board mutation; just shows text. */
    @Serializable
    data class Narrate(val text: String) : WalkthroughAction()

    /** Load a fresh default board (wipes current state). */
    @Serializable
    data object LoadDefaultBoard : WalkthroughAction()

    /** Create a new card in a named column. */
    @Serializable
    data class CreateCard(
        val title: String,
        val columnId: KanbanColumnId,
        val priority: CardPriority = CardPriority.MEDIUM,
        val assignee: String? = null,
        val description: String = "",
    ) : WalkthroughAction()

    /** Move an existing card by its title match (first match in board). */
    @Serializable
    data class MoveCardByTitle(
        val title: String,
        val toColumnId: KanbanColumnId,
    ) : WalkthroughAction()

    /** Move a card by explicit id. */
    @Serializable
    data class MoveCard(
        val cardId: KanbanCardId,
        val toColumnId: KanbanColumnId,
    ) : WalkthroughAction()

    /** Simulate a drag gesture — fires DragStarted → DragOver → DragDropped. */
    @Serializable
    data class DragCard(
        val cardId: KanbanCardId,
        val fromColumnId: KanbanColumnId,
        val toColumnId: KanbanColumnId,
        /** Milliseconds to animate the drag for visual effect. */
        val animationMs: Long = 600,
    ) : WalkthroughAction()

    /** Delete a card by title. */
    @Serializable
    data class DeleteCardByTitle(val title: String) : WalkthroughAction()

    /** Pause for a fixed duration with optional text. */
    @Serializable
    data class Pause(val durationMs: Long, val text: String = "") : WalkthroughAction()

    /** Select a different board by id. */
    @Serializable
    data class SelectBoard(val boardId: KanbanBoardId) : WalkthroughAction()

    /** Highlight a column (visual cue — sets overlay in recording state). */
    @Serializable
    data class HighlightColumn(val columnId: KanbanColumnId) : WalkthroughAction()

    /** Clear any highlight. */
    @Serializable
    data object ClearHighlight : WalkthroughAction()
}

// ── Predefined scripts ──────────────────────────────────────────────────────

/**
 * The canonical "teach-pendant robot walkthrough" — demonstrates:
 *  1. Empty board
 *  2. Backlog population
 *  3. Sequential drag-to-In-Progress (robot pick-and-place analogy)
 *  4. Review gate
 *  5. Done sweep
 *
 * Used as the default demo video if no custom script is provided.
 */
fun teachPendantRobotScript(): WalkthroughScript {
    val backlog  = KanbanColumnId("col-backlog")
    val wip      = KanbanColumnId("col-inprogress")   // matches ForgeBoardFSM.loadDefault()
    val review   = KanbanColumnId("col-review")
    val done     = KanbanColumnId("col-done")

    return WalkthroughScript(
        id = WalkthroughScriptId("teach-pendant-robot-v1"),
        title = "Forge Kanban — Teach-Pendant Robot Walkthrough",
        description = "Demonstrates board lifecycle: backlog → in-progress (robot) → review → done",
        targetFps = 30,
        captureWidth = 1280,
        captureHeight = 800,
        steps = listOf(
            WalkthroughStep(
                id = "s1",
                narration = "Loading the Forge Kanban board...",
                delayMs = 500, holdMs = 1500,
                action = WalkthroughAction.LoadDefaultBoard,
            ),
            WalkthroughStep(
                id = "s2",
                narration = "Narrate: Each column represents a stage in the pipeline.",
                delayMs = 400, holdMs = 1800,
                action = WalkthroughAction.Narrate("Pipeline stages: Backlog → In Progress → Review → Done"),
            ),
            WalkthroughStep(
                id = "s3",
                narration = "Adding task: 'Calibrate robot arm'",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.CreateCard(
                    title = "Calibrate robot arm",
                    columnId = backlog,
                    priority = CardPriority.HIGH,
                    assignee = "robot-01",
                ),
            ),
            WalkthroughStep(
                id = "s4",
                narration = "Adding task: 'Load part fixtures'",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.CreateCard(
                    title = "Load part fixtures",
                    columnId = backlog,
                    priority = CardPriority.MEDIUM,
                    assignee = "operator",
                ),
            ),
            WalkthroughStep(
                id = "s5",
                narration = "Adding task: 'Run weld sequence'",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.CreateCard(
                    title = "Run weld sequence",
                    columnId = backlog,
                    priority = CardPriority.CRITICAL,
                    assignee = "robot-01",
                ),
            ),
            WalkthroughStep(
                id = "s6",
                narration = "Robot picks up first task — drag to In Progress",
                delayMs = 800, holdMs = 1200,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Calibrate robot arm",
                    toColumnId = wip,
                ),
            ),
            WalkthroughStep(
                id = "s7",
                narration = "Operator loads fixtures — concurrent execution",
                delayMs = 600, holdMs = 1000,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Load part fixtures",
                    toColumnId = wip,
                ),
            ),
            WalkthroughStep(
                id = "s8",
                narration = "Calibration complete — moving to Review gate",
                delayMs = 800, holdMs = 1400,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Calibrate robot arm",
                    toColumnId = review,
                ),
            ),
            WalkthroughStep(
                id = "s9",
                narration = "Fixtures loaded — moving to Review gate",
                delayMs = 400, holdMs = 800,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Load part fixtures",
                    toColumnId = review,
                ),
            ),
            WalkthroughStep(
                id = "s10",
                narration = "Quality check passed — tasks done",
                delayMs = 1000, holdMs = 1200,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Calibrate robot arm",
                    toColumnId = done,
                ),
            ),
            WalkthroughStep(
                id = "s11",
                narration = "",
                delayMs = 200, holdMs = 600,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Load part fixtures",
                    toColumnId = done,
                ),
            ),
            WalkthroughStep(
                id = "s12",
                narration = "Robot executes weld sequence — final task in flight",
                delayMs = 800, holdMs = 1200,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Run weld sequence",
                    toColumnId = wip,
                ),
            ),
            WalkthroughStep(
                id = "s13",
                narration = "Weld sequence complete — pipeline closed",
                delayMs = 1000, holdMs = 2000,
                action = WalkthroughAction.MoveCardByTitle(
                    title = "Run weld sequence",
                    toColumnId = done,
                ),
            ),
            WalkthroughStep(
                id = "s14",
                narration = "Forge Kanban: teach-pendant robot workflow demonstrated.",
                delayMs = 400, holdMs = 2500,
                action = WalkthroughAction.Narrate("Pipeline complete. All tasks in Done."),
            ),
        ),
    )
}
