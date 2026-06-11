package borg.trikeshed.forge

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// =========================================================================
// Core Algebra Types (from PRELOAD.md)
// =========================================================================

typealias ForgeId<T> = Join<T, Int>  // (value, seq) - identity + version
typealias ForgeJoin<L, R> = Join<L, R>
typealias ForgeTwin<T> = Join<T, T>
typealias ForgeSeries<T> = Series<T>  // size j { i -> T }

// Infix constructors
infix fun <A, B> A.j(b: B): ForgeJoin<A, B> = ForgeJoin(this, b)

val <T> T.`↺`: () -> T get() = { this }

infix fun <X, C, V : ForgeSeries<X>> V.α(crossinline xform: (X) -> C): ForgeSeries<C> =
    (this as ForgeSeries<X>).size j { i -> xform((this as ForgeSeries<X>)[i]) }

// =========================================================================
// Typealiases for Forge IDs (sealed by value class pattern)
// =========================================================================

@Serializable
value class ForgeFileId(val value: String)
@Serializable
value class ForgeSnapshotId(val value: String)
@Serializable
value class ForgePromptId(val value: String)
@Serializable
value class ForgeWorkflowId(val value: String)
@Serializable
value class ForgeArtifactId(val value: String)
@Serializable
value class ForgeExecutionId(val value: String)
@Serializable
value class ForgeUserId(val value: String)

// =========================================================================
// File Algebra
// =========================================================================

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

/** File store = Series<ForgeFile> indexed by position, keyed by ForgeFileId via metadata */
typealias ForgeFileStore = ForgeSeries<ForgeFile>

/** File view = lazy projection over store */
inline fun ForgeFileStore.viewById(): Map<ForgeFileId, ForgeFile> =
    this.α { it.id to it }.toMap()

// =========================================================================
// Snapshot Algebra
// =========================================================================

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

/** Snapshot history = Series<ForgeSnapshot> */
typealias ForgeHistory = ForgeSeries<ForgeSnapshot>

/** Diff = (added, removed, modified) as Triple<ForgeSeries<ForgeFile>> */
typealias ForgeDiff = Join<ForgeSeries<ForgeFile>, Join<ForgeSeries<ForgeFileId>, ForgeSeries<ForgeFile>>>
// Added files, Removed file IDs, Modified files (new versions)

inline val ForgeHistory.head: ForgeSnapshot? get() = if (size > 0) this[size - 1] else null

// =========================================================================
// Prompt Algebra
// =========================================================================

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
data class PromptParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val defaultValue: String? = null,
)

/** Prompt library = Series<ForgePrompt> */
typealias ForgePromptLibrary = ForgeSeries<ForgePrompt>

// =========================================================================
// Workflow Algebra
// =========================================================================

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
sealed interface WorkflowStep {
    @Serializable
    data class LlmCall(
        val id: String,
        val promptId: ForgePromptId,
        val inputs: Map<String, String>,
        val model: String,
        val parameters: Map<String, String> = emptyMap(),
    ) : WorkflowStep

    @Serializable
    data class CodeExecution(
        val id: String,
        val language: String,
        val code: String,
        val inputs: Map<String, String>,
        val timeoutMs: Long = 30000,
    ) : WorkflowStep

    @Serializable
    data class AgentInvocation(
        val id: String,
        val agentType: AgentType,
        val task: String,
        val context: Map<String, String>,
        val allowedTools: List<String> = emptyList(),
    ) : WorkflowStep

    @Serializable
    data class FileTransform(
        val id: String,
        val inputFileIds: List<ForgeFileId>,
        val transform: String,
        val outputPath: String,
    ) : WorkflowStep

    @Serializable
    data class Conditional(
        val id: String,
        val condition: String,
        val thenBranch: List<WorkflowStep>,
        val elseBranch: List<WorkflowStep> = emptyList(),
    ) : WorkflowStep

