package borg.trikeshed.windowtoolkit.dsl

import borg.trikeshed.windowtoolkit.context.WindowContextElement
import borg.trikeshed.windowtoolkit.confix.ConfixBlackboardWidget
import borg.trikeshed.windowtoolkit.ui.SkinNode
import borg.trikeshed.windowtoolkit.ui.StyleCursor
import borg.trikeshed.windowtoolkit.math.Series
import borg.trikeshed.windowtoolkit.math.Vec2
import borg.trikeshed.windowtoolkit.ui.skin
import borg.trikeshed.usersignals.*
import borg.trikeshed.usersignals.SignalAlgebra

/**
 * Root builder for the Windowing toolkit DSL shell.
 * Now integrated with user-signals for signal-driven UI composition.
 */
class WindowShell(val context: WindowContextElement) {

    private val widgets = mutableListOf<ConfixBlackboardWidget>()
    private val layers = mutableListOf<SkinNode>()

    /** Signal factory for creating user signals within this shell */
    val signals: SignalFactory = SignalFactory(context.signalContextInstance)

    /** Signal components for UI composition */
    private val components = mutableListOf<SignalComponent<*>>()

    /**
     * Declares a Confix Blackboard widget layer.
     */
    fun confixDatagrid(configure: ConfixBlackboardWidget.() -> Unit) {
        val widget = ConfixBlackboardWidget()
        widget.configure()
        widgets.add(widget)
    }

    /**
     * Declares a geometric layout layer mapped with visual skins.
     */
    fun layoutLayer(geometry: Series<Vec2>, styles: StyleCursor) {
        layers.add(skin(geometry, styles))
    }

    /**
     * Create a signal template for UI composition without graphical dimensions.
     * Uses user-signals algebra for pure signal-driven templates.
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
     * Create a composed signal component using user-signals algebra.
     */
    fun composeComponents(vararg components: SignalComponent<*>): SignalComponent<*> {
        if (components.size == 1) return components[0]
        var composed = components[0]
        for (i in 1 until components.size) {
            composed = SignalAlgebra.beside(composed, components[i])
        }
        return composed
    }

    /** Stack components vertically */
    fun stackComponents(vararg components: SignalComponent<*>): SignalComponent<*> {
        if (components.size == 1) return components[0]
        var composed = components[0]
        for (i in 1 until components.size) {
            composed = SignalAlgebra.above(composed, components[i])
        }
        return composed
    }

    /** Create a toggle signal bound to this shell */
    fun createToggle(signalId: String, initial: Boolean = false) = signals.toggle(signalId, initial)

    /** Create an idiot light signal bound to this shell */
    fun createLight(signalId: String, initial: Boolean = false) = signals.idiotLight(signalId, initial)

    /** Create a slider signal bound to this shell */
    fun createSlider(signalId: String, min: Double, max: Double, initial: Double? = null, step: Double? = null) =
        signals.slider(signalId, min, max, initial, step)

    /** Create a knob signal bound to this shell */
    fun createKnob(signalId: String, min: Double = 0.0, max: Double = 1.0, initial: Double = 0.0, detents: Int? = null) =
        signals.knob(signalId, min, max, initial, detents)

    /** Create a level meter signal bound to this shell */
    fun createLevelMeter(signalId: String, peakHoldMillis: Long = 1000) =
        signals.levelMeter(signalId, peakHoldMillis)

    suspend fun mount() {
        context.open()
    }

    suspend fun unmount() {
        context.close()
        widgets.clear()
        layers.clear()
        components.clear()
    }
}

/**
 * Entry point DSL function for the Window Toolkit.
 */
fun windowContext(block: WindowShell.() -> Unit): WindowShell {
    val context = WindowContextElement()
    val shell = WindowShell(context)
    shell.block()
    return shell
}

/**
 * Entry point with signal context factory.
 */
fun windowContextWithSignals(block: WindowShell.() -> Unit): WindowShell = windowContext(block)