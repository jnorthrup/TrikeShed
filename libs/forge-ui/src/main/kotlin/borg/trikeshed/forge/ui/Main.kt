package borg.trikeshed.forge.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.MuxReactorBootstrapJvm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main entry point for Forge UI.
 *
 * - Bootstraps ForgeBoardFSM with default board.
 * - Wires MuxReactor taxonomy events into ForgeBoardFSM in background.
 * - Launches a 1280×800 Compose Desktop window (sized for clean screen recording).
 * - Registers the window with WalkthroughPlayer so the ScreenRecorder captures
 *   exactly this window's bounds.
 */
fun main() = application {
    ForgeBoardFSM.loadDefault()

    val bgScope = CoroutineScope(Dispatchers.IO)
    bgScope.launch {
        try {
            val reactor = MuxReactorBootstrapJvm.initialize()
            KanbanFSM.collectAndReduce(reactor.kanbanEvents)
        } catch (e: Exception) {
            println("[ForgeUI] Reactor init non-fatal: ${e.message}")
        }
    }

    val windowState = rememberWindowState(
        size = DpSize(1280.dp, 800.dp),
    )

    Window(
        onCloseRequest = ::exitApplication,
        title          = "Forge UI",
        state          = windowState,
    ) {
        // Register the AWT window reference with the recorder so it
        // can capture this exact window when a walkthrough recording starts.
        val awtWindow: java.awt.Window = this.window
        WalkthroughWindowRef.set(awtWindow)

        ForgeUiShell()
    }
}

/**
 * Holds the AWT window reference for the ScreenRecorder.
 * Set once when the Compose window is created; consumed by RecordingToolbar.
 */
object WalkthroughWindowRef {
    @Volatile private var window: java.awt.Window? = null

    fun set(w: java.awt.Window) { window = w }
    fun get(): java.awt.Window? = window
}
