package borg.trikeshed.forge

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Main workspace interface for Forge.
 * Manages files, snapshots, prompts, workflows, and executions.
 */
interface ForgeWorkspace {

    // =========================================================================
    // File Management
    // =========================================================================

    suspend fun put(file: ForgeFile): ForgeFile
    suspend fun get(id: ForgeFileId): ForgeFile?
    suspend fun delete(id: ForgeFileId): Boolean
    suspend fun list(): Map<ForgeFileId, ForgeFile>
    suspend fun search(query: String): List<ForgeFile>
    fun stream(id: ForgeFileId): ReceiveChannel<String>?

    // =========================================================================
    // Snapshot / Version Control
    // =========================================================================

    suspend fun snapshot(message: String, tags: Set<String> = emptySet()): ForgeSnapshot
    suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot?
    suspend fun history(): List<ForgeSnapshot>
    suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot
    suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff
    suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot
    suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot

    // =========================================================================
    // Prompt Management
    // =========================================================================

    suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt
    suspend fun getPrompt(id: ForgePromptId): ForgePrompt?
    suspend fun listPrompts(): List<ForgePrompt>
    suspend fun searchPrompts(query: String): List<ForgePrompt>
    suspend fun deletePrompt(id: ForgePromptId): Boolean

    // =========================================================================
    // Workflow Management
    // =========================================================================

    suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow
    suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow?
    suspend fun listWorkflows(): List<ForgeWorkflow>
    suspend fun searchWorkflows(query: String): List<ForgeWorkflow>
    suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean

    // =========================================================================
    // Execution Engine
    // =========================================================================

    fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig> = emptyMap(), snapshotId: ForgeSnapshotId? = null): Flow<StepProgress>
    suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig> = emptyMap(), snapshotId: ForgeSnapshotId? = null): ForgeExecutionResult
    suspend fun cancel(executionId: ForgeExecutionId): Boolean
    suspend fun executions(workflowId: ForgeWorkflowId? = null): List<ForgeExecutionResult>

    // =========================================================================
    // Collaboration
    // =========================================================================

    fun events(): Flow<CollaborationEvent>
    suspend fun emit(event: CollaborationEvent)
    suspend fun users(): List<ForgeUser>
    suspend fun join(user: ForgeUser)
    suspend fun leave(userId: ForgeUserId)

    // =========================================================================
    // Artifacts / Sharing
    // =========================================================================

    suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean = false): ForgeArtifact
    suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact?
    suspend fun listArtifacts(publicOnly: Boolean = false): List<ForgeArtifact>
    suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle
    suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact
}