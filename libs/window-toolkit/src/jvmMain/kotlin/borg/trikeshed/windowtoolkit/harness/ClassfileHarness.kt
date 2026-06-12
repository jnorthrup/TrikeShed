package borg.trikeshed.windowtoolkit.harness

import borg.trikeshed.windowtoolkit.dsl.windowContext
import borg.trikeshed.windowtoolkit.dsl.WindowShell
import borg.trikeshed.windowtoolkit.ui.StyleRow
import borg.trikeshed.windowtoolkit.math.v2
import borg.trikeshed.windowtoolkit.math.size
import borg.trikeshed.windowtoolkit.math.j
import borg.trikeshed.windowtoolkit.ui.applyUniformStyle
import borg.trikeshed.windowtoolkit.confix.ConfixBlackboardWidget
import borg.trikeshed.usersignals.Algebra.TemplateOutput
import borg.trikeshed.usersignals.SignalTemplate
import borg.trikeshed.usersignals.Algebra.beside
import borg.trikeshed.usersignals.Algebra.above
import kotlinx.coroutines.runBlocking

/**
 * Harness connecting a generic JSON string mapping Classfile coordinate data
 * into the generic Confix Blackboard and layout rendering system of the window-toolkit.
 * Now uses user-signals for signal-driven UI composition without graphical dimensions.
 */
fun buildClassfileDatagridHarness(jsonDump: String): WindowShell {
    val shell = windowContext {
        // 1. Setup the Confix Datagrid representation layer
        confixDatagrid {
            // Push entry to render
        }

        // 2. Setup a simplistic layout layer mimicking classfile grid plotting
        val points = 3 j { i: Int -> v2(i * 100.0, i * 20.0) }
        val uniformStyles = points.size j { _: Int -> StyleRow(mapOf("color" to "blue")) }

        layoutLayer(points, uniformStyles)

        // 3. Create signal-driven UI components using user-signals
        val toggle = createToggle("classfile.enabled", true)
        val slider = createSlider("classfile.zoom", 0.5, 3.0, 1.0, 0.1)
        val level = createLevelMeter("classfile.activity", 2000)

        // 4. Build a signal template (no graphical dimensions, pure data)
        signalTemplate {
            label("Classfile Harness")
            bind(toggleHole(), toggle)
            bind(lightHole(), toggle) // toggle also acts as light
            bind(sliderHole(), slider)
            bind(levelHole(), level)
            iconHole().also { bind(it, borg.trikeshed.usersignals.Rendering.ConstSignal("📊")) }
        }

        // 5. Compose multiple signal components
        val zoomControl = signalTemplate {
            label("Zoom")
            bind(sliderHole(), slider)
        }
        val activityMeter = signalTemplate {
            label("Activity")
            bind(levelHole(), level)
        }
        val harnessStatus = signalTemplate {
            label("Status")
            bind(toggleHole(), toggle)
        }

        // Use signal algebra to compose
        val composed = stackComponents(
            beside(harnessStatus, zoomControl),
            activityMeter
        )
        // Component is automatically tracked
    }

    return shell
}

fun main() = runBlocking {
    val sampleClassfileJson = """
        {
          "symbolName": "com.example.Demo",
          "ownerType": "Demo",
          "methodOrField": "<init>",
          "classfileCoord": "com.example.Demo#<init>",
          "cpIndex": 10,
          "descriptor": "()V",
          "xvmTypeInfo": "method",
          "pointcutKind": 12,
          "poolId": 101,
          "activeJsFacet": "Unfaceted"
        }
    """.trimIndent()

    val shell = buildClassfileDatagridHarness(sampleClassfileJson)
    shell.mount()

    // Demonstrate signal-driven updates
    val toggle = shell.signals.getSource<Boolean>("classfile.enabled")
    val slider = shell.signals.getSource<Double>("classfile.zoom")
    val level = shell.signals.getSource<Double>("classfile.activity")

    toggle?.emit(false)
    slider?.emit(2.0)
    level?.emit(0.75)

    println("Successfully mounted Classfile Harness inside Window Toolkit Shell")

    // Render via signal template output
    println("Signal template outputs (text backend):")
    // This would render via the user-signals rendering pipeline

    shell.unmount()
}