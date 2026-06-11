package borg.trikeshed.forge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * In-memory implementation of ForgeWorkspace for testing.
 */
class ForgeWorkspaceImpl : ForgeWorkspace {

    private val files = mutableMapOf<ForgeFileId, ForgeFile>()
    private val snapshots = mutableListOf<ForgeSnapshot>()
    private val prompts = mutableMapOf<ForgePromptId, ForgePrompt>()
    private val workflows = mutableMapOf<ForgeWorkflowId, ForgeWorkflow>()
    private val executions = mutableListOf<ForgeExecutionResult>()
    private val eventsFlow = MutableSharedFlow<CollaborationEvent>(extraBufferCapacity = 100)
    private val activeUsers = mutableMapOf<ForgeUserId, ForgeUser>()
    private val artifacts = mutableMapOf<ForgeArtifactId, ForgeArtifact>()

    // =========================================================================
    // File Management
    // =========================================================================

    override suspend fun put(file: ForgeFile): ForgeFile {
        val updated = file.copy(updatedAt = System.currentTimeMillis())
        files[updated.id] = updated
        return updated
    }

    override suspend fun get(id: ForgeFileId): ForgeFile? = files[id]

    override suspend fun delete(id: ForgeFileId): Boolean = files.remove(id) != null

    override suspend fun list(): Map<ForgeFileId, ForgeFile> = files.toMap()

    override suspend fun search(query: String): List<ForgeFile> =
        files.values.filter { it.content.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }.toList()

    override fun stream(id: ForgeFileId): ReceiveChannel<String>? {
        val file = files[id] ?: return null
        val channel = Channel<String>(1)
        // Use a scope to send content
        CoroutineScope(Dispatchers.IO).launch {
            channel.send(file.content)
            channel.close()
        }
        return channel
    }

    // =========================================================================
    // Snapshot / Version Control
    // =========================================================================

    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot = ForgeSnapshot(
        id = ForgeSnapshotId.generate(),
        parentId = snapshots.lastOrNull()?.id,
        files = files.toMap(),
        message = message,
        tags = tags,
        author = "test-user"
    ).also { snapshots.add(it) }

    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? = snapshots.find { it.id == id }

    override suspend fun history(): List<ForgeSnapshot> = snapshots.toList()

    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot {
        val target = snapshots.find { it.id == id } ?: throw IllegalArgumentException("Snapshot not found: $id")
        files.clear()
        files.putAll(target.files)
        return target
    }

    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff {
        val fromSnap = snapshots.find { it.id == from } ?: throw IllegalArgumentException("From snapshot not found: $from")
        val toSnap = snapshots.find { it.id == to } ?: throw IllegalArgumentException("To snapshot not found: $to")

        val fromFiles = fromSnap.files
        val toFiles = toSnap.files

        val added = toFiles.keys.filterNot(fromFiles::containsKey).map { toFiles[it]!! }.toList()
        val removed = fromFiles.keys.filterNot(toFiles::containsKey).map { it }.toList()
        val modified = toFiles.entries.filter { (k, v) ->
            fromFiles[k]?.content != v.content && fromFiles.containsKey(k) && toFiles.containsKey(k)
        }.map { it.value }.toList()
        val unchanged = toFiles.keys.filter { fromFiles[it]?.content == toFiles[it]?.content }.map { it }.toList()

        return ForgeDiff(
            addedFiles = added,
            removedFiles = removed,
            modifiedFiles = modified,
            unchangedFiles = unchanged
        )
    }

    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot {
        val baseSnap = snapshots.find { it.id == base } ?: throw IllegalArgumentException("Base snapshot not found: $base")
        return ForgeSnapshot(
            id = ForgeSnapshotId.generate(),
            parentId = baseSnap.id,
            files = baseSnap.files.toMutableMap(),
            message = "branch: $name",
            tags = setOf(name),
            author = "test-user"
        ).also { snapshots.add(it) }
    }

    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot {
        val sourceSnap = snapshots.find { it.id == source } ?: throw IllegalArgumentException("Source snapshot not found: $source")
        val targetSnap = snapshots.find { it.id == target } ?: throw IllegalArgumentException("Target snapshot not found: $target")

        // Simple merge: source wins on conflicts
        val mergedFiles = targetSnap.files.toMutableMap()
        mergedFiles.putAll(sourceSnap.files)

        return ForgeSnapshot(
            id = ForgeSnapshotId.generate(),
            parentId = targetSnap.id,
            files = mergedFiles,
            message = message,
            tags = setOf("merge"),
            author = "test-user"
        ).also { snapshots.add(it) }
    }

