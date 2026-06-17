package borg.trikeshed.forge

import borg.trikeshed.forge.platform.PlatformUtils
import kotlinx.serialization.Serializable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

/**
 * Patch-cable type system for n-dimensional signal routing.
 *
 * Each workflow step becomes a module with typed ports (inputs/outputs).
 * Cables connect output ports to input ports with explicit type contracts.
 * This enables real-time reconfiguration, hot-swapping, and signal introspection.
 */
@Serializable
data class PortSpec(
    val name: String,
    val portType: PortType,
    val direction: PortDirection,
    /** Type signature for validation (e.g., "String", "List<CascadeOutputRow>", "Map<String, String>") */
    val typeSignature: String,
    /** Optional default value for optional inputs */
    val defaultValue: String? = null,
    /** Metadata for UI/visualization */
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class PortType {
    /** Data signals: JSON-serializable values */
    DATA,
    /** Control signals: triggers, gates, clocks */
    CONTROL,
    /** Audio-rate signals: high-frequency streams */
    AUDIO,
    /** Video-rate signals: frame streams */
    VIDEO,
    /** CV (control voltage) signals: modulation */
    CV,
    /** Gate/triggers: discrete events */
    GATE,
    /** Polyphonic signals: multiple voices */
    POLY,
}

@Serializable
enum class PortDirection {
    INPUT, OUTPUT, BIDIRECTIONAL
}

/**
 * A cable connecting two ports with type-safe routing.
 * Cables carry signal metadata and can be hot-swapped at runtime.
 */
@Serializable
data class PatchCable(
    val id: CableId,
    val source: PortAddress,
    val destination: PortAddress,
    /** Signal transformation applied during transit (gain, filter, format conversion) */
    val transform: CableTransform? = null,
    /** Cable state: active, muted, soloed, bypassed */
    val state: CableState = CableState.ACTIVE,
    /** Latency compensation in samples/frames */
    val latency: Long = 0,
    /** Visual routing hints */
    val routing: CableRouting = CableRouting.DIRECT,
)

@Serializable
@JvmInline
value class CableId(val value: String) {
    companion object {
        fun generate(): CableId = CableId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class PortAddress(
    /** Module/step identifier */
    val moduleId: String,
    /** Port name on that module */
    val portName: String,
)

@Serializable
sealed interface CableTransform {
    @Serializable
    data class Gain(val factor: Double) : CableTransform
    @Serializable
    data class Filter(val filterType: String, val params: Map<String, Double>) : CableTransform
    @Serializable
    data class FormatConvert(val fromFormat: String, val toFormat: String) : CableTransform
    @Serializable
    data class MapTransform(val expression: String) : CableTransform  // JS expression: "input * 2"
    @Serializable
    data class Lag(val timeMs: Long) : CableTransform
    @Serializable
    data class Quantize(val steps: Int): CableTransform
    @Serializable
    data class SampleAndHold(val enabled: Boolean = true) : CableTransform
    @Serializable
    data class Custom(val functionRef: String, val params: Map<String, String>) : CableTransform
}

@Serializable
enum class CableState { ACTIVE, MUTED, SOLOED, BYPASSED }

@Serializable
enum class CableRouting { DIRECT, CURVED, ORTHOGONAL, BUNDLED }

/**
 * Patch bay: the complete routing graph for a workspace.
 * Supports multiple layers (signal, control, metadata) with independent routing.
 */
@Serializable
data class PatchBay(
    val id: PatchBayId,
    val name: String,
    val modules: Map<String, ModuleSpec>,
    val cables: List<PatchCable>,
    val layers: Map<String, LayerSpec> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
@JvmInline
value class PatchBayId(val value: String) {
    companion object {
        fun generate(): PatchBayId = PatchBayId(PlatformUtils.randomUuid())
    }
}

/**
 * Module specification: declares ports and internal behavior.
 * Each WorkflowStep maps to a ModuleSpec with typed ports.
 */
@Serializable
data class ModuleSpec(
    val id: String,
    val moduleType: ModuleType,
    val inputPorts: List<PortSpec>,
    val outputPorts: List<PortSpec>,
    /** Internal parameter ports (knobs, sliders, CV inputs) */
    val parameterPorts: List<PortSpec> = emptyList(),
    /** Current parameter values */
    val parameters: Map<String, String> = emptyMap(),
    /** Visual position in patch bay */
    val position: ModulePosition = ModulePosition(0.0, 0.0),
)

@Serializable
enum class ModuleType {
    /** LLM call module */
    LLM_CALL,
    /** Code execution module */
    CODE_EXECUTION,
    /** Agent invocation module */
    AGENT_INVOCATION,
    /** File transform module */
    FILE_TRANSFORM,
    /** Conditional routing module */
    CONDITIONAL,
    /** Parallel branching module */
    PARALLEL,
    /** Cascade execution module */
    CASCADE_EXECUTION,
    /** Custom module */
    CUSTOM,
    /** Signal processing: map/reduce/filter */
    MAP, REDUCE, FILTER, PROJECT, JOIN,
    /** Signal generators */
    OSCILLATOR, NOISE, SEQUENCER,
    /** Signal modifiers */
    FILTER_MODULE, DELAY, REVERB, DISTORTION,
    /** Control logic */
    GATE, TRIGGER, COMPARATOR, LOGIC,
    /** Utility */
    MIXER, SPLITTER, MERGER, SWITCH, MULTIPLEXER,
}

@Serializable
data class ModulePosition(val x: Double, val y: Double)

@Serializable
data class LayerSpec(
    val name: String,
    val layerType: LayerType,
    val visible: Boolean = true,
    val color: String = "#888888",
)

@Serializable
enum class LayerType {
    SIGNAL, CONTROL, METADATA, VISUALIZATION, DEBUG
}

/**
 * Patch bay graph for visualization — nodes are modules, edges are cables.
 */
@Serializable
data class PatchBayGraph(
    val patchBayId: PatchBayId,
    val nodes: List<PatchBayNode>,
    val edges: List<PatchBayEdge>,
)

@Serializable
data class PatchBayNode(
    val id: String,
    val moduleType: ModuleType,
    val label: String,
    val position: ModulePosition,
    val inputPorts: List<PortSpec>,
    val outputPorts: List<PortSpec>,
    val state: NodeState = NodeState.ACTIVE,
)

@Serializable
enum class NodeState { ACTIVE, BYPASSED, ERROR, PROCESSING }

@Serializable
data class PatchBayEdge(
    val id: CableId,
    val source: PortAddress,
    val destination: PortAddress,
    val state: CableState,
    val transform: CableTransform? = null,
)

@Serializable
enum class LayoutAlgorithm {
    FORCE_DIRECTED, HIERARCHICAL, CIRCULAR, GRID, ORTHOGONAL
}

/**
 * Real-time patch bay processor state for streaming execution.
 */
@Serializable
data class PatchBayProcessorState(
    val patchBayId: PatchBayId,
    val moduleStates: Map<String, ModuleProcessorState>,
    val cableBuffers: Map<CableId, List<String>>,  // signal history for lag/delay
    val frameCount: Long = 0,
    val sampleRate: Double = 44100.0,
    val blockSize: Int = 256,
)

@Serializable
data class ModuleProcessorState(
    val moduleId: String,
    val inputBuffers: Map<String, List<String>>,
    val outputBuffers: Map<String, List<String>>,
    val parameterValues: Map<String, String>,
    val lastProcessedFrame: Long = 0,
)

/**
 * Signal value with metadata for n-dimensional routing.
 */
@Serializable
data class SignalValue(
    val value: String,  // JSON-serialized value
    val typeSignature: String,
    val timestamp: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A single input file or context item in the forge workspace.
 * Could be Markdown, code, JSON, PDF, images, etc.
 */
@Serializable
data class ForgeFile(
    val id: ForgeFileId,
    val path: String,
    val content: String,
    val mimeType: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Unique identifier for a forge file.
 */
@Serializable
@JvmInline
value class ForgeFileId(val value: String) {
    companion object {
        fun generate(): ForgeFileId = ForgeFileId(PlatformUtils.randomUuid())
        fun fromPath(path: String): ForgeFileId = ForgeFileId(path)
    }
}

/**
 * A snapshot of the workspace at a point in time.
 * Enables VCS-like operations: diff, rollback, branch.
 */
@Serializable
data class ForgeSnapshot(
    val id: ForgeSnapshotId,
    val parentId: ForgeSnapshotId?,
    val files: Map<ForgeFileId, ForgeFile>,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val author: String = "unknown",
    val tags: Set<String> = emptySet(),
)

@Serializable
@JvmInline
value class ForgeSnapshotId(val value: String) {
    companion object {
        fun generate(): ForgeSnapshotId = ForgeSnapshotId(PlatformUtils.randomUuid())
        val ROOT = ForgeSnapshotId("root")
    }
}

/**
 * A stored prompt template with metadata.
 */
@Serializable
data class ForgePrompt(
    val id: ForgePromptId,
    val name: String,
    val template: String,
    val parameters: Map<String, PromptParameter>,
    val version: Int = 1,
    val tags: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
@JvmInline
value class ForgePromptId(val value: String) {
    companion object {
        fun generate(): ForgePromptId = ForgePromptId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class PromptParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val defaultValue: String? = null,
)

/**
 * An inference workflow - a directed graph of steps that transform inputs to outputs.
 * Steps can be: LLM calls, code execution, file transforms, agent invocations.
 */
@Serializable
data class ForgeWorkflow(
    val id: ForgeWorkflowId,
    val name: String,
    val steps: List<WorkflowStep>,
    val inputSchema: Map<String, String>,
    val outputSchema: Map<String, String>,
    val version: Int = 1,
    val tags: Set<String> = emptySet(),
)

@Serializable
@JvmInline
value class ForgeWorkflowId(val value: String) {
    companion object {
        fun generate(): ForgeWorkflowId = ForgeWorkflowId(PlatformUtils.randomUuid())
    }
}

@Serializable
sealed interface WorkflowStep {
    val id: String

    @Serializable
    data class LlmCall(
        override val id: String,
        val promptId: ForgePromptId,
        val inputs: Map<String, String>,
        val model: String,
        val parameters: Map<String, String> = emptyMap(),
    ) : WorkflowStep

    @Serializable
    data class CodeExecution(
        override val id: String,
        val language: String,
        val code: String,
        val inputs: Map<String, String>,
        val timeoutMs: Long = 30000,
    ) : WorkflowStep

    @Serializable
    data class AgentInvocation(
        override val id: String,
        val agentType: AgentType,
        val task: String,
        val context: Map<String, String>,
        val allowedTools: List<String> = emptyList(),
    ) : WorkflowStep

    @Serializable
    data class FileTransform(
        override val id: String,
        val inputFileIds: List<ForgeFileId>,
        val transform: String,
        val outputPath: String,
    ) : WorkflowStep

    @Serializable
    data class Conditional(
        override val id: String,
        val condition: String,
        val thenBranch: List<WorkflowStep>,
        val elseBranch: List<WorkflowStep> = emptyList(),
    ) : WorkflowStep

    @Serializable
    data class Parallel(
        override val id: String,
        val branches: List<List<WorkflowStep>>,
    ) : WorkflowStep

    @Serializable
    data class CascadeExecution(
        override val id: String,
        val cascadeId: CascadeId,
        val inputs: Map<String, String> = emptyMap(),
    ) : WorkflowStep
}

@Serializable
enum class AgentType {
    CODEX, CLAUDE_CODE, OPENCODE, GENERIC, CUSTOM
}

@Serializable
data class AgentConfig(
    val type: AgentType,
    val endpoint: String?,
    val apiKey: String?,
    val workingDirectory: String?,
    val environment: Map<String, String> = emptyMap(),
    val maxTurns: Int = 10,
    val timeoutMs: Long = 300000,
)

/**
 * Result of executing a workflow step.
 */
@Serializable
sealed interface StepResult {
    @Serializable
    data class Success(
        val stepId: String,
        val output: String,
        val artifacts: List<ForgeFile> = emptyList(),
        val metadata: Map<String, String> = emptyMap(),
    ) : StepResult

    @Serializable
    data class Failure(
        val stepId: String,
        val error: String,
        val partialOutput: String? = null,
    ) : StepResult

    @Serializable
    data class Streaming(
        val stepId: String,
        val stream: ReceiveChannel<String>,
    ) : StepResult
}

/**
 * Complete workflow execution result.
 */
@Serializable
data class ForgeExecutionResult(
    val executionId: ForgeExecutionId,
    val workflowId: ForgeWorkflowId,
    val snapshotId: ForgeSnapshotId,
    val stepResults: List<StepResult>,
    val finalOutputs: Map<String, String>,
    val artifacts: List<ForgeFile>,
    val startedAt: Long,
    val completedAt: Long,
    val status: ExecutionStatus,
)

@Serializable
@JvmInline
value class ForgeExecutionId(val value: String) {
    companion object {
        fun generate(): ForgeExecutionId = ForgeExecutionId(PlatformUtils.randomUuid())
    }
}

@Serializable
enum class ExecutionStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}

/**
 * Real-time collaboration event.
 */
@Serializable
sealed interface CollaborationEvent {
    @Serializable
    data class FileCreated(val file: ForgeFile, val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class FileUpdated(val fileId: ForgeFileId, val patch: String, val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class FileDeleted(val fileId: ForgeFileId, val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class SnapshotCreated(val snapshot: ForgeSnapshot, val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class WorkflowExecuted(val execution: ForgeExecutionResult, val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class UserJoined(val userId: ForgeUserId, val userName: String) : CollaborationEvent
    @Serializable
    data class UserLeft(val userId: ForgeUserId) : CollaborationEvent
    @Serializable
    data class CursorPosition(val userId: ForgeUserId, val fileId: ForgeFileId, val position: Int) : CollaborationEvent
}

@Serializable
@JvmInline
value class ForgeUserId(val value: String) {
    companion object {
        fun generate(): ForgeUserId = ForgeUserId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class ForgeUser(
    val id: ForgeUserId,
    val name: String,
    val color: String,
    val connectedAt: Long = System.currentTimeMillis(),
)

/**
 * Exported/sharable inference result.
 */
@Serializable
data class ForgeArtifact(
    val id: ForgeArtifactId,
    val name: String,
    val description: String,
    val files: List<ForgeFile>,
    val workflowId: ForgeWorkflowId?,
    val executionId: ForgeExecutionId?,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val isPublic: Boolean = false,
)

@Serializable
@JvmInline
value class ForgeArtifactId(val value: String) {
    companion object {
        fun generate(): ForgeArtifactId = ForgeArtifactId(PlatformUtils.randomUuid())
    }
}

@Serializable
enum class ExportFormat {
    ZIP, TAR_GZ, TAR, DIRECTORY, JSON
}

@Serializable
data class ForgeExportManifest(
    val artifactId: ForgeArtifactId,
    val artifactName: String,
    val exportedAt: Long,
    val fileCount: Int,
    val totalSize: Long,
    val workflowId: ForgeWorkflowId?,
    val executionId: ForgeExecutionId?,
)

data class ForgeExportBundle(
    val format: ExportFormat,
    val data: ByteArray,
    val manifest: ForgeExportManifest,
)

/**
 * Diff between two snapshots.
 */
data class ForgeDiff(
    val addedFiles: List<ForgeFile>,
    val removedFiles: List<ForgeFileId>,
    val modifiedFiles: List<ForgeFile>,  // new version
    val unchangedFiles: List<ForgeFileId>,
)

/**
 * Progress update during workflow execution.
 */
sealed interface StepProgress {
    data class StepStarted(val stepId: String, val stepName: String) : StepProgress
    data class StepMessage(val stepId: String, val message: String) : StepProgress
    data class StepCompleted(val stepId: String, val result: StepResult) : StepProgress
    data class StepFailed(val stepId: String, val error: String) : StepProgress
    data class WorkflowCompleted(val result: ForgeExecutionResult) : StepProgress
    data class WorkflowFailed(val error: String, val partialResults: List<StepResult>) : StepProgress
}