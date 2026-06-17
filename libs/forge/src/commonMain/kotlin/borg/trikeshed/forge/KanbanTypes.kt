package borg.trikeshed.forge

import borg.trikeshed.forge.platform.PlatformUtils
import kotlinx.serialization.Serializable

/**
 * Kanban board — task visualization backed by Forge workspace.
 * 
 * Maps to CascadeGraph for graphviz/HTML rendering:
 * - Columns → SOURCE/SINK nodes
 * - Cards → intermediate nodes
 * - Dependencies → edges
 * - Swimlanes → color-coded edge groups
 */
@Serializable
data class KanbanBoard(
    val id: KanbanBoardId,
    val name: String,
    val columns: List<KanbanColumn>,
    val cards: List<KanbanCard>,
    val swimlanes: List<Swimlane> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class KanbanBoardId(val value: String) {
    companion object {
        fun generate(): KanbanBoardId = KanbanBoardId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class KanbanColumn(
    val id: KanbanColumnId,
    val name: String,
    val order: Int,
    val wipLimit: Int? = null,  // Work-in-progress limit
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class KanbanColumnId(val value: String) {
    companion object {
        fun generate(): KanbanColumnId = KanbanColumnId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class KanbanCard(
    val id: KanbanCardId,
    val title: String,
    val description: String = "",
    val columnId: KanbanColumnId,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: CardPriority = CardPriority.MEDIUM,
    val dependencies: List<KanbanCardId> = emptyList(),  // blocks other cards
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = PlatformUtils.currentTimeMillis(),
    val updatedAt: Long = PlatformUtils.currentTimeMillis(),
)

@Serializable
data class KanbanCardId(val value: String) {
    companion object {
        fun generate(): KanbanCardId = KanbanCardId(PlatformUtils.randomUuid())
    }
}

@Serializable
enum class CardPriority { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
data class Swimlane(
    val id: SwimlaneId,
    val name: String,
    val color: String,  // hex color
    val cardIds: List<KanbanCardId> = emptyList(),
)

@Serializable
data class SwimlaneId(val value: String) {
    companion object {
        fun generate(): SwimlaneId = SwimlaneId(PlatformUtils.randomUuid())
    }
}

/**
 * Convert KanbanBoard to CascadeGraph for visualization.
 */
fun KanbanBoard.toCascadeGraph(): CascadeGraph {
    val nodes = mutableListOf<CascadeNode>()
    val edges = mutableListOf<CascadeEdge>()
    
    // Column nodes as SOURCE/SINK
    columns.forEach { col ->
        nodes.add(CascadeNode(
            id = col.id.value,
            type = CascadeStageType.SOURCE,
            label = col.name,
            config = mapOf("wipLimit" to (col.wipLimit?.toString() ?: "unlimited")),
        ))
    }
    
    // Card nodes as intermediate stages
    cards.forEach { card ->
        val stageType = when (card.priority) {
            CardPriority.CRITICAL -> CascadeStageType.MAP  // highlighted
            CardPriority.HIGH -> CascadeStageType.MAP
            else -> CascadeStageType.FILTER
        }
        nodes.add(CascadeNode(
            id = card.id.value,
            type = stageType,
            label = card.title,
            config = mapOf(
                "column" to card.columnId.value,
                "priority" to card.priority.name,
                "assignee" to (card.assignee ?: ""),
            ),
        ))
        
        // Edges: card -> column
        edges.add(CascadeEdge(
            from = card.id.value,
            to = card.columnId.value,
            dataFlow = "belongs-to",
        ))
        
        // Edges: dependencies
        card.dependencies.forEach { depId ->
            edges.add(CascadeEdge(
                from = depId.value,
                to = card.id.value,
                dataFlow = "blocks",
            ))
        }
    }
    
    return CascadeGraph(
        cascadeId = CascadeId(this.id.value),
        nodes = nodes,
        edges = edges,
    )
}

/**
 * Render KanbanBoard as Mermaid diagram.
 */
fun KanbanBoard.toMermaid(): String {
    val graph = toCascadeGraph()
    return buildString {
        appendLine("graph LR")
        graph.nodes.forEach { node ->
            val shape = when (node.type) {
                CascadeStageType.SOURCE -> "[${node.label}]"
                CascadeStageType.SINK -> "]${node.label}["
                else -> "(${node.label})"
            }
            appendLine("  ${node.id}$shape")
        }
        graph.edges.forEach { edge ->
            val arrow = when (edge.dataFlow) {
                "blocks" -> "-->|blocked|"
                else -> "-->"
            }
            appendLine("  ${edge.from} ${arrow} ${edge.to}")
        }
    }
}

/**
 * Render KanbanBoard as Graphviz DOT.
 */
fun KanbanBoard.toDot(): String {
    val graph = toCascadeGraph()
    return buildString {
        appendLine("digraph ${this@toDot.id.value} {")
        appendLine("  rankdir=LR;")
        appendLine("  node [shape=box;]")
        
        graph.nodes.forEach { node ->
            val style = when (node.type) {
                CascadeStageType.SOURCE -> "shape=doubleoctagon,"
                CascadeStageType.SINK -> "shape=doublecircle,"
                else -> ""
            }
            val priority = node.config["priority"]
            val color = when (priority) {
                "CRITICAL" -> "red"
                "HIGH" -> "orange"
                else -> "black"
            }
            appendLine("  ${node.id} [${style}label=\"${node.label}\",color=$color];")
        }
        
        graph.edges.forEach { edge ->
            val style = when (edge.dataFlow) {
                "blocks" -> "style=bold,color=red,"
                else -> ""
            }
            appendLine("  ${edge.from} -> ${edge.to} [${style}label=\"${edge.dataFlow}\";")
        }
        appendLine("}")
    }
}

/**
 * Shape dimension enum for patch-cable modules.
 * Each dimension adds visual/semantic meaning to the module shape.
 */
enum class PatchCableShapeDimension(
    val dimName: String,
    val description: String,
    val visualHint: String,
) {
    SIGNAL_FLOW("signal_flow", "Data flows through the module", "→"),
    CONTROL_FLOW("control_flow", "Control/branching logic", "⚡"),
    STATE_MUTATION("state_mutation", "Module mutates shared state", "💾"),
    PARALLELISM("parallelism", "Module spawns parallel branches", "⏱"),
    EXTERNAL_IO("external_io", "Module interacts with external systems", "🌐"),
    TEMPORAL("temporal", "Module has time-based behavior", "⏳"),
    STOCHASTIC("stochastic", "Module has non-deterministic output", "🎲"),
    COMPOSITE("composite", "Module contains sub-modules", "📦"),
    // Modernized patch-cable dimensions — increased shape dimensionality
    FEEDBACK("feedback", "Module has feedback/recursive loops", "🔄"),
    MODULATION("modulation", "Module modulates other signals", "〰"),
    QUANTIZATION("quantization", "Module quantizes/discretizes signals", "⎯⎯"),
    SPATIAL("spatial", "Module has spatial/geometric properties", "📐"),
    SPECTRAL("spectral", "Module operates in frequency domain", "📊"),
    ENVELOPE("envelope", "Module shapes amplitude over time", "⋏"),
    SEQUENCE("sequence", "Module generates/processes sequences", "⏭"),
    POLYPHONIC("polyphonic", "Module handles multiple voices/channels", "🎹"),
    GRANULAR("granular", "Module processes micro-sound grains", "⬤"),
    HYBRID("hybrid", "Module combines multiple signal domains", "⚛"),
}

/**
 * Get shape dimensions for a workflow step.
 */
fun WorkflowStep.getPatchCableShapeDimensions(): Set<PatchCableShapeDimension> = when (this) {
    is WorkflowStep.LlmCall -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.STOCHASTIC,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.MODULATION,  // LLMs modulate output based on prompts
        PatchCableShapeDimension.HYBRID,      // LLMs blend reasoning + generation
    )
    is WorkflowStep.CodeExecution -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.SEQUENCE,    // Code executes sequentially
        PatchCableShapeDimension.FEEDBACK,    // Loops/recursion
    )
    is WorkflowStep.AgentInvocation -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.TEMPORAL,
        PatchCableShapeDimension.FEEDBACK,    // Agent loops
        PatchCableShapeDimension.POLYPHONIC,  // Multi-turn conversations
    )
    is WorkflowStep.FileTransform -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.GRANULAR,    // Chunked processing
    )
    is WorkflowStep.Conditional -> setOf(
        PatchCableShapeDimension.CONTROL_FLOW,
        PatchCableShapeDimension.QUANTIZATION, // Discrete branching
    )
    is WorkflowStep.Parallel -> setOf(
        PatchCableShapeDimension.PARALLELISM,
        PatchCableShapeDimension.CONTROL_FLOW,
        PatchCableShapeDimension.SPATIAL,     // Parallel = spatial distribution
    )
    is WorkflowStep.CascadeExecution -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.COMPOSITE,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.SPECTRAL,    // Map-reduce = spectral decomposition
        PatchCableShapeDimension.ENVELOPE,    // Cascade has attack/sustain/release
    )
}

/**
 * Render workflow with shape dimensions annotated (Mermaid).
 */
fun ForgeWorkflow.toShapeDimensionDiagram(): String {
    val wfId = this.id.value
    val steps = this.steps
    return buildString {
        appendLine("graph TD")
        appendLine("  subgraph Dimensions [\"Shape Dimensions\"]")
        PatchCableShapeDimension.values().forEach { dim ->
            appendLine("    dim_${dim.dimName}[\"${dim.visualHint} ${dim.dimName}\n${dim.description}\"]")
            appendLine("    style dim_${dim.dimName} fill:#333,stroke:#${dim.ordinal.toString(16).padStart(6, '0')},stroke-width:2px,color:#fff")
        }
        appendLine("  end")
        appendLine("")

        steps.forEach { step ->
            val dims = step.getPatchCableShapeDimensions()
            val dimLabels = dims.map { it.visualHint }.joinToString(" ")
            val stepId = step.id
            appendLine("  $stepId[\"$stepId\n$dimLabels\"]")
            val primaryDim = dims.firstOrNull() ?: PatchCableShapeDimension.SIGNAL_FLOW
            val color = when (primaryDim) {
                PatchCableShapeDimension.SIGNAL_FLOW -> "#4A90D9"
                PatchCableShapeDimension.CONTROL_FLOW -> "#D94AD9"
                PatchCableShapeDimension.STATE_MUTATION -> "#E8A838"
                PatchCableShapeDimension.PARALLELISM -> "#4AD9D9"
                PatchCableShapeDimension.EXTERNAL_IO -> "#D94A4A"
                PatchCableShapeDimension.TEMPORAL -> "#D9A84A"
                PatchCableShapeDimension.STOCHASTIC -> "#A02EA0"
                PatchCableShapeDimension.COMPOSITE -> "#2E8A8A"
                // Modernized patch-cable dimensions
                PatchCableShapeDimension.FEEDBACK -> "#8A4AD9"
                PatchCableShapeDimension.MODULATION -> "#D98A4A"
                PatchCableShapeDimension.QUANTIZATION -> "#4AD98A"
                PatchCableShapeDimension.SPATIAL -> "#D94AD9"
                PatchCableShapeDimension.SPECTRAL -> "#8AD94A"
                PatchCableShapeDimension.ENVELOPE -> "#D94A8A"
                PatchCableShapeDimension.SEQUENCE -> "#4A4AD9"
                PatchCableShapeDimension.POLYPHONIC -> "#D9D94A"
                PatchCableShapeDimension.GRANULAR -> "#4AD9D9"
                PatchCableShapeDimension.HYBRID -> "#D98AD9"
            }
            appendLine("  style $stepId fill:$color,stroke:#fff,stroke-width:2px,color:#fff")
        }

        // Patch cables
        steps.forEach { step ->
            val currentStepId = step.id
            val inputs = when (step) {
                is WorkflowStep.LlmCall -> step.inputs
                is WorkflowStep.CodeExecution -> step.inputs
                is WorkflowStep.AgentInvocation -> step.context
                is WorkflowStep.FileTransform -> emptyMap<String, String>()
                is WorkflowStep.Conditional -> emptyMap<String, String>()
                is WorkflowStep.Parallel -> emptyMap<String, String>()
                is WorkflowStep.CascadeExecution -> step.inputs
                else -> emptyMap<String, String>()
            }
            inputs.forEach { entry ->
                val inputName = entry.key
                val inputValue = entry.value
                val refPattern = """\$\{(.+?)\.(.+?)\}""".toRegex()
                inputValue.replace(refPattern) { matchResult ->
                    val sourceStepId = matchResult.groupValues[1]
                    val sourceOutput = matchResult.groupValues[2]
                    appendLine("  $sourceStepId -.->|$sourceOutput → $inputName| $currentStepId")
                    ""
                }
            }
        }
    }
}

/**
 * Convert workflow to patch bay modules (for real-time signal routing).
 */
fun ForgeWorkflow.toPatchBayModules(patchBayId: PatchBayId): Map<String, ModuleSpec> {
    val modules = mutableMapOf<String, ModuleSpec>()
    var x = 0.0
    val y = 0.0
    val spacing = 200.0
    val steps = this.steps

    steps.forEach { step ->
        val moduleSpec = step.toModuleSpec(ModulePosition(x, y))
        val stepId = when (step) {
            is WorkflowStep.LlmCall -> step.id
            is WorkflowStep.CodeExecution -> step.id
            is WorkflowStep.AgentInvocation -> step.id
            is WorkflowStep.FileTransform -> step.id
            is WorkflowStep.Conditional -> step.id
            is WorkflowStep.Parallel -> step.id
            is WorkflowStep.CascadeExecution -> step.id
            else -> ""
        }
        modules[stepId] = moduleSpec
        x += spacing
    }
    
    return modules
}

/**
 * Convert workflow to patch cables based on input references.
 */
fun ForgeWorkflow.toPatchCables(patchBayId: PatchBayId): List<PatchCable> {
    val cables = mutableListOf<PatchCable>()
    val steps = this.steps

    steps.forEach { step ->
        val currentStepId = step.id
        val inputs = when (step) {
            is WorkflowStep.LlmCall -> step.inputs
            is WorkflowStep.CodeExecution -> step.inputs
            is WorkflowStep.AgentInvocation -> step.context
            is WorkflowStep.FileTransform -> emptyMap<String, String>()
            is WorkflowStep.Conditional -> emptyMap<String, String>()
            is WorkflowStep.Parallel -> emptyMap<String, String>()
            is WorkflowStep.CascadeExecution -> step.inputs
            else -> emptyMap<String, String>()
        }

        inputs.forEach { entry ->
            val inputName = entry.key
            val inputValue = entry.value
            val refPattern = """\{\{(.+?)\.(.+?)\}\}""".toRegex()
            inputValue.replace(refPattern) { matchResult ->
                val sourceStepId = matchResult.groupValues[1]
                val sourceOutput = matchResult.groupValues[2]
                val cable = PatchCable(
                    id = CableId.generate(),
                    source = PortAddress(sourceStepId, sourceOutput),
                    destination = PortAddress(currentStepId, inputName),
                    state = CableState.ACTIVE,
                    routing = CableRouting.DIRECT,
                )
                cables.add(cable)
                ""
            }
        }
    }

    return cables
}

/**
 * Convert a single workflow step to a ModuleSpec for patch bay.
 */
fun WorkflowStep.toModuleSpec(position: ModulePosition): ModuleSpec = when (this) {
    is WorkflowStep.LlmCall -> {
        val moduleType = ModuleType.LLM_CALL
        val inputPorts = inputs.keys.map { name ->
            PortSpec(name, PortType.DATA, PortDirection.INPUT, "String")
        }
        val outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
        val parameterPorts = listOf(
            PortSpec("model", PortType.CV, PortDirection.INPUT, "String", model),
            PortSpec("temperature", PortType.CV, PortDirection.INPUT, "Double", parameters["temperature"] ?: "0.7"),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = inputs,
            position = position,
        )
    }
    is WorkflowStep.CodeExecution -> {
        val moduleType = ModuleType.CODE_EXECUTION
        val inputPorts = inputs.keys.map { name ->
            PortSpec(name, PortType.DATA, PortDirection.INPUT, "String")
        }
        val outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
        val parameterPorts = listOf(
            PortSpec("language", PortType.CV, PortDirection.INPUT, "String", language),
            PortSpec("timeoutMs", PortType.CV, PortDirection.INPUT, "Long", timeoutMs.toString()),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = inputs,
            position = position,
        )
    }
    is WorkflowStep.AgentInvocation -> {
        val moduleType = ModuleType.AGENT_INVOCATION
        val inputPorts = context.keys.map { name ->
            PortSpec(name, PortType.DATA, PortDirection.INPUT, "String")
        }
        val outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
        val parameterPorts = listOf(
            PortSpec("agentType", PortType.CV, PortDirection.INPUT, "String", agentType.name),
            PortSpec("maxTurns", PortType.CV, PortDirection.INPUT, "Int", "10"),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = context,
            position = position,
        )
    }
    is WorkflowStep.FileTransform -> {
        val moduleType = ModuleType.FILE_TRANSFORM
        val inputPorts = inputFileIds.mapIndexed { index, _ ->
            PortSpec("input$index", PortType.DATA, PortDirection.INPUT, "ForgeFile")
        }
        val outputPorts = listOf(PortSpec("outputPath", PortType.DATA, PortDirection.OUTPUT, "String"))
        val parameterPorts = listOf(
            PortSpec("transform", PortType.CV, PortDirection.INPUT, "String", transform),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = emptyMap(),
            position = position,
        )
    }
    is WorkflowStep.Conditional -> {
        val moduleType = ModuleType.CONDITIONAL
        val inputPorts = listOf(
            PortSpec("condition", PortType.DATA, PortDirection.INPUT, "Boolean"),
        )
        val outputPorts = listOf(
            PortSpec("then", PortType.DATA, PortDirection.OUTPUT, "Any"),
            PortSpec("else", PortType.DATA, PortDirection.OUTPUT, "Any"),
        )
        val parameterPorts = listOf(
            PortSpec("expression", PortType.CV, PortDirection.INPUT, "String", condition),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = emptyMap(),
            position = position,
        )
    }
    is WorkflowStep.Parallel -> {
        val moduleType = ModuleType.PARALLEL
        val inputPorts = emptyList<PortSpec>()
        val outputPorts = branches.mapIndexed { index, _ ->
            PortSpec("branch$index", PortType.DATA, PortDirection.OUTPUT, "Any")
        }
        val parameterPorts = listOf(
            PortSpec("branchCount", PortType.CV, PortDirection.INPUT, "Int", branches.size.toString()),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = emptyMap(),
            position = position,
        )
    }
    is WorkflowStep.CascadeExecution -> {
        val moduleType = ModuleType.CASCADE_EXECUTION
        val inputPorts = inputs.keys.map { name ->
            PortSpec(name, PortType.DATA, PortDirection.INPUT, "String")
        }
        val outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "CascadeOutputRow"))
        val parameterPorts = listOf(
            PortSpec("cascadeId", PortType.CV, PortDirection.INPUT, "String", cascadeId.value),
        )
        ModuleSpec(
            id = id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            parameterPorts = parameterPorts,
            parameters = inputs,
            position = position,
        )
    }
    else -> throw IllegalArgumentException("Unknown WorkflowStep type: ${this::class.simpleName}")
}

