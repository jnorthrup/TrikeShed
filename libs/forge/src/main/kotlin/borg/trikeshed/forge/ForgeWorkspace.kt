package borg.trikeshed.forge

import borg.trikeshed.lib.Series
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

/**
 * Main workspace interface for Forge.
 * Manages files, snapshots, prompts, workflows, and executions.
 */
interface ForgeWorkspace {

    // =========================================================================
    // File Management
    // =========================================================================

    /**
     * Add or update a file in the workspace.
     */
    suspend fun putFile(file: ForgeFile): ForgeFile

    /**
     * Get a file by ID.
     */
    suspend fun getFile(id: ForgeFileId): ForgeFile?

    /**
     * Delete a file.
     */
    suspend fun deleteFile(id: ForgeFileId): Boolean

    /**
     * List all files in the workspace.
     */
    suspend fun listFiles(): Map<ForgeFileId, ForgeFile>

    /**
     * Search files by content or metadata.
     */
    suspend fun searchFiles(query: String): List<ForgeFile>

    /**
     * Read file content as a stream (for large files).
     */
    fun readFileStream(id: ForgeFileId): ReceiveChannel<String>?

    // =========================================================================
    // Snapshot / Version Control
    // =========================================================================

    /**
     * Create a snapshot of current workspace state.
     */
    suspend fun createSnapshot(message: String, tags: Set<String> = emptySet()): ForgeSnapshot

    /**
     * Get a snapshot by ID.
     */
    suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot?

    /**
     * List all snapshots (history).
     */
    suspend fun listSnapshots(): List<ForgeSnapshot>

    /**
     * Restore workspace to a snapshot.
     */
    suspend fun restoreSnapshot(id: ForgeSnapshotId): ForgeSnapshot

    /**
     * Diff two snapshots.
     */
    suspend fun diffSnapshots(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff

    /**
     * Create a branch from a snapshot.
     */
    suspend fun branchSnapshot(base: ForgeSnapshotId, branchName: String): ForgeSnapshot

    /**
     * Merge a branch back.
     */
    suspend fun mergeBranch(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot

    // =========================================================================
    // Prompt Management
    // =========================================================================

    /**
     * Save a prompt template.
     */
    suspend fun savePrompt(prompt: ForgePrompt): ForgePrompt

    /**
     * Get a prompt by ID.
     */
    suspend fun getPrompt(id: ForgePromptId): ForgePrompt?

    /**
     * List all prompts.
     */
    suspend fun listPrompts(): List<ForgePrompt>

    /**
     * Search prompts by name, tags, or content.
     */
    suspend fun searchPrompts(query: String): List<ForgePrompt>

    /**
     * Delete a prompt.
     */
    suspend fun deletePrompt(id: ForgePromptId): Boolean

    // =========================================================================
    // Workflow Management
    // =========================================================================

    /**
     * Save a workflow definition.
     */
    suspend fun saveWorkflow(workflow: ForgeWorkflow): ForgeWorkflow

    /**
     * Get a workflow by ID.
     */
    suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow?

    /**
     * List all workflows.
     */
    suspend fun listWorkflows(): List<ForgeWorkflow>

    /**
     * Search workflows.
     */
    suspend fun searchWorkflows(query: String): List<ForgeWorkflow>

    /**
     * Delete a workflow.
     */
    suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean

    // =========================================================================
    // Execution Engine
    // =========================================================================

    /**
     * Execute a workflow with given inputs.
     * Returns a flow of step results for streaming progress.
     */
    fun executeWorkflow(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        agentConfigs: Map<AgentType, AgentConfig> = emptyMap(),
        snapshotId: ForgeSnapshotId? = null,
    ): Flow<ForgeExecutionStepProgress>

    /**
     * Execute a workflow and wait for completion.
     */
    suspend fun executeWorkflowSync(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        agentConfigs: Map<AgentType, AgentConfig> = emptyMap(),
        snapshotId: ForgeSnapshotId? = null,
    ): ForgeExecutionResult

    /**
     * Cancel a running workflow execution.
     */
    suspend fun cancelExecution(executionId: String): Boolean

    /**
     * Get execution history.
     */
    suspend fun listExecutions(workflowId: ForgeWorkflowId? = null): List<ForgeExecutionResult>

    // =========================================================================
    // Collaboration
    // =========================================================================

    /**
     * Subscribe to real-time collaboration events.
     */
    fun subscribeToCollaboration(): Flow<CollaborationEvent>

    /**
     * Emit a collaboration event (for other users to receive).
     */
    suspend fun emitCollaborationEvent(event: CollaborationEvent)

    /**
     * Get current active users.
     */
    suspend fun getActiveUsers(): List<ForgeUser>

    /**
     * Register user presence.
     */
    suspend fun registerUser(user: ForgeUser)

    /**
     * Unregister user presence.
     */
    suspend fun unregisterUser(userId: String)

    // =========================================================================
    // Artifacts / Sharing
    // =========================================================================

    /**
     * Create an artifact from execution results.
     */
    suspend fun createArtifact(
        name: String,
        description: String,
        files: List<ForgeFile>,
        workflowId: ForgeWorkflowId?,
        executionId: String?,
        isPublic: Boolean = false,
    ): ForgeArtifact

    /**
     * Get an artifact by ID.
     */
    suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact?

    /**
     * List artifacts.
     */
    suspend fun listArtifacts(publicOnly: Boolean = false): List<ForgeArtifact>

    /**
     * Export artifact as a portable bundle (zip, tar, etc.).
     */
    suspend fun exportArtifact(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle

    /**
     * Import artifact from a bundle.
     */
    suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact
}

/**
 * Progress update during workflow execution.
 */
sealed interface ForgeExecutionStepProgress {
    data class StepStarted(val stepId: String, val stepName: String) : ForgeExecutionStepProgress
    data class StepProgress(val stepId: String, val progress: String) : ForgeExecutionStepProgress
    data class StepCompleted(val stepId: String, val result: StepResult) : ForgeExecutionStepProgress
    data class StepFailed(val stepId: String, val error: String) : ForgeExecutionStepProgress
    data class WorkflowCompleted(val result: ForgeExecutionResult) : ForgeExecutionStepProgress
    data class WorkflowFailed(val error: String, val partialResults: List<StepResult>) : ForgeExecutionStepProgress
}

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
 * User in the collaboration session.
 */
@Serializable
data class ForgeUser(
    val id: String,
    val name: String,
    val color: String,  // for cursor/presence UI
    val connectedAt: Long = System.currentTimeMillis(),
)

/**
 * Export formats for artifacts.
 */
enum class ExportFormat {
    ZIP("zip"),
    TAR_GZ("tar.gz"),
    TAR("tar"),
    DIRECTORY("dir"),
    JSON("json");

    val extension: String
}

/**
 * Portable bundle for sharing artifacts.
 */
data class ForgeExportBundle(
    val format: ExportFormat,
    val data: ByteArray,
    val manifest: ForgeExportManifest,
)

@Serializable
data class ForgeExportManifest(
    val artifactId: ForgeArtifactId,
    val artifactName: String,
    val exportedAt: Long,
    val fileCount: Int,
    val totalSize: Long,
    val workflowId: ForgeWorkflowId?,
    val executionId: String?,
)