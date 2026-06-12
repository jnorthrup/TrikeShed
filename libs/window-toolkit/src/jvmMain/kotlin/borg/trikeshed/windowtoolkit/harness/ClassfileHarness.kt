package borg.trikeshed.windowtoolkit.harness

import borg.trikeshed.windowtoolkit.dsl.WindowShell
import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.dsl.panel
import borg.trikeshed.windowtoolkit.internal.*
import kotlinx.coroutines.runBlocking

/**
 * Signal-driven UI harness - demonstrates the coherent DSL API.
 */
fun buildSignalUIHarness(): WindowShell {
    return windowContext {
        // Create signals
        val enabled = toggle("app.enabled", true)
        val zoom = slider("app.zoom", 0.5, 3.0, 1.0, 0.1)
        val activity = level("app.activity", 2000)

        // Compose templates fluently
        components.add(
            panel {
                label("Signal UI Harness")
                toggle(enabled)
                slider(zoom)
                level(activity)
            }
        )

        // Individual control panels
        components.add(
            panel {
                label("Zoom Control")
                slider(zoom)
            }
        )

        components.add(
            panel {
                label("Activity Meter")
                level(activity)
            }
        )
    }
}

fun main() = runBlocking {
    val shell = buildSignalUIHarness()
    shell.mount()

    // Update signals via context
    val ctx = shell.signalContext
    ctx.getSource<Boolean>("app.enabled")?.emit(false)
    ctx.getSource<Double>("app.zoom")?.emit(2.5)
    ctx.getSource<Double>("app.activity")?.emit(0.85)

    println("Signal UI Harness mounted and updated")

    // One-shot render to TUI
    val results = shell.renderOnce()
    if (results.size >= 2) {
        val textResult = results[0]
        val ansiResult = results[1]
        println("Text output:")
        println(textResult.content)
        println("\nANSI output:")
        println(ansiResult.content)
    }

    shell.unmount()
}