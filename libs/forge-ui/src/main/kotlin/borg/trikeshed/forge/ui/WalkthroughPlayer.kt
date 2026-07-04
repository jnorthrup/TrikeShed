package borg.trikeshed.forge.ui

import borg.trikeshed.kanban.ForgeBoardEvent
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.kanban.KanbanCardId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Window
import java.io.File

/**
 * WalkthroughPlayer
 *
 * Plays back a [WalkthroughScript] by:
 *  1. Driving [ForgeBoardFSM] events (card create / move / drag)
 *  2. Advancing the [ScreenRecorder]'s narration text each step
 *  3. Optionally recording via [ForgeRecorder] while playing
 *
 * The player can also run in headless mode (no window ref) for
 * server-side render pipelines.
 *
 * Replay fidelity contract:
 *  - delayMs pause fires BEFORE the action (records "before" state)
 *  - action fires (FSM mutates board, UI re-renders within one Compose frame)
 *  - holdMs pause fires AFTER (records "after" state)
 *
 * Thread safety: all FSM calls go through ForgeBoardFSM.emit() which is
 * already thread-safe (backed by a MutableStateFlow + mutex).
 */
object WalkthroughPlayer {

    enum class PlayState { IDLE, PLAYING, PAUSED, DONE }

    // ── Observable state ─────────────────────────────────────────────────────

    private val _playState   = MutableStateFlow(PlayState.IDLE)
    private val _currentStep = MutableStateFlow<WalkthroughStep?>(null)
    private val _progress    = MutableStateFlow(0 to 0)    // current to total
    private val _lastError   = MutableStateFlow<String?>(null)

    val playState:   StateFlow<PlayState>          = _playState
    val currentStep: StateFlow<WalkthroughStep?>   = _currentStep
    val progress:    StateFlow<Pair<Int, Int>>      = _progress
    val lastError:   StateFlow<String?>             = _lastError

