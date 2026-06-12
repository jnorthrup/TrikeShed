package borg.trikeshed.windowtoolkit.dsl

import borg.trikeshed.windowtoolkit.context.WindowContextElement
import borg.trikeshed.usersignals.*
import borg.trikeshed.usersignals.SignalAlgebra

/**
 * Root builder for the Windowing toolkit DSL shell.
 * Signal-driven UI composition without graphical dimensions.
 */
class WindowShell(private val context: WindowContextElement) {

    /** Signal components for UI composition */
    private val components = mutableListOf<SignalComponent<*>>()

    /** Signal factory for creating user signals within this shell */
    val signals: SignalFactory = SignalFactory(context.signalContextInstance)

    /** Access underlying signal context for advanced usage */
    val signalContext: SignalContextElement = context.signalContextInstance

    /**
     * Create a signal template for UI composition.
     * Uses user-signals algebra for pure signal-driven templates.
     * @return the created SignalTemplate (also auto-tracked as a component)
     */
    fun signalTemplate(block: SignalTemplateBuilder.() -> Unit): SignalTemplate {
        val builder = SignalTemplateBuilder()
        builder.block()
        val st = builder.build()
        components.add(st)
        return st
    }

    /** DSL for building signal templates - uses user-signals SignalTemplateBuilder */
    typealias SignalTemplateBuilder = borg.trikeshed.usersignals.SignalTemplateBuilder

    /**
     * Compose components horizontally (side by side).
     */
    fun beside(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.reduce { acc, c -> SignalAlgebra.beside(acc, c) }

    /**
     * Compose components vertically (stacked).
     */
    fun above(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.reduce { acc, c -> SignalAlgebra.above(acc, c) }

    /**
     * Compose components in z-stack (overlay).
     */
    fun overlay(vararg components: SignalComponent<*>): SignalComponent<*> =
        if (components.size == 1) components[0]
        else components.reduce { acc, c -> SignalAlgebra.overlay(acc, c) }

    /**
     * Conditional visibility based on a boolean signal.
     */
    fun whenVisible(condition: Signal<Boolean>, component: SignalComponent<*>): SignalComponent<*> =
        SignalAlgebra.whenVisible(condition, component)

    // ====================================================================
    // Signal Creation - unified factory methods
    // ====================================================================

    /** Create a toggle signal (0D on/off with intent) */
    fun toggle(id: String, initial: Boolean = false): Toggle =
        signals.toggle(id, initial)

    /** Create an idiot light (0D passive status indicator) */
    fun light(id: String, initial: Boolean = false): IdiotLight =
        signals.idiotLight(id, initial)

    /** Create a momentary button (0D press/release) */
    fun button(id: String): MomentaryButton =
        signals.momentaryButton(id)

    /** Create a radio toggle group (0D multi-state) */
    fun <T> radio(id: String, options: List<T>, initial: T? = null): RadioToggle<T> =
        signals.radioToggle(id, options, initial)

    /** Create a slider (1D continuous range) */
    fun slider(id: String, min: Double, max: Double, initial: Double? = null, step: Double? = null): Slider =
        signals.slider(id, min, max, initial, step)

    /** Create a knob (1D rotary with optional detents) */
    fun knob(id: String, min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null): Knob =
        signals.knob(id, min, max, initial, detents)

    /** Create a dial (1D discrete positions) */
    fun <T> dial(id: String, positions: List<T>, initial: T? = null): Dial<T> =
        signals.dial(id, positions, initial)

    /** Create a level meter (1D read-only amplitude) */
    fun level(id: String, peakHoldMillis: Long = 1000): LevelMeter =
        signals.levelMeter(id, peakHoldMillis)

    // ====================================================================
    // Lifecycle
    // ====================================================================

    suspend fun mount() = context.open()

    suspend fun unmount() {
        context.close()
        components.clear()
    }
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