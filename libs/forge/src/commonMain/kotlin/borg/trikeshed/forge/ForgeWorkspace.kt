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
    // Cascade Operations
    // =========================================================================

    suspend fun putCascade(cascade: OperationalCascade): OperationalCascade
    suspend fun getCascade(id: CascadeId): OperationalCascade?
    suspend fun listCascades(): List<OperationalCascade>
    suspend fun deleteCascade(id: CascadeId): Boolean

    /** Detect operational cascades from source data */
    suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult

    /** Execute a cascade and return results */
    fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId? = null): Flow<CascadeProgress>
    suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId? = null): CascadeExecutionResult

    /** Get cascade graph for visualization */
    suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph?

    // =========================================================================
    // Patch Bay / Cable Operations (Real-time Signal Routing)
    // =========================================================================

    suspend fun putPatchBay(patchBay: PatchBay): PatchBay
    suspend fun getPatchBay(id: PatchBayId): PatchBay?
    suspend fun listPatchBays(): List<PatchBay>
    suspend fun deletePatchBay(id: PatchBayId): Boolean

    /** Add or update a module in the patch bay */
    suspend fun putModule(patchBayId: PatchBayId, module: ModuleSpec): ModuleSpec
    suspend fun getModule(patchBayId: PatchBayId, moduleId: String): ModuleSpec?
    suspend fun deleteModule(patchBayId: PatchBayId, moduleId: String): Boolean

    /** Cable operations for real-time reconfiguration */
    suspend fun connectCable(patchBayId: PatchBayId, cable: PatchCable): PatchCable
    suspend fun disconnectCable(patchBayId: PatchBayId, cableId: CableId): Boolean
    suspend fun setCableState(patchBayId: PatchBayId, cableId: CableId, state: CableState): PatchCable?
    suspend fun setCableTransform(patchBayId: PatchBayId, cableId: CableId, transform: CableTransform?): PatchCable?

    /** Real-time signal processing: process one frame/block through the patch bay */
    suspend fun processPatchBay(patchBayId: PatchBayId, inputs: Map<String, String>, frameCount: Int): Map<String, String>

    /** Stream real-time output from a patch bay (for audio/video/CV streams) */
    fun streamPatchBay(patchBayId: PatchBayId, outputPort: PortAddress): Flow<Map<String, String>>

    /** Visual/graph operations */
    suspend fun getPatchBayGraph(patchBayId: PatchBayId): PatchBayGraph?
    suspend fun autoLayout(patchBayId: PatchBayId, algorithm: LayoutAlgorithm): PatchBay

    /** Module factory: create standard modules from workflow steps */
    suspend fun createModuleFromStep(patchBayId: PatchBayId, step: WorkflowStep, position: ModulePosition): ModuleSpec
    suspend fun createModuleFromCascade(patchBayId: PatchBayId, cascade: OperationalCascade, position: ModulePosition): ModuleSpec

    // =========================================================================
    // Artifact / Sharing Operations
    // =========================================================================

    suspend fun artifact(
        name: String,
        description: String,
        files: List<ForgeFile>,
        workflowId: ForgeWorkflowId?,
        executionId: ForgeExecutionId?,
        isPublic: Boolean
    ): ForgeArtifact

    suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact?
    suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact>
    suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle
    suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact
}