package dreamer.dashboard

import kotlinx.coroutines.*

/**
 * Dreamer Dashboard — split-pane terminal UI for dual SupervisorJob monitoring.
 *
 * Left pane:  Robinhood live trading (balance, holdings, trade log)
 * Right pane: Binance archive replay (training progress, genome fitness)
 *
 * Built with blessed (npm) via Kotlin/JS externals.
 * Same source compiles to JS and Wasm nodejs targets.
 *
 * Usage:
 *   cd libs/dreamer-dashboard && npm install && ./gradlew jsNodeRun
 *
 * Quit: q or Ctrl-C
 */
fun main() {
    // ── Create blessed screen ────────────────────────────────────────
    val screen = Screen.screen(js("({ smartCSR: true, title: 'Dreamer Dashboard', fullUnicode: true })"))

    val state = DashboardState()

    // ── Layout ───────────────────────────────────────────────────────
    val split = SplitPane(screen)
    val tradingPane = TradingPane(split.left)
    val trainingPane = TrainingPane(split.right)

    // ── Footer ───────────────────────────────────────────────────────
    val footer = BlessedText.text(js("({ bottom: 0, left: 0, width: '100%', height: 1," +
        " content: ' {cyan-fg}q{/cyan-fg} quit  |  {green-fg}r{/green-fg} refresh  |  dreamer-dashboard v0.1'," +
        " tags: true, style: { fg: 'white', bg: 'black' } })"))
    screen.append(footer)

    // ── Render loop (250ms ticks) ────────────────────────────────────
    val scope = CoroutineScope(Dispatchers.Default)
    var running = true

    scope.launch {
        while (running) {
            state.tick()
            tradingPane.render(state)
            trainingPane.render(state)
            screen.render()
            delay(250)
        }
    }

    // ── Key bindings ─────────────────────────────────────────────────
    screen.key(arrayOf("q", "C-c")) { _, _ ->
        running = false
        scope.cancel()
        screen.destroy()
        println("Dreamer Dashboard closed.")
    }

    screen.key(arrayOf("r")) { _, _ ->
        // force full redraw
        screen.render()
    }

    // ── Initial render ───────────────────────────────────────────────
    tradingPane.render(state)
    trainingPane.render(state)
    screen.render()
}
