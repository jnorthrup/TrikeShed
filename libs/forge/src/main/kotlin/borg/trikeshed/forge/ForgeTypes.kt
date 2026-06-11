package borg.trikeshed.forge

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import kotlinx.serialization.Serializable
import kotlinx.coroutines.channels.ReceiveChannel

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
value class ForgeFileId(val value: String) {
    companion object {
        fun generate(): ForgeFileId = ForgeFileId(java.util.UUID.randomUUID().toString())
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
}

@Serializable
enum class AgentType {
    CODEX("Codex CLI - autonomous coding agent"),
    CLAUDE_CODE("Claude Code - autonomous coding agent"),
    OPENCODE("OpenCode - autonomous coding agent"),
    GENERIC("Generic agent via API"),
    CUSTOM("Custom agent implementation");

    val description: String
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

/**
 * Real-time collaboration event.
 */
@Serializable
sealed interface CollaborationEvent {
    @Serializable
    data class FileCreated(val file: ForgeFile, val userId: String) : CollaborationEvent
    @Serializable
    data class FileUpdated(val fileId: ForgeFileId, val patch: String, val userId: String) : CollaborationEvent
    @Serializable
    data class FileDeleted(val fileId: ForgeFileId, val userId: String) : CollaborationEvent
    @Serializable
    data class SnapshotCreated(val snapshot: ForgeSnapshot, val userId: String) : CollaborationEvent
    @Serializable
    data class WorkflowExecuted(val execution: ForgeExecutionResult, val userId: String) : CollaborationEvent
    @Serializable
    data class UserJoined(val userId: String, val userName: String) : CollaborationEvent
    @Serializable
    data class UserLeft(val userId: String) : CollaborationEvent
    @Serializable
    data class CursorPosition(val userId: String, val fileId: ForgeFileId, val position: Int) : CollaborationEvent
}

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
    val executionId: String?,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val isPublic: Boolean = false,
)

@Serializable
value class ForgeArtifactId(val value: String) {
    companion object {
        fun generate(): ForgeArtifactId = ForgeArtifactId(java.util.UUID.randomUUID().toString())
    }
}