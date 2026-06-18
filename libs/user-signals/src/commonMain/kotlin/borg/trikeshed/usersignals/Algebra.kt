package borg.trikeshed.usersignals

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

inline  class TemplateHole<T>(val key: String) {
    operator fun invoke(signal: Signal<T>): TemplateBinding<T> = TemplateBinding(this, signal)
}

data class TemplateBinding<T>(val hole: TemplateHole<T>, val signal: Signal<T>)

interface VisualTemplate {
    val id: String
    val holes: List<TemplateHole<*>>
    fun render(bindings: Map<String, Any>): TemplateOutput
}

data class TemplateOutput(
    val templateId: String,
    val boundValues: Map<String, Any>,
    val metadata: Map<String, Any> = emptyMap()
)

interface SignalComponent<out T> {
    val template: VisualTemplate
    val bindings: List<TemplateBinding<*>>
    val output: Signal<TemplateOutput>
}

object SignalAlgebra {
    fun <A, B> beside(left: SignalComponent<A>, right: SignalComponent<B>): SignalComponent<Pair<A, B>> =
        CompositeComponent(left, right, CompositionMode.Beside)
    fun <A, B> above(top: SignalComponent<A>, bottom: SignalComponent<B>): SignalComponent<Pair<A, B>> =
        CompositeComponent(top, bottom, CompositionMode.Above)
    fun <A, B> overlay(bottom: SignalComponent<A>, top: SignalComponent<B>): SignalComponent<Pair<A, B>> =
        CompositeComponent(bottom, top, CompositionMode.Overlay)
    fun <A> whenVisible(condition: Signal<Boolean>, component: SignalComponent<A>): SignalComponent<A?> =
        ConditionalComponent(condition, component)
}

enum class CompositionMode { Beside, Above, Overlay }

private class CompositeComponent<A, B>(
    private val left: SignalComponent<A>,
    private val right: SignalComponent<B>,
    private val mode: CompositionMode
) : SignalComponent<Pair<A, B>> {
    override val template: VisualTemplate = CompositeTemplate(
        listOf("left" to left.template, "right" to right.template),
        mode
    )
    override val bindings: List<TemplateBinding<*>> get() = left.bindings + right.bindings
    override val output: Signal<TemplateOutput> = object : Signal<TemplateOutput> {
        private val leftFlow = left.output.changes
        private val rightFlow = right.output.changes
        private val combinedFlow = combine(leftFlow, rightFlow) { l, r ->
            TemplateOutput(templateId = template.id, boundValues = mapOf("left" to l.boundValues, "right" to r.boundValues), metadata = mapOf("mode" to mode.name))
        }
        override val value: TemplateOutput get() = TemplateOutput(templateId = template.id, boundValues = mapOf("left" to left.output.value.boundValues, "right" to right.output.value.boundValues), metadata = mapOf("mode" to mode.name))
        override val changes: Flow<TemplateOutput> = combinedFlow
    }
}

private data class CompositeTemplate(
    private val children: List<Pair<String, VisualTemplate>>,
    private val mode: CompositionMode
) : VisualTemplate {
    override val id: String = "composite_${mode.name.lowercase()}_${children.hashCode()}"
    override val holes: List<TemplateHole<*>> = children.flatMap { it.second.holes }
    override fun render(bindings: Map<String, Any>): TemplateOutput = TemplateOutput(templateId = id, boundValues = bindings)
}

private class ConditionalComponent<A>(
    private val condition: Signal<Boolean>,
    private val component: SignalComponent<A>
) : SignalComponent<A?> {
    override val template: VisualTemplate = ConditionalTemplate(component.template)
    override val bindings: List<TemplateBinding<*>> = component.bindings + listOf(TemplateHole<Boolean>("visible")(condition))
    override val output: Signal<TemplateOutput> = object : Signal<TemplateOutput> {
        private val combinedFlow = combine(condition.changes, component.output.changes) { visible, out ->
            if (visible) out else TemplateOutput(templateId = template.id, boundValues = emptyMap())
        }
        override val value: TemplateOutput get() = if (condition.value) component.output.value else TemplateOutput(templateId = template.id, boundValues = emptyMap())
        override val changes: Flow<TemplateOutput> = combinedFlow
    }
}

private data class ConditionalTemplate(private val child: VisualTemplate) : VisualTemplate {
    override val id: String = "conditional_${child.id}"
    override val holes: List<TemplateHole<*>> = child.holes + listOf(TemplateHole<Boolean>("visible"))
    override fun render(bindings: Map<String, Any>): TemplateOutput = TemplateOutput(templateId = id, boundValues = bindings)
}

class TemplateBuilder {
    private val holeBuilders = mutableListOf<TemplateHole<*>>()
    val metadata = mutableMapOf<String, Any>()
    val id = "template_${hashCode()}"

    fun <T> hole(key: String): TemplateHole<T> {
        val hole = TemplateHole<T>(key)
        holeBuilders.add(hole)
        return hole
    }
    fun meta(key: String, value: Any): TemplateBuilder { metadata[key] = value; return this }

    fun build(renderFn: (Map<String, Any>) -> TemplateOutput): VisualTemplate =
        InlineTemplate(id = id, holes = holeBuilders.toList(), metadata = metadata.toMap(), renderFn)

    fun struct(holes: List<TemplateHole<*>>): VisualTemplate {
        holeBuilders.addAll(holes)
        return build { bindings -> TemplateOutput(templateId = id, boundValues = bindings, metadata = metadata) }
    }
}

private class InlineTemplate(
    override val id: String,
    override val holes: List<TemplateHole<*>>,
    val metadata: Map<String, Any>,
    private val renderFn: (Map<String, Any>) -> TemplateOutput
) : VisualTemplate {
    override fun render(bindings: Map<String, Any>): TemplateOutput = renderFn(bindings)
}

fun template(block: TemplateBuilder.() -> VisualTemplate): VisualTemplate {
    val builder = TemplateBuilder()
    builder.block()
    return builder.build { bindings -> TemplateOutput(templateId = builder.id, boundValues = bindings, metadata = builder.metadata) }
}

object StandardHoles {
    val toggle = TemplateHole<Boolean>("toggle")
    val light = TemplateHole<Boolean>("light")
    val button = TemplateHole<Boolean>("button")
    val slider = TemplateHole<Double>("slider")
    val knob = TemplateHole<Double>("knob")
    val dial = TemplateHole<Any>("dial")
    val level = TemplateHole<Double>("level")
    val label = TemplateHole<String>("label")
    val icon = TemplateHole<String>("icon")
    val textField = TemplateHole<TextFieldState>("textField")
}

fun toggleHole() = StandardHoles.toggle
fun lightHole() = StandardHoles.light
fun buttonHole() = StandardHoles.button
fun sliderHole() = StandardHoles.slider
fun knobHole() = StandardHoles.knob
fun dialHole() = StandardHoles.dial
fun levelHole() = StandardHoles.level
fun labelHole() = StandardHoles.label
fun iconHole() = StandardHoles.icon
fun textFieldHole() = StandardHoles.textField