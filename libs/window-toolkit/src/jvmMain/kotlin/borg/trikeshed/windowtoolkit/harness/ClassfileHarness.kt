package borg.trikeshed.windowtoolkit.harness

import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.dsl.WindowShell
import borg.trikeshed.usersignals.*
import kotlinx.coroutines.runBlocking

/**
 * Harness demonstrating signal-driven UI composition with user-signals.
 * No graphical dimensions - pure signal templates with rendering backends.
 */
fun buildSignalUIHarness(): WindowShell {
    return windowContext {
        // Create signal-driven UI components
        val enabled = toggle("app.enabled", true)
        val zoom = slider("app.zoom", 0.5, 3.0, 1.0, 0.1)
        val activity = level("app.activity", 2000)

        // Status panel template
        signalTemplate {
            label("Signal UI Harness")
            bind(toggleHole(), enabled)
            bind(lightHole(), enabled)
            bind(sliderHole(), zoom)
            bind(levelHole(), activity)
        }

        // Control panel - zoom
        signalTemplate {
            label("Zoom Control")
            bind(sliderHole(), zoom)
        }.also { /* auto-registered as component */ }

        // Activity meter
        signalTemplate {
            label("Activity Meter")
            bind(levelHole(), activity)
        }.also { /* auto-registered */ }
    }
}

fun main() = runBlocking {
    val shell = buildSignalUIHarness()
    shell.mount()

    // Demonstrate signal-driven updates via context
    val ctx = shell.signalContext
    ctx.getSource<Boolean>("app.enabled")?.emit(false)
    ctx.getSource<Double>("app.zoom")?.emit(2.5)
    ctx.getSource<Double>("app.activity")?.emit(0.85)

    println("Signal UI Harness mounted and updated")

    // Render via text backend (one-shot)
    val pipeline = RenderPipeline()
        .addBackend(TextBackend())
        .addBackend(AnsiBackend())
    
    // Add all tracked components
    // Note: In real usage, you'd add components explicitly or via a registry

    shell.unmount()
}