package borg.trikeshed.forge

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow

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
        fun generate(): ForgeFileId = ForgeFileId(UuidGenerator.generate())
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
        fun generate(): ForgeSnapshotId = ForgeSnapshotId(java.util.UUID.randomUUID().toString())
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
        fun generate(): ForgePromptId = ForgePromptId(java.util.UUID.randomUUID().toString())
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
        fun generate(): ForgeWorkflowId = ForgeWorkflowId(java.util.UUID.randomUUID().toString())
    }
}

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

    @Serializable
    data class CascadeExecution(
        val id: String,
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
        fun generate(): ForgeExecutionId = ForgeExecutionId(java.util.UUID.randomUUID().toString())
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
        fun generate(): ForgeUserId = ForgeUserId(java.util.UUID.randomUUID().toString())
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
        fun generate(): ForgeArtifactId = ForgeArtifactId(java.util.UUID.randomUUID().toString())
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