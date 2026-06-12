package borg.trikeshed.windowtoolkit.dsl

import borg.trikeshed.windowtoolkit.context.WindowContextElement
import borg.trikeshed.windowtoolkit.confix.ConfixBlackboardWidget
import borg.trikeshed.windowtoolkit.ui.SkinNode
import borg.trikeshed.windowtoolkit.ui.StyleCursor
import borg.trikeshed.windowtoolkit.math.Series
import borg.trikeshed.windowtoolkit.math.Vec2
import borg.trikeshed.windowtoolkit.ui.skin
import borg.trikeshed.usersignals.Signal
import borg.trikeshed.usersignals.SignalComponent
import borg.trikeshed.usersignals.SignalFactory
import borg.trikeshed.usersignals.SignalTemplate
import borg.trikeshed.usersignals.SignalSource
import borg.trikeshed.usersignals.Algebra.VisualTemplate
import borg.trikeshed.usersignals.Algebra.TemplateHole
import borg.trikeshed.usersignals.Algebra.TemplateOutput
import borg.trikeshed.usersignals.Algebra.template
import borg.trikeshed.usersignals.Algebra.beside
import borg.trikeshed.usersignals.Algebra.above
import borg.trikeshed.usersignals.SignalContextElement

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

    /** DSL for building signal templates */
    class SignalTemplateBuilder {
        private val templateBuilder = borg.trikeshed.usersignals.Algebra.TemplateBuilder()
        private val bindingBuilders = mutableListOf<() -> borg.trikeshed.usersignals.Algebra.TemplateBinding<*>>()

        fun <T> hole(key: String): TemplateHole<T> = templateBuilder.hole(key)
        fun toggleHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.toggle
        fun lightHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.light
        fun sliderHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.slider
        fun knobHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.knob
        fun levelHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.level
        fun labelHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.label
        fun iconHole() = borg.trikeshed.usersignals.Algebra.StandardHoles.icon

        fun <T> bind(hole: TemplateHole<T>, signal: Signal<T>): SignalTemplateBuilder {
            bindingBuilders.add { borg.trikeshed.usersignals.Algebra.TemplateBinding(hole, signal) }
            return this
        }

        fun label(text: String): SignalTemplateBuilder {
            bindingBuilders.add { borg.trikeshed.usersignals.Algebra.TemplateBinding(borg.trikeshed.usersignals.Algebra.StandardHoles.label, borg.trikeshed.usersignals.Rendering.ConstSignal(text)) }
            return this
        }

        fun icon(name: String): SignalTemplateBuilder {
            bindingBuilders.add { borg.trikeshed.usersignals.Algebra.TemplateBinding(borg.trikeshed.usersignals.Algebra.StandardHoles.icon, borg.trikeshed.usersignals.Rendering.ConstSignal(name)) }
            return this
        }

        fun build(): SignalTemplate {
            val tpl = templateBuilder.build { bindings ->
                TemplateOutput(templateId = templateBuilder.id, boundValues = bindings, metadata = templateBuilder.metadata)
            }
            val bindings = bindingBuilders.map { it() }
            return borg.trikeshed.usersignals.SignalTemplate(tpl, bindings)
        }
    }

    /**
     * Create a composed signal component using user-signals algebra.
     */
    fun composeComponents(vararg components: SignalComponent<*>): SignalComponent<*> {
        if (components.size == 1) return components[0]
        var composed = components[0]
        for (i in 1 until components.size) {
            composed = borg.trikeshed.usersignals.Algebra.beside(composed, components[i])
        }
        return composed
    }

    /** Stack components vertically */
    fun stackComponents(vararg components: SignalComponent<*>): SignalComponent<*> {
        if (components.size == 1) return components[0]
        var composed = components[0]
        for (i in 1 until components.size) {
            composed = borg.trikeshed.usersignals.Algebra.above(composed, components[i])
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