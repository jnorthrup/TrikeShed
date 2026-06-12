package borg.trikeshed.usersignals

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

interface RenderBackend {
    val id: String
    fun render(output: TemplateOutput): RenderResult
    fun renderSequence(outputs: Flow<TemplateOutput>): Flow<RenderResult> = outputs.map { render(it) }
}

interface RenderResult {
    val content: Any
    val backendId: String
    val metadata: Map<String, Any>
}

data class TextRenderResult(
    override val content: String,
    override val backendId: String = "text",
    override val metadata: Map<String, Any> = emptyMap()
) : RenderResult

data class JsonRenderResult(
    override val content: String,
    override val backendId: String = "json",
    override val metadata: Map<String, Any> = emptyMap()
) : RenderResult

data class AnsiRenderResult(
    override val content: String,
    override val backendId: String = "ansi",
    override val metadata: Map<String, Any> = emptyMap()
) : RenderResult

class TextBackend : RenderBackend {
    override val id = "text"
    override fun render(output: TemplateOutput): RenderResult = TextRenderResult(
        content = formatText(output),
        metadata = output.metadata
    )

    private fun formatText(output: TemplateOutput): String {
        val sb = StringBuilder()
        sb.appendLine("┌─ ${output.templateId} ─")
        output.boundValues.forEach { (key, value) ->
            sb.appendLine("│ $key: ${formatValue(value)}")
        }
        output.metadata.forEach { (key, value) ->
            sb.appendLine("│ # $key: $value")
        }
        sb.appendLine("└${"─".repeat(output.templateId.length + 4)}")
        return sb.toString()
    }

    private fun formatValue(value: Any): String = when (value) {
        is Map<*, *> -> value.map { "${it.key}=${formatValue(it.value)}" }.joinToString(", ", "{", "}")
        is List<*> -> value.joinToString(", ", "[", "]") { formatValue(it) }
        is Double -> "%.3f".format(value)
        is Boolean -> if (value) "●" else "○"
        else -> value.toString()
    }
}

class JsonBackend : RenderBackend {
    override val id = "json"
    override fun render(output: TemplateOutput): RenderResult = JsonRenderResult(
        content = formatJson(output),
        metadata = output.metadata
    )

    private fun formatJson(output: TemplateOutput): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"templateId\":\"${output.templateId}\",")
        sb.append("\"values\":{")
        sb.append(output.boundValues.map { (k, v) -> "\"$k\":${toJson(v)}" }.joinToString(","))
        sb.append("},")
        sb.append("\"metadata\":{")
        sb.append(output.metadata.map { (k, v) -> "\"$k\":${toJson(v)}" }.joinToString(","))
        sb.append("}}")
        return sb.toString()
    }

    private fun toJson(value: Any): String = when (value) {
        is String -> "\"$value\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> "{${value.map { (k, v) -> "\"$k\":${toJson(v)}" }.joinToString(",")}}"
        is List<*> -> "[${value.map { toJson(it) }.joinToString(",")}]"
        else -> "\"$value\""
    }
}

class AnsiBackend : RenderBackend {
    override val id = "ansi"

    companion object {
        const val RESET = "\u001B[0m"
        const val BOLD = "\u001B[1m"
        const val DIM = "\u001B[2m"
        const val RED = "\u001B[31m"
        const val GREEN = "\u001B[32m"
        const val YELLOW = "\u001B[33m"
        const val BLUE = "\u001B[34m"
        const val MAGENTA = "\u001B[35m"
        const val CYAN = "\u001B[36m"
        const val WHITE = "\u001B[37m"
    }

    override fun render(output: TemplateOutput): RenderResult = AnsiRenderResult(
        content = formatAnsi(output),
        metadata = output.metadata
    )

    private fun formatAnsi(output: TemplateOutput): String {
        val sb = StringBuilder()
        sb.appendLine("${BOLD}${CYAN}╭─ ${output.templateId} ─╮${RESET}")
        output.boundValues.forEach { (key, value) ->
            val (color, symbol) = when (value) {
                is Boolean -> if (value) Pair(GREEN, "●") else Pair(RED, "○")
                is Double -> Pair(if (value > 0.5) YELLOW else BLUE, "▓")
                else -> Pair(WHITE, "▸")
            }
            sb.appendLine("${DIM}│${RESET} $key: ${color}$symbol${RESET} ${formatAnsiValue(value)}")
        }
        output.metadata.forEach { (key, value) ->
            sb.appendLine("${DIM}│${RESET} ${MAGENTA}# $key${RESET}: $value")
        }
        sb.appendLine("${BOLD}${CYAN}╰${"─".repeat(output.templateId.length + 4)}╯${RESET}")
        return sb.toString()
    }

