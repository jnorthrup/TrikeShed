package borg.trikeshed.forge.cursor

import borg.trikeshed.forge.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Hermes Kanban with fanout for real-time board updates.
 * 
 * Uses SharedFlow for event streaming:
 * - Emits KanbanEvent on task changes
 * - Subscribers receive live updates
 */
class HermesKanbanFanout(
    private val cursor: HermesKanbanCursor = HermesKanbanCursor(),
) : ForgeWorkspace {

    private val _files = mutableMapOf<ForgeFileId, ForgeFile>()
    private val _snapshots = mutableMapOf<ForgeSnapshotId, ForgeSnapshot>()
    private val _prompts = mutableMapOf<ForgePromptId, ForgePrompt>()
    private val _workflows = mutableMapOf<ForgeWorkflowId, ForgeWorkflow>()
    private val _cascades = mutableMapOf<CascadeId, OperationalCascade>()
    private val _patchBays = mutableMapOf<PatchBayId, PatchBay>()
    private val _patchBayModules = mutableMapOf<String, MutableMap<String, ModuleSpec>>()
    private val _patchBayCables = mutableMapOf<String, MutableList<PatchCable>>()
    
    // Kanban events flow
    private val _events = MutableSharedFlow<KanbanEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<KanbanEvent> = _events.asSharedFlow()
    
    // =========================================================================
    // File ops
    // =========================================================================
    
    override suspend fun put(file: ForgeFile): ForgeFile {
        _files[file.id] = file
        _events.emit(KanbanEvent.CardCreated(
            cardId = KanbanCardId(file.id.value),
            title = file.path,
        ))
        return file
    }
    
    override suspend fun get(id: ForgeFileId): ForgeFile? = _files[id]
    
    override suspend fun delete(id: ForgeFileId): Boolean {
        val result = _files.remove(id) != null
        if (result) {
            _events.emit(KanbanEvent.CardDeleted(KanbanCardId(id.value)))
        }
        return result
    }
    
    override suspend fun list(): Map<ForgeFileId, ForgeFile> = _files.toMap()
    
    override suspend fun search(query: String): List<ForgeFile> = 
        _files.values.filter { it.path.contains(query) || it.content.contains(query) }.toList()
    
    override fun stream(id: ForgeFileId): kotlinx.coroutines.channels.ReceiveChannel<String>? = null
    
    // =========================================================================
    // Snapshot ops
    // =========================================================================
    
    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot {
        val snap = ForgeSnapshot(
            ForgeSnapshotId.generate(),
            null,
            _files.toMap(),
            message,
            tags = tags,
        )
        _snapshots[snap.id] = snap
        return snap
    }
    
    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = _snapshots[id]
    
    override suspend fun history(): List<ForgeSnapshot> = 
        _snapshots.values.sortedByDescending { it.timestamp }.toList()
    
    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot = 
        _snapshots[id] ?: error("not found")
    
    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff = 
        ForgeDiff(emptyList(), emptyList(), emptyList(), emptyList())
    
    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot = snapshot("branch: $name")
    
    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot = snapshot(message)
    
    // =========================================================================
    // Prompt ops
    // =========================================================================
    
    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt {
        _prompts[prompt.id] = prompt
        return prompt
    }
    
    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = _prompts[id]
    
    override suspend fun listPrompts(): List<ForgePrompt> = _prompts.values.toList()
    
    override suspend fun searchPrompts(query: String): List<ForgePrompt> = 
        _prompts.values.filter { it.name.contains(query) || it.template.contains(query) }.toList()
    
    override suspend fun deletePrompt(id: ForgePromptId): Boolean = _prompts.remove(id) != null
    
    // =========================================================================
    // Workflow ops
    // =========================================================================
    
    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow {
        _workflows[workflow.id] = workflow
        return workflow
    }
    
    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = _workflows[id]
    
    override suspend fun listWorkflows(): List<ForgeWorkflow> = _workflows.values.toList()
    
    override suspend fun searchWorkflows(query: String): List<ForgeWorkflow> = 
        _workflows.values.filter { it.name.contains(query) }.toList()
    
    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = _workflows.remove(id) != null
    
    // =========================================================================
    // Execution
    // =========================================================================
    
    override fun execute(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?,
    ): Flow<StepProgress> = flowOf()
    
    override suspend fun executeSync(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?,
    ): ForgeExecutionResult = error("not impl")
    
    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = false
    
    override suspend fun executions(workflowId: ForgeWorkflowId?): List<ForgeExecutionResult> = emptyList()
    
    // =========================================================================
    // Collaboration
    // =========================================================================
    
    override fun events(): Flow<CollaborationEvent> = flowOf()
    
    override suspend fun emit(event: CollaborationEvent) = Unit
    
    override suspend fun users(): List<ForgeUser> = emptyList()
    
    override suspend fun join(user: ForgeUser) = Unit
    
    override suspend fun leave(userId: ForgeUserId) = Unit
    
    // =========================================================================
    // Cascades
    // =========================================================================
    
    override suspend fun putCascade(cascade: OperationalCascade): OperationalCascade {
        _cascades[cascade.id] = cascade
        return cascade
    }
    
    override suspend fun getCascade(id: CascadeId): OperationalCascade? = _cascades[id]
    
    override suspend fun listCascades(): List<OperationalCascade> = _cascades.values.toList()
    
    override suspend fun deleteCascade(id: CascadeId): Boolean = _cascades.remove(id) != null
    
    override suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult = 
        CascadeDetectionResult(emptyList(), emptyList(), emptyList(), 0.0)
    
    override fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): Flow<CascadeProgress> = flowOf()
    
    override suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): CascadeExecutionResult = 
        error("not impl")
    
    override suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph? = null
    
    // =========================================================================
    // Artifacts
    // =========================================================================
    
    override suspend fun artifact(
        name: String,
        description: String,
        files: List<ForgeFile>,
        workflowId: ForgeWorkflowId?,
        executionId: ForgeExecutionId?,
        isPublic: Boolean,
    ): ForgeArtifact = ForgeArtifact(
        ForgeArtifactId.generate(),
        name,
        description,
        files,
        workflowId,
        executionId,
    )
    
    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = null
    
    override suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact> = emptyList()
    
    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle = 
        error("not impl")
    
    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact = error("not impl")
    
    // =========================================================================
    // Fanout events
    // =========================================================================
    
    /**
     * Complete task → emit event.
     */
    suspend fun complete(cardId: KanbanCardId, summary: String, metadata: Map<String, String> = emptyMap()) {
        _events.emit(KanbanEvent.CardCompleted(cardId, summary, metadata))
    }
    
    /**
     * Block task → emit event.
     */
    suspend fun block(cardId: KanbanCardId, reason: String) {
        _events.emit(KanbanEvent.CardBlocked(cardId, reason))
    }
    
    /**
     * Unblock task → emit event.
     */
    suspend fun unblock(cardId: KanbanCardId, comment: String? = null) {
        _events.emit(KanbanEvent.CardUnblocked(cardId, comment))
    }
    
    /**
     * Move card between columns → emit event.
     */
    suspend fun moveCard(cardId: KanbanCardId, fromColumn: KanbanColumnId, toColumn: KanbanColumnId) {
        _events.emit(KanbanEvent.CardMoved(cardId, fromColumn, toColumn))
    }
    
    /**
     * Add dependency → emit event.
     */
    suspend fun addDependency(blockerId: KanbanCardId, blockedId: KanbanCardId) {
        _events.emit(KanbanEvent.DependencyAdded(blockerId, blockedId))
    }
    
    /**
     * Remove dependency → emit event.
     */
    suspend fun removeDependency(blockerId: KanbanCardId, blockedId: KanbanCardId) {
        _events.emit(KanbanEvent.DependencyRemoved(blockerId, blockedId))
    }
    
    /**
     * Comment added → emit event.
     */
    suspend fun comment(cardId: KanbanCardId, author: String, body: String) {
        _events.emit(KanbanEvent.CommentAdded(cardId, author, body))
    }
    
    /**
     * Run started → emit event.
     */
    suspend fun runStarted(cardId: KanbanCardId, profile: String) {
        _events.emit(KanbanEvent.RunStarted(cardId, profile))
    }
    
    /**
     * Run completed → emit event.
     */
    suspend fun runCompleted(cardId: KanbanCardId, outcome: String, summary: String?) {
        _events.emit(KanbanEvent.RunCompleted(cardId, outcome, summary))
    }
    
    // =========================================================================
    // Rendering
    // =========================================================================
    
    /**
     * Render board as Mermaid diagram.
     */
    fun mermaid(board: KanbanBoard): String = cursor.renderMermaid(board)
    
    /**
     * Render board as Graphviz DOT.
     */
    fun dot(board: KanbanBoard): String = cursor.renderDot(board)
    
    // =========================================================================
    // Patch Bay / Cable Operations (Real-time Signal Routing)
    // =========================================================================
    
    override suspend fun putPatchBay(patchBay: PatchBay): PatchBay {
        _patchBays[patchBay.id] = patchBay
        _patchBayModules[patchBay.id.value] = mutableMapOf()
        _patchBayCables[patchBay.id.value] = mutableListOf()
        return patchBay
    }
    
    override suspend fun getPatchBay(id: PatchBayId): PatchBay? = _patchBays[id]
    
    override suspend fun listPatchBays(): List<PatchBay> = _patchBays.values.toList()
    
    override suspend fun deletePatchBay(id: PatchBayId): Boolean {
        _patchBayModules.remove(id.value)
        _patchBayCables.remove(id.value)
        return _patchBays.remove(id) != null
    }
    
    override suspend fun putModule(patchBayId: PatchBayId, module: ModuleSpec): ModuleSpec {
        val modules = _patchBayModules.getOrPut(patchBayId.value) { mutableMapOf() }
        modules[module.id] = module
        return module
    }
    
    override suspend fun getModule(patchBayId: PatchBayId, moduleId: String): ModuleSpec? {
        return _patchBayModules[patchBayId.value]?.get(moduleId)
    }
    
    override suspend fun deleteModule(patchBayId: PatchBayId, moduleId: String): Boolean {
        return _patchBayModules[patchBayId.value]?.remove(moduleId) != null
    }
    
    override suspend fun connectCable(patchBayId: PatchBayId, cable: PatchCable): PatchCable {
        val cables = _patchBayCables.getOrPut(patchBayId.value) { mutableListOf() }
        cables.add(cable)
        return cable
    }
    
    override suspend fun disconnectCable(patchBayId: PatchBayId, cableId: CableId): Boolean {
        return _patchBayCables[patchBayId.value]?.removeIf { it.id == cableId } ?: false
    }
    
    override suspend fun setCableState(patchBayId: PatchBayId, cableId: CableId, state: CableState): PatchCable? {
        val cables = _patchBayCables[patchBayId.value] ?: return null
        val index = cables.indexOfFirst { it.id == cableId }
        return if (index >= 0) {
            val updated = cables[index].copy(state = state)
            cables[index] = updated
            updated
        } else null
    }
    
    override suspend fun setCableTransform(patchBayId: PatchBayId, cableId: CableId, transform: CableTransform?): PatchCable? {
        val cables = _patchBayCables[patchBayId.value] ?: return null
        val index = cables.indexOfFirst { it.id == cableId }
        return if (index >= 0) {
            val updated = cables[index].copy(transform = transform)
            cables[index] = updated
            updated
        } else null
    }
    
    override suspend fun processPatchBay(patchBayId: PatchBayId, inputs: Map<String, String>, frameCount: Int): Map<String, String> {
        // Simplified: just pass through inputs
        return inputs
    }
    
    override fun streamPatchBay(patchBayId: PatchBayId, outputPort: PortAddress): Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap<String, String>())
    
    override suspend fun getPatchBayGraph(patchBayId: PatchBayId): PatchBayGraph? {
        val modules = _patchBayModules[patchBayId.value] ?: return null
        val cables = _patchBayCables[patchBayId.value] ?: return null
        val nodes = modules.values.map { module ->
            PatchBayNode(
                id = module.id,
                moduleType = module.moduleType,
                label = module.id,
                position = module.position,
                inputPorts = module.inputPorts,
                outputPorts = module.outputPorts,
            )
        }
        val edges = cables.map { cable ->
            PatchBayEdge(
                id = cable.id,
                source = cable.source,
                destination = cable.destination,
                state = cable.state,
                transform = cable.transform,
            )
        }
        return PatchBayGraph(patchBayId, nodes, edges)
    }
    
    override suspend fun autoLayout(patchBayId: PatchBayId, algorithm: LayoutAlgorithm): PatchBay {
        return _patchBays[patchBayId] ?: PatchBay(patchBayId, "", emptyMap(), emptyList())
    }
    
    override suspend fun createModuleFromStep(patchBayId: PatchBayId, step: WorkflowStep, position: ModulePosition): ModuleSpec {
        val module = step.toModuleSpec(position)
        return putModule(patchBayId, module)
    }
    
    override suspend fun createModuleFromCascade(patchBayId: PatchBayId, cascade: OperationalCascade, position: ModulePosition): ModuleSpec {
        val module = ModuleSpec(
            id = cascade.id.value,
            moduleType = ModuleType.CASCADE_EXECUTION,
            inputPorts = cascade.keyHierarchy.map { key ->
                PortSpec(key, PortType.DATA, PortDirection.INPUT, "String")
            },
            outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "CascadeOutputRow")),
            position = position,
        )
        return putModule(patchBayId, module)
    }
}

