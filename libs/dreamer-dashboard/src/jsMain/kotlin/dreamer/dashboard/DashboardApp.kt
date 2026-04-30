package dreamer.dashboard

import kotlinx.coroutines.*

/**
 * Dreamer Dashboard — split-pane terminal UI for stochastic bag/span training.
 *
 * Left pane:  Dreamer paper replay measurement
 * Right pane: stochastic bag/span training progress
 *
 * Built with blessed (npm) via Kotlin/JS externals.
 * Same source compiles to JS and Wasm nodejs targets.
 *
 * Usage:
 *   cd libs/dreamer-dashboard && npm install && ./gradlew jsNodeDevelopmentRun
 *
 * Quit: q or Ctrl-C
 */
fun main() {
    // ── Create blessed screen ────────────────────────────────────────
    val screenOpts = js("({})")
    screenOpts.smartCSR = false  // disable cursor save/restore — more compatible
    screenOpts.title = "Dreamer Dashboard"
    screenOpts.fullUnicode = false  // ASCII-only for max compatibility
    screenOpts.mouse = false  // disable mouse events to prevent noise
    screenOpts.autoPadding = true
    val screen = screen(screenOpts)

    val state = DashboardState()

    // ── Layout ───────────────────────────────────────────────────────
    val split = SplitPane(screen)
    val tradingPane = TradingPane(split.left)
    val trainingPane = TrainingPane(split.right)

    // ── Footer ───────────────────────────────────────────────────────
    val footerOpts = js("({})")
    footerOpts.bottom = 0
    footerOpts.left = 0
    footerOpts.width = "100%"
    footerOpts.height = 1
    footerOpts.content = " {cyan-fg}q{/cyan-fg} quit  |  {green-fg}r{/green-fg} refresh  |  stochastic bag/span training"
    footerOpts.tags = true
    footerOpts.style = js("({ fg: 'white', bg: 'black' })")
    val footer = text(footerOpts)
    screen.append(footer)

    // ── Render loop (250ms ticks) ────────────────────────────────────
    val scope = CoroutineScope(Dispatchers.Default)
    var running = true
    state.start(scope)

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
        state.stop()
        scope.cancel()
        screen.destroy()
        println("Dreamer Dashboard closed.")
    }

    screen.key(arrayOf("r")) { _, _ ->
        screen.render()
    }

    // ── Initial render ───────────────────────────────────────────────
    tradingPane.render(state)
    trainingPane.render(state)
    screen.render()
}