    private fun formatAnsiValue(value: Any): String = when (value) {
        is Double -> "%.2f".format(value)
        is Boolean -> if (value) "ON" else "OFF"
        else -> value.toString()
    }
}

class RenderPipeline {
    private val backends = mutableListOf<RenderBackend>()
    private val componentOutputs = mutableListOf<SignalComponent<*>>()

    fun addBackend(backend: RenderBackend): RenderPipeline {
        backends.add(backend)
        return this
    }

    fun addComponent(component: SignalComponent<*>): RenderPipeline {
        componentOutputs.add(component)
        return this
    }

    fun renderFlows(): Map<String, Flow<RenderResult>> = backends.associateBy({ it.id }) { backend ->
        val combinedFlow = if (componentOutputs.size == 1) {
            componentOutputs[0].output.changes
        } else {
            kotlinx.coroutines.flow.combine(componentOutputs.map { it.output.changes }) { outputs ->
                TemplateOutput(
                    templateId = "combined_${outputs.hashCode()}",
                    boundValues = outputs.associateBy({ it.templateId }, { it.boundValues })
                )
            }
        }
        backend.renderSequence(combinedFlow)
    }

    suspend fun renderOnce(): List<RenderResult> = backends.map { backend ->
        val combined = if (componentOutputs.size == 1) {
            componentOutputs[0].output.value
        } else {
            TemplateOutput(
                templateId = "combined",
                boundValues = componentOutputs.associateBy({ it.template.id }, { it.output.value.boundValues })
            )
        }
        backend.render(combined)
    }
}

class ConsoleRenderer(
    private val pipeline: RenderPipeline,
    private val backend: RenderBackend = AnsiBackend()
) {
    private val job = kotlinx.coroutines.Job()
    private val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Default)

    fun start() {
        val flows = pipeline.renderFlows()
        val flow = flows[backend.id] ?: return
        scope.launch {
            flow.collect { result ->
                println(result.content)
            }
        }
    }

    fun stop() {
        job.cancel()
    }

    suspend fun renderFrame(): List<RenderResult> = pipeline.renderOnce()
}

class SignalTemplate(
    private val template: VisualTemplate,
    private val bindings: List<TemplateBinding<*>> = emptyList()
) {
    fun <T> bind(hole: TemplateHole<T>, signal: Signal<T>): SignalTemplate =
        SignalTemplate(template, bindings + TemplateBinding(hole, signal))

    fun label(text: String): SignalTemplate = bind(labelHole(), ConstSignal(text))
    fun icon(name: String): SignalTemplate = bind(iconHole(), ConstSignal(name))

    val output: Signal<TemplateOutput> = object : Signal<TemplateOutput> {
        private val _channel = Channel<TemplateOutput>(Channel.UNLIMITED)
        override val value: TemplateOutput
            get() = template.render(bindings.associate { it.hole.key to it.signal.value })
        override val changes: Flow<TemplateOutput> = _channel.receiveAsFlow()
        init { _channel.trySend(value) }
    }
}

private class ConstSignal<T>(private val constValue: T) : Signal<T> {
    override val value: T = constValue
    override val changes: Flow<T> = kotlinx.coroutines.flow.flowOf(constValue)
}

fun signalTemplate(block: SignalTemplateBuilder.() -> Unit): SignalTemplate =
    SignalTemplateBuilder().apply { block() }.build()

class SignalTemplateBuilder {
    private val templateBuilder = TemplateBuilder()
    private val bindingBuilders = mutableListOf<() -> TemplateBinding<*>>()

    fun <T> hole(key: String): TemplateHole<T> = templateBuilder.hole(key)
    fun toggleHole() = StandardHoles.toggle
    fun lightHole() = StandardHoles.light
    fun sliderHole() = StandardHoles.slider
    fun knobHole() = StandardHoles.knob
    fun levelHole() = StandardHoles.level
    fun labelHole() = StandardHoles.label
    fun iconHole() = StandardHoles.icon

    fun <T> bind(hole: TemplateHole<T>, signal: Signal<T>): SignalTemplateBuilder {
        bindingBuilders.add { TemplateBinding(hole, signal) }
        return this
    }

    fun label(text: String): SignalTemplateBuilder {
        bindingBuilders.add { TemplateBinding(StandardHoles.label, ConstSignal(text)) }
        return this
    }

    fun icon(name: String): SignalTemplateBuilder {
        bindingBuilders.add { TemplateBinding(StandardHoles.icon, ConstSignal(name)) }
        return this
    }

    fun build(): SignalTemplate {
        val template = templateBuilder.build { bindings ->
            TemplateOutput(templateId = templateBuilder.id, boundValues = bindings, metadata = templateBuilder.metadata)
        }
        val bindings = bindingBuilders.map { it() }
        return SignalTemplate(template, bindings)
    }
}