/**
 * Kanban events for fanout.
 */
sealed interface KanbanEvent {
    data class CardCreated(val cardId: KanbanCardId, val title: String) : KanbanEvent
    data class CardDeleted(val cardId: KanbanCardId) : KanbanEvent
    data class CardCompleted(val cardId: KanbanCardId, val summary: String, val metadata: Map<String, String>) : KanbanEvent
    data class CardBlocked(val cardId: KanbanCardId, val reason: String) : KanbanEvent
    data class CardUnblocked(val cardId: KanbanCardId, val comment: String?) : KanbanEvent
    data class CardMoved(val cardId: KanbanCardId, val fromColumn: KanbanColumnId, val toColumn: KanbanColumnId) : KanbanEvent
    data class DependencyAdded(val blockerId: KanbanCardId, val blockedId: KanbanCardId) : KanbanEvent
    data class DependencyRemoved(val blockerId: KanbanCardId, val blockedId: KanbanCardId) : KanbanEvent
    data class CommentAdded(val cardId: KanbanCardId, val author: String, val body: String) : KanbanEvent
    data class RunStarted(val cardId: KanbanCardId, val profile: String) : KanbanEvent
    data class RunCompleted(val cardId: KanbanCardId, val outcome: String, val summary: String?) : KanbanEvent
}

/**
 * Open Hermes kanban with fanout.
 */
fun hermesKanbanFanout(): HermesKanbanFanout = HermesKanbanFanout()
