package borg.trikeshed.windowtoolkit.dsl

import borg.trikeshed.windowtoolkit.context.WindowContextElement
import borg.trikeshed.usersignals.*
import borg.trikeshed.usersignals.SignalAlgebra
import kotlinx.coroutines.Dispatchers

/**
 * Root builder for signal-driven UI shell.
 * No graphical dimensions - pure signal composition.
 */
class WindowShell(private val context: WindowContextElement) {

    /** Component registry for declarative pipeline building */
    val components = ComponentRegistry()

    /** Signal factory for creating user signals within this shell */
    val signals: SignalFactory = SignalFactory(context.signalContextInstance)

    /** Access underlying signal context for advanced usage */
    val signalContext: SignalContextElement = context.signalContextInstance

    // ====================================================================
    // Signal Creation - unified factory methods
    // ====================================================================

    fun toggle(id: String, initial: Boolean = false): Toggle = signals.toggle(id, initial)
    fun light(id: String, initial: Boolean = false): IdiotLight = signals.idiotLight(id, initial)
    fun button(id: String): MomentaryButton = signals.momentaryButton(id)
    fun <T> radio(id: String, options: List<T>, initial: T? = null): RadioToggle<T> = signals.radioToggle(id, options, initial)
    fun slider(id: String, min: Double, max: Double, initial: Double? = null, step: Double? = null): Slider = signals.slider(id, min, max, initial, step)
    fun knob(id: String, min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null): Knob = signals.knob(id, min, max, initial, detents)
    fun <T> dial(id: String, positions: List<T>, initial: T? = null): Dial<T> = signals.dial(id, positions, initial)
    fun level(id: String, peakHoldMillis: Long = 1000): LevelMeter = signals.levelMeter(id, peakHoldMillis)

    // ====================================================================
    // Template & Composition
    // ====================================================================

    fun template(block: SignalTemplateBuilder.() -> Unit): SignalTemplate =
        signalTemplate(block)

    fun beside(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.drop(1).fold(components[0]) { acc, c -> SignalAlgebra.beside(acc, c) }

    fun above(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.drop(1).fold(components[0]) { acc, c -> SignalAlgebra.above(acc, c) }

    fun overlay(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.drop(1).fold(components[0]) { acc, c -> SignalAlgebra.overlay(acc, c) }

    fun whenVisible(condition: Signal<Boolean>, component: SignalComponent<*>): SignalComponent<*> =
        SignalAlgebra.whenVisible(condition, component)

    // ====================================================================
    // Pipeline & Rendering
    // ====================================================================

    suspend fun mount() = context.open()

    suspend fun unmount() = context.close()

    suspend fun renderOnce(): List<RenderResult> = components.build().renderOnce()
    fun pipeline(): RenderPipeline = components.build()
    fun live(): ConsoleRenderer = ConsoleRenderer(pipeline())
}

/**
 * Main entry point - creates a window context with signal-driven UI.
 */
fun windowContext(block: WindowShell.() -> Unit): WindowShell {
    val context = WindowContextElement()
    val shell = WindowShell(context)
    shell.block()
    return shell
}

/**
 * Shorthand for creating a signal-driven UI panel.
 */
fun panel(block: SignalTemplateBuilder.() -> Unit): SignalTemplate = signalTemplate(block)

/**
 * Quick render a panel to TUI.
 */
suspend fun quickTui(block: SignalTemplateBuilder.() -> Unit): Pair<RenderResult, RenderResult> =
    quickRender(signalTemplate(block))