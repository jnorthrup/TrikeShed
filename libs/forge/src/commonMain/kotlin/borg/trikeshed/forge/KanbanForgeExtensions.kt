package borg.trikeshed.forge

/**
 * Forge-specific extensions on the root kanban types.
 *
 * The core types (KanbanBoard, KanbanCard, KanbanColumn, etc.) live in
 * borg.trikeshed.kanban (root commonMain) and are typealiased in this package.
 *
 * This file owns everything that needs CascadeGraph / PatchBay / WorkflowStep —
 * i.e. all the forge-internal rendering and routing extensions.
 */

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
            CardPriority.CRITICAL -> CascadeStageType.MAP
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

        // card -> column
        edges.add(CascadeEdge(from = card.id.value, to = card.columnId.value, dataFlow = "belongs-to"))

        // dependency edges
        card.dependencies.forEach { depId ->
            edges.add(CascadeEdge(from = depId.value, to = card.id.value, dataFlow = "blocks"))
        }
    }

    return CascadeGraph(cascadeId = CascadeId(this.id.value), nodes = nodes, edges = edges)
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
            appendLine("  ${edge.from} $arrow ${edge.to}")
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

// ─── PatchCableShapeDimension ───────────────────────────────────────────────

/**
 * Shape dimension enum for patch-cable modules.
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

fun WorkflowStep.getPatchCableShapeDimensions(): Set<PatchCableShapeDimension> = when (this) {
    is WorkflowStep.LlmCall -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.STOCHASTIC,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.MODULATION,
        PatchCableShapeDimension.HYBRID,
    )
    is WorkflowStep.CodeExecution -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.SEQUENCE,
        PatchCableShapeDimension.FEEDBACK,
    )
    is WorkflowStep.AgentInvocation -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.TEMPORAL,
        PatchCableShapeDimension.FEEDBACK,
        PatchCableShapeDimension.POLYPHONIC,
    )
    is WorkflowStep.FileTransform -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.EXTERNAL_IO,
        PatchCableShapeDimension.GRANULAR,
    )
    is WorkflowStep.Conditional -> setOf(
        PatchCableShapeDimension.CONTROL_FLOW,
        PatchCableShapeDimension.QUANTIZATION,
    )
    is WorkflowStep.Parallel -> setOf(
        PatchCableShapeDimension.PARALLELISM,
        PatchCableShapeDimension.CONTROL_FLOW,
        PatchCableShapeDimension.SPATIAL,
    )
    is WorkflowStep.CascadeExecution -> setOf(
        PatchCableShapeDimension.SIGNAL_FLOW,
        PatchCableShapeDimension.COMPOSITE,
        PatchCableShapeDimension.STATE_MUTATION,
        PatchCableShapeDimension.SPECTRAL,
        PatchCableShapeDimension.ENVELOPE,
    )
    else -> setOf(PatchCableShapeDimension.SIGNAL_FLOW)
}

fun ForgeWorkflow.toShapeDimensionDiagram(): String {
    val steps = this.steps
    return buildString {
        appendLine("graph TD")
        appendLine("  subgraph Dimensions [\"Shape Dimensions\"]")
        PatchCableShapeDimension.entries.forEach { dim ->
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

        steps.forEach { step ->
            val currentStepId = step.id
            val inputs = when (step) {
                is WorkflowStep.LlmCall -> step.inputs
                is WorkflowStep.CodeExecution -> step.inputs
                is WorkflowStep.AgentInvocation -> step.context
                is WorkflowStep.CascadeExecution -> step.inputs
                else -> emptyMap()
            }
            inputs.forEach { (inputName, inputValue) ->
                val refPattern = """\$\{(.+?)\.(.+?)\}""".toRegex()
                inputValue.replace(refPattern) { mr ->
                    val sourceStepId = mr.groupValues[1]
                    val sourceOutput = mr.groupValues[2]
                    appendLine("  $sourceStepId -.->|$sourceOutput → $inputName| $currentStepId")
                    ""
                }
            }
        }
    }
}

fun ForgeWorkflow.toPatchBayModules(patchBayId: PatchBayId): Map<String, ModuleSpec> {
    val modules = mutableMapOf<String, ModuleSpec>()
    var x = 0.0
    val y = 0.0
    val spacing = 200.0
    steps.forEach { step ->
        val moduleSpec = step.toModuleSpec(ModulePosition(x, y))
        modules[step.id] = moduleSpec
        x += spacing
    }
    return modules
}

fun ForgeWorkflow.toPatchCables(patchBayId: PatchBayId): List<PatchCable> {
    val cables = mutableListOf<PatchCable>()
    steps.forEach { step ->
        val currentStepId = step.id
        val inputs = when (step) {
            is WorkflowStep.LlmCall -> step.inputs
            is WorkflowStep.CodeExecution -> step.inputs
            is WorkflowStep.AgentInvocation -> step.context
            is WorkflowStep.CascadeExecution -> step.inputs
            else -> emptyMap()
        }
        inputs.forEach { (inputName, inputValue) ->
            val refPattern = """\{\{(.+?)\.(.+?)\}\}""".toRegex()
            inputValue.replace(refPattern) { mr ->
                val sourceStepId = mr.groupValues[1]
                val sourceOutput = mr.groupValues[2]
                cables.add(PatchCable(
                    id = CableId.generate(),
                    source = PortAddress(sourceStepId, sourceOutput),
                    destination = PortAddress(currentStepId, inputName),
                    state = CableState.ACTIVE,
                    routing = CableRouting.DIRECT,
                ))
                ""
            }
        }
    }
    return cables
}

fun WorkflowStep.toModuleSpec(position: ModulePosition): ModuleSpec = when (this) {
    is WorkflowStep.LlmCall -> ModuleSpec(
        id = id,
        moduleType = ModuleType.LLM_CALL,
        inputPorts = inputs.keys.map { n -> PortSpec(n, PortType.DATA, PortDirection.INPUT, "String") },
        outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String")),
        parameterPorts = listOf(
            PortSpec("model", PortType.CV, PortDirection.INPUT, "String", model),
            PortSpec("temperature", PortType.CV, PortDirection.INPUT, "Double", parameters["temperature"] ?: "0.7"),
        ),
        parameters = inputs,
        position = position,
    )
    is WorkflowStep.CodeExecution -> ModuleSpec(
        id = id,
        moduleType = ModuleType.CODE_EXECUTION,
        inputPorts = inputs.keys.map { n -> PortSpec(n, PortType.DATA, PortDirection.INPUT, "String") },
        outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String")),
        parameterPorts = listOf(
            PortSpec("language", PortType.CV, PortDirection.INPUT, "String", language),
            PortSpec("timeoutMs", PortType.CV, PortDirection.INPUT, "Long", timeoutMs.toString()),
        ),
        parameters = inputs,
        position = position,
    )
    is WorkflowStep.AgentInvocation -> ModuleSpec(
        id = id,
        moduleType = ModuleType.AGENT_INVOCATION,
        inputPorts = context.keys.map { n -> PortSpec(n, PortType.DATA, PortDirection.INPUT, "String") },
        outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String")),
        parameterPorts = listOf(
            PortSpec("agentType", PortType.CV, PortDirection.INPUT, "String", agentType.name),
            PortSpec("maxTurns", PortType.CV, PortDirection.INPUT, "Int", "10"),
        ),
        parameters = context,
        position = position,
    )
    is WorkflowStep.FileTransform -> ModuleSpec(
        id = id,
        moduleType = ModuleType.FILE_TRANSFORM,
        inputPorts = inputFileIds.mapIndexed { i, _ -> PortSpec("input$i", PortType.DATA, PortDirection.INPUT, "ForgeFile") },
        outputPorts = listOf(PortSpec("outputPath", PortType.DATA, PortDirection.OUTPUT, "String")),
        parameterPorts = listOf(PortSpec("transform", PortType.CV, PortDirection.INPUT, "String", transform)),
        parameters = emptyMap(),
        position = position,
    )
    is WorkflowStep.Conditional -> ModuleSpec(
        id = id,
        moduleType = ModuleType.CONDITIONAL,
        inputPorts = listOf(PortSpec("condition", PortType.DATA, PortDirection.INPUT, "Boolean")),
        outputPorts = listOf(
            PortSpec("then", PortType.DATA, PortDirection.OUTPUT, "Any"),
            PortSpec("else", PortType.DATA, PortDirection.OUTPUT, "Any"),
        ),
        parameterPorts = listOf(PortSpec("expression", PortType.CV, PortDirection.INPUT, "String", condition)),
        parameters = emptyMap(),
        position = position,
    )
    is WorkflowStep.Parallel -> ModuleSpec(
        id = id,
        moduleType = ModuleType.PARALLEL,
        inputPorts = emptyList(),
        outputPorts = branches.mapIndexed { i, _ -> PortSpec("branch$i", PortType.DATA, PortDirection.OUTPUT, "Any") },
        parameterPorts = listOf(PortSpec("branchCount", PortType.CV, PortDirection.INPUT, "Int", branches.size.toString())),
        parameters = emptyMap(),
        position = position,
    )
    is WorkflowStep.CascadeExecution -> ModuleSpec(
        id = id,
        moduleType = ModuleType.CASCADE_EXECUTION,
        inputPorts = inputs.keys.map { n -> PortSpec(n, PortType.DATA, PortDirection.INPUT, "String") },
        outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "CascadeOutputRow")),
        parameterPorts = listOf(PortSpec("cascadeId", PortType.CV, PortDirection.INPUT, "String", cascadeId.value)),
        parameters = inputs,
        position = position,
    )
    else -> throw IllegalArgumentException("Unknown WorkflowStep: ${this::class.simpleName}")
}

// ─── Pulled from WorkflowStep ── id field helper ────────────────────────────

private val WorkflowStep.id: String
    get() = when (this) {
        is WorkflowStep.LlmCall -> id
        is WorkflowStep.CodeExecution -> id
        is WorkflowStep.AgentInvocation -> id
        is WorkflowStep.FileTransform -> id
        is WorkflowStep.Conditional -> id
        is WorkflowStep.Parallel -> id
        is WorkflowStep.CascadeExecution -> id
        else -> this::class.simpleName ?: "unknown"
    }