    @Serializable
    data class Parallel(
        val id: String,
        val branches: List<List<WorkflowStep>>,
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

/** Workflow registry = Series<ForgeWorkflow> */
typealias ForgeWorkflowRegistry = ForgeSeries<ForgeWorkflow>

// =========================================================================
// Execution Algebra
// =========================================================================

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
enum class ExecutionStatus {
    PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
}

/** Execution history = Series<ForgeExecutionResult> */
typealias ForgeExecutionHistory = ForgeSeries<ForgeExecutionResult>

/** Step progress as a stream = Flow<StepProgress> */
sealed interface StepProgress {
    data class Started(val stepId: String, val stepName: String) : StepProgress
    data class Progress(val stepId: String, val message: String) : StepProgress
    data class Completed(val stepId: String, val result: StepResult) : StepProgress
    data class Failed(val stepId: String, val error: String) : StepProgress
    data class WorkflowCompleted(val result: ForgeExecutionResult) : StepProgress
    data class WorkflowFailed(val error: String, val partialResults: List<StepResult>) : StepProgress
}

// =========================================================================
// Collaboration Algebra
// =========================================================================

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
data class ForgeUser(
    val id: ForgeUserId,
    val name: String,
    val color: String,
    val connectedAt: Long = System.currentTimeMillis(),
)

/** Active users = Series<ForgeUser> */
typealias ForgeActiveUsers = ForgeSeries<ForgeUser>

/** Event stream = Flow<CollaborationEvent> */
typealias ForgeEventStream = Flow<CollaborationEvent>

// =========================================================================
// Artifact Algebra
// =========================================================================

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

/** Artifact collection = Series<ForgeArtifact> */
typealias ForgeArtifactCollection = ForgeSeries<ForgeArtifact>

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

// =========================================================================
// Core Contracts (Interfaces)
// =========================================================================

/**
 * Workspace contract - pure algebra, no implementation.
 * All operations return projected views or suspended values.
 */
interface ForgeWorkspace {

    // File algebra
    suspend fun put(file: ForgeFile): ForgeFile
    suspend fun get(id: ForgeFileId): ForgeFile?
    suspend fun delete(id: ForgeFileId): Boolean
    suspend fun list(): ForgeFileStore
    suspend fun search(query: String): ForgeFileStore
    fun stream(id: ForgeFileId): ReceiveChannel<String>?

    // Snapshot algebra
    suspend fun snapshot(message: String, tags: Set<String> = emptySet()): ForgeSnapshot
    suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot?
    suspend fun history(): ForgeHistory
    suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot
    suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff
    suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot
    suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot

    // Prompt algebra
    suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt
    suspend fun getPrompt(id: ForgePromptId): ForgePrompt?
    suspend fun listPrompts(): ForgePromptLibrary
    suspend fun searchPrompts(query: String): ForgePromptLibrary
    suspend fun deletePrompt(id: ForgePromptId): Boolean

    // Workflow algebra
    suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow
    suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow?
    suspend fun listWorkflows(): ForgeWorkflowRegistry
    suspend fun searchWorkflows(query: String): ForgeWorkflowRegistry
    suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean

    // Execution algebra
    fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig> = emptyMap(), snapshotId: ForgeSnapshotId? = null): Flow<StepProgress>
    suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig> = emptyMap(), snapshotId: ForgeSnapshotId? = null): ForgeExecutionResult
    suspend fun cancel(executionId: ForgeExecutionId): Boolean
    suspend fun executions(workflowId: ForgeWorkflowId? = null): ForgeExecutionHistory

    // Collaboration algebra
    fun events(): ForgeEventStream
    suspend fun emit(event: CollaborationEvent)
    suspend fun users(): ForgeActiveUsers
    suspend fun join(user: ForgeUser)
    suspend fun leave(userId: ForgeUserId)

    // Artifact algebra
    suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean = false): ForgeArtifact
    suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact?
    suspend fun listArtifacts(publicOnly: Boolean = false): ForgeArtifactCollection
    suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle
    suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact
}

/**
 * Step runner contract - executes individual workflow steps.
 */
interface ForgeStepRunner {
    suspend fun runLlmCall(step: WorkflowStep.LlmCall, inputs: Map<String, String>, modelConfig: Map<String, String>): StepResult
    suspend fun runCode(step: WorkflowStep.CodeExecution, inputs: Map<String, String>, workingDir: String): StepResult
    suspend fun runAgent(step: WorkflowStep.AgentInvocation, inputs: Map<String, String>, config: AgentConfig, workingDir: String): StepResult
    suspend fun runTransform(step: WorkflowStep.FileTransform, workspace: ForgeWorkspace): StepResult
    suspend fun evalConditional(step: WorkflowStep.Conditional, inputs: Map<String, String>): Boolean
    suspend fun runParallel(step: WorkflowStep.Parallel, inputs: Map<String, String>, runBranch: (List<WorkflowStep>, Map<String, String>) -> List<StepResult>): List<StepResult>
}

/**
 * Agent runner contract - encapsulates Codex, Claude Code, etc.
 */
interface ForgeAgentRunner {
    fun run(config: AgentConfig, task: String, context: Map<String, String>, workingDir: String): ReceiveChannel<AgentEvent>
    suspend fun isAvailable(): Boolean
    val agentType: AgentType
}

/**
 * Agent events - streamed during execution.
 */
sealed interface AgentEvent {
    data class Output(val text: String) : AgentEvent
    data class ToolCall(val tool: String, val args: Map<String, String>) : AgentEvent
    data class ToolResult(val tool: String, val result: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
    data class Completed(val finalOutput: String) : AgentEvent
    data class Progress(val message: String) : AgentEvent
}