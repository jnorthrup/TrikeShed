package dreamer.terminal

import kotlinx.coroutines.*

/**
 * Dreamer terminal app for stochastic bag/span training.
 *
 * Left pane: Dreamer paper replay measurement
 * Right pane: stochastic bag/span training progress
 *
 * Built with blessed (npm) via Kotlin/JS externals.
 * Same source compiles to JS and Wasm nodejs targets.
 *
 * Usage:
 *   ./dreamer
 *   cd libs/dreamer-dashboard && npm start
 *
 * Quit: q or Ctrl-C
 */
fun main() {
    val screenOpts = js("({})")
    screenOpts.smartCSR = false
    screenOpts.title = "Dreamer"
    screenOpts.name = "dreamer"
    screenOpts.fullUnicode = false
    screenOpts.mouse = false
    screenOpts.autoPadding = true
    val screen = screen(screenOpts)

    val state = DreamerTerminalState()

    val split = SplitPane(screen)
    val tradingPane = TradingPane(split.left)
    val trainingPane = TrainingPane(split.right)

    val footerOpts = js("({})")
    footerOpts.bottom = 0
    footerOpts.left = 0
    footerOpts.width = "100%"
    footerOpts.height = 1
    footerOpts.content = " {cyan-fg}q{/cyan-fg} quit  |  {green-fg}r{/green-fg} refresh  |  Dreamer stochastic bag/span"
    footerOpts.tags = true
    footerOpts.style = js("({ fg: 'white', bg: 'black' })")
    val footer = text(footerOpts)
    screen.append(footer)

    val scope = CoroutineScope(Dispatchers.Default)
    var running = true
    var closed = false

    fun shutdown(message: String? = null) {
        if (closed) return
        closed = true
        running = false
        state.stop()
        scope.cancel()
        screen.destroy()
        if (message != null) println(message)
    }

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

    screen.key(arrayOf("q", "C-c")) { _, _ ->
        shutdown("Dreamer closed.")
    }

    screen.key(arrayOf("r")) { _, _ ->
        screen.render()
    }

    val process = nodeProcess()
    if (process != null) {
        process.on("SIGINT", { shutdown("Dreamer closed.") })
        process.on("SIGTERM", { shutdown("Dreamer closed.") })
        process.on("uncaughtException", { error: dynamic ->
            shutdown(null)
            println("Dreamer failed: ${error?.message ?: error}")
        })
    }

    tradingPane.render(state)
    trainingPane.render(state)
    screen.render()
}

private fun nodeProcess(): dynamic = js("typeof process !== 'undefined' ? process : null")