    // ── Internals ─────────────────────────────────────────────────────────────

    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Play [script] from start.
     *
     * @param window  Optional AWT window — if provided, [ForgeRecorder] will
     *                record the session and export to [outputFile].
     * @param record  When true, starts [ForgeRecorder] around the playback.
     * @param outputFile  Destination MP4. Defaults to ~/Movies/forge-walkthrough-<id>.mp4
     */
    fun play(
        script: WalkthroughScript,
        window: Window? = null,
        record: Boolean = false,
        outputFile: File = defaultOutputFile(script),
    ) {
        check(_playState.value == PlayState.IDLE || _playState.value == PlayState.DONE) {
            "Already playing — call stop() first"
        }
        _lastError.value = null
        _progress.value = 0 to script.steps.size
        _playState.value = PlayState.PLAYING

        playJob = scope.launch {
            try {
                if (record) {
                    ForgeRecorder.recorder.startRecording(window)
                }

                for ((index, step) in script.steps.withIndex()) {
                    if (!isActive) break
                    _currentStep.value = step
                    _progress.value = index to script.steps.size

                    // Narrate before delay so the subtitle is visible during
                    // the "before" hold
                    if (record && step.narration.isNotEmpty()) {
                        ForgeRecorder.recorder.setNarration(step.narration)
                    }

                    // Pre-action pause — shows current board state
                    if (step.delayMs > 0) delay(step.delayMs)
                    if (!isActive) break

                    // Execute action
                    executeAction(step.action)

                    // Post-action pause — shows result state
                    if (step.holdMs > 0) delay(step.holdMs)
                }

                _progress.value = script.steps.size to script.steps.size
                _currentStep.value = null

                if (record) {
                    try {
                        ForgeRecorder.recorder.setNarration("")
                        // Brief hold on final frame
                        delay(800)
                        val out = ForgeRecorder.recorder.stopAndExport(outputFile)
                        ForgeRecorder.lastExportPath = out.absolutePath
                        println("[WalkthroughPlayer] Video exported: ${out.absolutePath}")
                    } catch (e: Exception) {
                        _lastError.value = "Export failed: ${e.message}"
                        println("[WalkthroughPlayer] Export error: ${e.message}")
                        ForgeRecorder.recorder.stopDiscard()
                    }
                }

                _playState.value = PlayState.DONE
            } catch (e: Exception) {
                _lastError.value = e.message
                _playState.value = PlayState.IDLE
                if (record && ForgeRecorder.isRecording) ForgeRecorder.recorder.stopDiscard()
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        if (ForgeRecorder.isRecording) ForgeRecorder.recorder.stopDiscard()
        _playState.value = PlayState.IDLE
        _currentStep.value = null
    }

    fun reset() {
        stop()
        _playState.value = PlayState.IDLE
        _lastError.value = null
        _progress.value = 0 to 0
    }

    // ── Action executor ────────────────────────────────────────────────────────

    private suspend fun executeAction(action: WalkthroughAction) {
        val now = System.currentTimeMillis()
        val board = ForgeBoardFSM.current().activeBoard

        when (action) {
            is WalkthroughAction.Narrate -> {
                // Pure narration — no FSM mutation. The narration text is
                // already set on the recorder via step.narration above.
                // Nothing to do here except a short visual pause.
                delay(200)
            }

            is WalkthroughAction.LoadDefaultBoard -> {
                ForgeBoardFSM.loadDefault()
            }

            is WalkthroughAction.CreateCard -> {
                if (board == null) return
                ForgeBoardFSM.emit(
                    ForgeBoardEvent.CardCreated(
                        boardId  = board.id,
                        cardId   = KanbanCardId.generate(),
                        columnId = action.columnId,
                        title    = action.title,
                        timestampMs = now,
                    )
                )
            }

            is WalkthroughAction.MoveCardByTitle -> {
                if (board == null) return
                val card = board.cards.firstOrNull { it.title == action.title } ?: run {
                    println("[WalkthroughPlayer] card not found: '${action.title}'")
                    return
                }
                ForgeBoardFSM.emit(
                    ForgeBoardEvent.CardMoved(
                        boardId    = board.id,
                        cardId     = card.id,
                        toColumnId = action.toColumnId,
                        timestampMs = now,
                    )
                )
            }

            is WalkthroughAction.MoveCard -> {
                if (board == null) return
                ForgeBoardFSM.emit(
                    ForgeBoardEvent.CardMoved(
                        boardId    = board.id,
                        cardId     = action.cardId,
                        toColumnId = action.toColumnId,
                        timestampMs = now,
                    )
                )
            }

            is WalkthroughAction.DragCard -> {
                if (board == null) return
                // Simulate the drag lifecycle: Started → hold animate → Dropped
                ForgeBoardFSM.emit(
                    ForgeBoardEvent.DragStarted(
                        boardId    = board.id,
                        cardId     = action.cardId,
                        fromColumnId = action.fromColumnId,
                        timestampMs = now,
                    )
                )
                // Animate drag over: step through intermediate columns if any
                ForgeBoardFSM.emit(ForgeBoardEvent.DragOver(action.toColumnId, now))
                delay(action.animationMs)
                ForgeBoardFSM.emit(ForgeBoardEvent.DragDropped(System.currentTimeMillis()))
            }

            is WalkthroughAction.DeleteCardByTitle -> {
                if (board == null) return
                val card = board.cards.firstOrNull { it.title == action.title } ?: return
                ForgeBoardFSM.emit(
                    ForgeBoardEvent.CardDeleted(
                        boardId = board.id,
                        cardId  = card.id,
                        timestampMs = now,
                    )
                )
            }

            is WalkthroughAction.Pause -> {
                delay(action.durationMs)
            }

            is WalkthroughAction.SelectBoard -> {
                ForgeBoardFSM.emit(ForgeBoardEvent.BoardSelected(action.boardId, now))
            }

            is WalkthroughAction.HighlightColumn,
            is WalkthroughAction.ClearHighlight -> {
                // Visual-only hints — consumed by the UI via PlayerOverlayState
                // Nothing to emit on the FSM
            }
        }
    }

    private fun defaultOutputFile(script: WalkthroughScript): File {
        val moviesDir = File(System.getProperty("user.home"), "Movies")
        moviesDir.mkdirs()
        return File(moviesDir, "forge-${script.id.value}.mp4")
    }
}

// ── Playback overlay state (drives UI highlights/subtitles) ──────────────────

/**
 * Compose-observable state for the player overlay.
 * Collected by [KanbanBoardScreen] to show subtitles + highlighted columns.
 */
object PlayerOverlayState {
    val narration  = MutableStateFlow("")
    val highlighted = MutableStateFlow<borg.trikeshed.kanban.KanbanColumnId?>(null)

    fun update(step: WalkthroughStep?) {
        narration.value  = step?.narration ?: ""
        highlighted.value = when (val a = step?.action) {
            is WalkthroughAction.HighlightColumn -> a.columnId
            is WalkthroughAction.ClearHighlight  -> null
            else -> null
        }
    }
}
