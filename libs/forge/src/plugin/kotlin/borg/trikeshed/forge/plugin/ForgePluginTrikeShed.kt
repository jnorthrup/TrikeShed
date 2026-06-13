package borg.trikeshed.forge.plugin

import borg.trikeshed.forge.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * TrikeShed-backed Forge workspace plugin.
 * 
 * Bridges ForgeWorkspace to TrikeShed kernel algebra:
 * - Cursor = Series<RowVec> (columnar data)
 * - Confix = confix type storage (zstorage)
 * - FieldSynapse = 24B wireproto for JS transitions
 */
class ForgePluginTrikeShed(
    private val store: TrikeShedStore,
) : ForgeWorkspace {

    private val files = mutableMapOf<ForgeFileId, ForgeFile>()
    private val snapshots = mutableMapOf<ForgeSnapshotId, ForgeSnapshot>()
    private val prompts = mutableMapOf<ForgePromptId, ForgePrompt>()
    private val workflows = mutableMapOf<ForgeWorkflowId, ForgeWorkflow>()
    private val cascades = mutableMapOf<CascadeId, OperationalCascade>()

    /** TrikeShed storage backend interface. */
    interface TrikeShedStore {
        suspend fun put(key: String, value: String): String?
        suspend fun get(key: String): String?
        suspend fun remove(key: String): Boolean
        suspend fun scan(prefix: String): List<String>
    }

    // =========================================================================
    // File Management
    // =========================================================================

    override suspend fun put(file: ForgeFile): ForgeFile {
        files[file.id] = file
        store.put("file:${file.id.value}", kotlinx.serialization.json.Json.encodeToString(ForgeFile.serializer(), file))
        return file
    }

    override suspend fun get(id: ForgeFileId): ForgeFile? = files[id]

    override suspend fun delete(id: ForgeFileId): Boolean = files.remove(id) != null

    override suspend fun list(): Map<ForgeFileId, ForgeFile> = files.toMap()

    override suspend fun search(query: String): List<ForgeFile> =
        files.values.filter { it.path.contains(query) || it.content.contains(query) }

    override fun stream(id: ForgeFileId): ReceiveChannel<String>? = null

    // =========================================================================
    // Snapshot / Version Control
    // =========================================================================

    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot {
        val snap = ForgeSnapshot(
            id = ForgeSnapshotId.generate(),
            parentId = snapshots.values.maxByOrNull { it.timestamp }?.id,
            files = files.toMap(),
            message = message,
            tags = tags,
        )
        snapshots[snap.id] = snap
        return snap
    }

    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = snapshots[id]

    override suspend fun history(): List<ForgeSnapshot> = snapshots.values.sortedByDescending { it.timestamp }

    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot {
        val snap = snapshots[id] ?: error("Snapshot $id not found")
        files.clear()
        files.putAll(snap.files)
        return snap
    }

    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff {
        val s1 = snapshots[from] ?: error("Snapshot $from not found")
        val s2 = snapshots[to] ?: error("Snapshot $to not found")
        
        val allIds = (s1.files.keys + s2.files.keys)
        val added = s2.files.keys - s1.files.keys
        val removed = s1.files.keys - s2.files.keys
        val modified = allIds.filter { id -> s1.files[id]?.content != s2.files[id]?.content }
        
        return ForgeDiff(
            addedFiles = added.mapNotNull { s2.files[it] },
            removedFiles = removed.toList(),
            modifiedFiles = modified.mapNotNull { s2.files[it] },
            unchangedFiles = (allIds - added - removed - modified.toSet()).toList(),
        )
    }

    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot {
        val baseSnap = snapshots[base] ?: error("Snapshot $base not found")
        return snapshot("branch: $name", setOf(name))
    }

    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot {
        val s1 = snapshots[source] ?: error("Snapshot $source not found")
        val s2 = snapshots[target] ?: error("Snapshot $target not found")
        
        // Simple merge: take newer versions
        val merged = s1.files.toMutableMap()
        for ((id, file) in s2.files) {
            val existing = merged[id]
            if (existing == null || file.updatedAt > existing.updatedAt) {
                merged[id] = file
            }
        }
        
        val result = ForgeSnapshot(
            id = ForgeSnapshotId.generate(),
            parentId = target,
            files = merged,
            message = message,
        )
        snapshots[result.id] = result
        return result
    }

    // =========================================================================
    // Prompt Management
    // =========================================================================

    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt {
        prompts[prompt.id] = prompt
        return prompt
    }

    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = prompts[id]

    override suspend fun listPrompts(): List<ForgePrompt> = prompts.values.toList()

    override suspend fun searchPrompts(query: String): List<ForgePrompt> =
        prompts.values.filter { it.name.contains(query) || it.template.contains(query) }

    override suspend fun deletePrompt(id: ForgePromptId): Boolean = prompts.remove(id) != null

    // =========================================================================
    // Workflow Management
    // =========================================================================

    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow {
        workflows[workflow.id] = workflow
        return workflow
    }

    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = workflows[id]

    override suspend fun listWorkflows(): List<ForgeWorkflow> = workflows.values.toList()

    override suspend fun searchWorkflows(query: String): List<ForgeWorkflow> =
        workflows.values.filter { it.name.contains(query) }

    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = workflows.remove(id) != null

    // =========================================================================
    // Execution Engine
    // =========================================================================

    override fun execute(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?,
    ): Flow<StepProgress> {
        error("Not implemented - use executeSync")
    }

    override suspend fun executeSync(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?,
    ): ForgeExecutionResult {
        val workflow = workflows[workflowId] ?: error("Workflow $workflowId not found")
        error("Execution not implemented - requires agent runtime")
    }

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
    // Cascade Operations
    // =========================================================================

    override suspend fun putCascade(cascade: OperationalCascade): OperationalCascade {
        cascades[cascade.id] = cascade
        return cascade
    }

    override suspend fun getCascade(id: CascadeId): OperationalCascade? = cascades[id]

    override suspend fun listCascades(): List<OperationalCascade> = cascades.values.toList()

    override suspend fun deleteCascade(id: CascadeId): Boolean = cascades.remove(id) != null

    override suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult =
        CascadeDetectionResult(emptyList(), emptyList(), emptyList(), 0.0)

    override fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): Flow<CascadeProgress> =
        flowOf()

    override suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): CascadeExecutionResult =
        error("Not implemented")

    override suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph? = null

    // =========================================================================
    // Artifacts / Sharing
    // =========================================================================

    override suspend fun artifact(
        name: String,
        description: String,
        files: List<ForgeFile>,
        workflowId: ForgeWorkflowId?,
        executionId: ForgeExecutionId?,
        isPublic: Boolean,
    ): ForgeArtifact = error("Not implemented")

    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = null

    override suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact> = emptyList()

    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle =
        error("Not implemented")

    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact = error("Not implemented")

    private fun <T> flowOf(): Flow<T> = kotlinx.coroutines.flow.flowOf()
}

/**
 * In-memory TrikeShed store stub.
 */
class InMemoryTrikeShedStore : ForgePluginTrikeShed.TrikeShedStore {
    private val map = mutableMapOf<String, String>()
    
    override suspend fun put(key: String, value: String): String? = map.put(key, value)
    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String): Boolean = map.remove(key) != null
    override suspend fun scan(prefix: String): List<String> =
        map.keys.filter { it.startsWith(prefix) }.mapNotNull { map[it] }
}

/**
 * Factory to create TrikeShed-backed Forge workspace.
 */
fun forgeWorkspaceTrikeShed(store: ForgePluginTrikeShed.TrikeShedStore = InMemoryTrikeShedStore()): ForgeWorkspace =
    ForgePluginTrikeShed(store)