    // =========================================================================
    // Prompt Management
    // =========================================================================

    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt {
        val updated = prompt.copy()
        prompts[updated.id] = updated
        return updated
    }

    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = prompts[id]

    override suspend fun listPrompts(): List<ForgePrompt> = prompts.values.toList()

    override suspend fun searchPrompts(query: String): List<ForgePrompt> =
        prompts.values.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.template.contains(query, ignoreCase = true) ||
            it.tags.any { it.contains(query, ignoreCase = true) }
        }.toList()

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
        workflows.values.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.tags.any { it.contains(query, ignoreCase = true) }
        }.toList()

    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = workflows.remove(id) != null

    // =========================================================================
    // Execution Engine
    // =========================================================================

    override fun execute(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?
    ): kotlinx.coroutines.flow.Flow<StepProgress> {
        val workflow = workflows[workflowId] ?: throw IllegalArgumentException("Workflow not found: $workflowId")
        return flowOf(StepProgress.StepStarted(workflowId.value, workflow.name))
    }

    override suspend fun executeSync(
        workflowId: ForgeWorkflowId,
        inputs: Map<String, String>,
        configs: Map<AgentType, AgentConfig>,
        snapshotId: ForgeSnapshotId?
    ): ForgeExecutionResult {
        val workflow = workflows[workflowId] ?: throw IllegalArgumentException("Workflow not found: $workflowId")
        val snapId = snapshotId ?: snapshots.last()?.id ?: ForgeSnapshotId.ROOT
        val now = System.currentTimeMillis()

        val result = ForgeExecutionResult(
            executionId = ForgeExecutionId.generate(),
            workflowId = workflowId,
            snapshotId = snapId,
            stepResults = emptyList(),
            finalOutputs = inputs,
            artifacts = emptyList(),
            startedAt = now,
            completedAt = now,
            status = ExecutionStatus.SUCCESS
        )
        executions.add(result)
        return result
    }

    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = true

    override suspend fun executions(workflowId: ForgeWorkflowId?): List<ForgeExecutionResult> =
        if (workflowId == null) executions.toList()
        else executions.filter { it.workflowId == workflowId }.toList()

    // =========================================================================
    // Collaboration
    // =========================================================================

    override fun events(): kotlinx.coroutines.flow.Flow<CollaborationEvent> = eventsFlow

    override suspend fun emit(event: CollaborationEvent) {
        eventsFlow.tryEmit(event)
    }

    override suspend fun users(): List<ForgeUser> = activeUsers.values.toList()

    override suspend fun join(user: ForgeUser) {
        activeUsers[user.id] = user
        emit(CollaborationEvent.UserJoined(user.id, user.name))
    }

    override suspend fun leave(userId: ForgeUserId) {
        activeUsers.remove(userId)
        emit(CollaborationEvent.UserLeft(userId))
    }

    // =========================================================================
    // Artifacts / Sharing
    // =========================================================================

    override suspend fun artifact(
        name: String,
        description: String,
        files: List<ForgeFile>,
        workflowId: ForgeWorkflowId?,
        executionId: ForgeExecutionId?,
        isPublic: Boolean
    ): ForgeArtifact {
        val artifact = ForgeArtifact(
            id = ForgeArtifactId.generate(),
            name = name,
            description = description,
            files = files,
            workflowId = workflowId,
            executionId = executionId,
            isPublic = isPublic
        )
        artifacts[artifact.id] = artifact
        emit(CollaborationEvent.SnapshotCreated(
            ForgeSnapshot(
                id = ForgeSnapshotId.generate(),
                parentId = null,
                files = this.files.entries.associateBy({ it.key }, { it.value }),
                message = "artifact: $name"
            ),
            ForgeUserId("system")
        ))
        return artifact
    }

    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = artifacts[id]

    override suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact> =
        artifacts.values.filter { !publicOnly || it.isPublic }.toList()

    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle {
        val artifact = artifacts[id] ?: throw IllegalArgumentException("Artifact not found: $id")
        val manifest = ForgeExportManifest(
            artifactId = artifact.id,
            artifactName = artifact.name,
            exportedAt = System.currentTimeMillis(),
            fileCount = artifact.files.size,
            totalSize = artifact.files.sumOf { it.content.length.toLong() },
            workflowId = artifact.workflowId,
            executionId = artifact.executionId
        )
        val json = Json.encodeToString(artifact)
        return ForgeExportBundle(
            format = format,
            data = json.toByteArray(),
            manifest = manifest
        )
    }

    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact {
        val artifact = Json.decodeFromString<ForgeArtifact>(String(bundle.data))
        artifacts[artifact.id] = artifact
        return artifact
    }
}