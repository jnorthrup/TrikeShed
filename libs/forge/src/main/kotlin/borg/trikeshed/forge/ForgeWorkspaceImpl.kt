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
import kotlin.math.min

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

    // =========================================================================
    // Cascade Operations
    // =========================================================================

    private val cascades = mutableMapOf<CascadeId, OperationalCascade>()

    override suspend fun putCascade(cascade: OperationalCascade): OperationalCascade {
        cascades[cascade.id] = cascade
        return cascade
    }

    override suspend fun getCascade(id: CascadeId): OperationalCascade? = cascades[id]

    override suspend fun listCascades(): List<OperationalCascade> = cascades.values.toList()

    override suspend fun deleteCascade(id: CascadeId): Boolean = cascades.remove(id) != null

    override suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult {
        // Simple detection: infer hierarchy from candidate keys, create map/reduce/rereduce stages
        val source = request.sources.firstOrNull()
            ?: return CascadeDetectionResult(emptyList(), emptyList(), emptyList(), 0.0)

        // Get sample data to infer structure
        val sampleData = parseSampleData(source)
        val inferredKeys = request.candidateKeys.filter { key ->
            sampleData.any { it.containsKey(key) }
        }
        val inferredMetrics = request.candidateMetrics.filter { metric ->
            sampleData.any { it.containsKey(metric) }
        }

        val keyHierarchy = inferredKeys.take(request.maxHierarchyDepth)
        val confidence = if (keyHierarchy.isNotEmpty() && inferredMetrics.isNotEmpty()) 0.9 else 0.3

        val stages = buildCascadeStages(keyHierarchy, inferredMetrics)

        val cascade = OperationalCascade(
            id = CascadeId.generate(),
            name = "detected-${source.hashCode()}",
            sources = request.sources,
            stages = stages,
            keyHierarchy = keyHierarchy,
            metadata = mapOf("confidence" to confidence.toString())
        )

        val hierarchies = if (keyHierarchy.isNotEmpty()) listOf(keyHierarchy) else emptyList()

        return CascadeDetectionResult(
            detectedCascades = listOf(cascade),
            inferredKeyHierarchies = hierarchies,
            inferredMetrics = inferredMetrics,
            confidence = confidence
        )
    }

    private fun parseSampleData(source: CascadeSource): List<Map<String, Any>> = when (source) {
        is CascadeSource.FileSource -> {
            val file = files[source.fileId] ?: return emptyList()
            parseJsonLines(file.content)
        }
        is CascadeSource.InlineData -> parseJsonLines(source.data)
        else -> emptyList()
    }

    private fun parseJsonLines(content: String): List<Map<String, Any>> {
        val lines = content.lines().filter { it.isNotBlank() }
        return lines.map { line ->
            try {
                Json.decodeFromString<Map<String, Any>>(line)
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }
        }.filter { it.isNotEmpty() }.take(10)  // Sample first 10
    }

    private fun buildCascadeStages(keys: List<String>, metrics: List<String>): List<CascadeStage> {
        val mapJs = """
            function(doc) {
                var key = [
                    ${keys.map { "doc.$it" }.joinToString(", ")}
                ];
                var value = {
                    ${metrics.map { "\"$it\": doc.$it" }.joinToString(", ")}
                };
                emit(key, value);
            }
        """.trimIndent()

        val reduceJs = if (metrics.size == 1) {
            // Builtin SUM for single metric
            """{"builtin": "SUM"}"""
        } else {
            // Multi-metric reduce
            """
            function(key, values, rereduce) {
                var result = {};
                ${metrics.map { m ->
                    """
                    result.$m = { sum: 0, count: 0, min: Infinity, max: -Infinity };
                    values.forEach(function(v) {
                        var val = rereduce ? v.$m.sum : v.$m;
                        result.$m.sum += val;
                        result.$m.count += rereduce ? v.$m.count : 1;
                        result.$m.min = Math.min(result.$m.min, val);
                        result.$m.max = Math.max(result.$m.max, val);
                    });
                    result.$m.avg = result.$m.sum / result.$m.count;
                    """.trimIndent()
                }.joinToString("\n")}
                return result;
            }
            """.trimIndent()
        }

        return listOf(
            CascadeStage.MapStage(
                id = "map",
                transform = MapTransform.JsFunction(mapJs)
            ),
            CascadeStage.ReduceStage(
                id = "reduce",
                reduceFn = if (metrics.size == 1)
                    ReduceTransform.Builtin(BuiltinReduce.SUM)
                else
                    ReduceTransform.JsFunction(reduceJs)
            ),
            CascadeStage.RereduceStage(
                id = "rereduce",
                rereduceFn = ReduceTransform.JsFunction(reduceJs)
            )
        )
    }

    override fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): kotlinx.coroutines.flow.Flow<CascadeProgress> {
        return kotlinx.coroutines.flow.flow {
            val cascade = cascades[cascadeId] ?: throw IllegalArgumentException("Cascade not found: $cascadeId")

            // Emit stage started events
            for (stage in cascade.stages) {
                emit(CascadeProgress.StageStarted(stage.id, stage.id))
            }

            // Execute cascade (simplified - in real impl would use GraalVM ViewServer)
            val result = executeCascadeInternal(cascade, snapshotId)

            for (stage in cascade.stages) {
                emit(CascadeProgress.StageCompleted(stage.id, result.output.size))
            }

            emit(CascadeProgress.CascadeCompleted(result))
        }
    }

    override suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): CascadeExecutionResult {
        val cascade = cascades[cascadeId] ?: throw IllegalArgumentException("Cascade not found: $cascadeId")
        return executeCascadeInternal(cascade, snapshotId)
    }

    private suspend fun executeCascadeInternal(cascade: OperationalCascade, snapshotId: ForgeSnapshotId?): CascadeExecutionResult {
        // Get source data
        val source = cascade.sources.firstOrNull() ?: throw IllegalStateException("No source for cascade")
        val sampleData = parseSampleData(source)

        // Execute map: emit (key, value) for each row
        val mapStage = cascade.stages.first { it is CascadeStage.MapStage } as CascadeStage.MapStage

        // Simulate map: group by key hierarchy
        val grouped = mutableMapOf<List<String>, MutableList<Map<String, Any>>>()
        for (row in sampleData) {
            val key = cascade.keyHierarchy.map { row[it]?.toString() ?: "" }
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

        // Execute reduce: sum metrics per key
        val outputRows = mutableListOf<CascadeOutputRow>()

            for ((key, rows) in grouped) {
            val reduced = mutableMapOf<String, Any>()
            for (metric in cascade.keyHierarchy) {  // Using keyHierarchy as metric proxy for now
                val values = rows.mapNotNull { it[metric] as? Number }.map { it.toDouble() }
                if (values.isNotEmpty()) {
                    reduced["${metric}_sum"] = values.sum()
                    reduced["${metric}_avg"] = (values.average() ?: 0.0) as Any
                    reduced["${metric}_min"] = (values.minOrNull() ?: 0.0) as Any
                    reduced["${metric}_max"] = (values.maxOrNull() ?: 0.0) as Any
                }
            }
            outputRows.add(CascadeOutputRow(key, Json.encodeToString(reduced)))
        }

        return CascadeExecutionResult(
            executionId = CascadeExecutionId.generate(),
            cascadeId = cascade.id,
            output = outputRows,
            stageOutputs = mapOf("map" to outputRows, "reduce" to outputRows),
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            status = CascadeExecutionStatus.SUCCESS
        )
    }

    override suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph? {
        val cascade = cascades[cascadeId] ?: return null

        val nodes = mutableListOf<CascadeNode>()
        val edges = mutableListOf<CascadeEdge>()

        // Source node
        nodes.add(CascadeNode("source", CascadeStageType.SOURCE, "Source", mapOf("source" to cascade.sources.first().toString())))

        var prevId = "source"
        for (stage in cascade.stages) {
            val type = when (stage) {
                is CascadeStage.MapStage -> CascadeStageType.MAP
                is CascadeStage.ReduceStage -> CascadeStageType.REDUCE
                is CascadeStage.RereduceStage -> CascadeStageType.REREDUCE
                is CascadeStage.FilterStage -> CascadeStageType.FILTER
                is CascadeStage.ProjectStage -> CascadeStageType.PROJECT
                is CascadeStage.JoinStage -> CascadeStageType.JOIN
                else -> CascadeStageType.MAP
            }
            nodes.add(CascadeNode(stage.id, type, stage.id))
            edges.add(CascadeEdge(prevId, stage.id, "data"))
            prevId = stage.id
        }

        // Sink node
        nodes.add(CascadeNode("sink", CascadeStageType.SINK, "Output"))
        edges.add(CascadeEdge(prevId, "sink", "results"))

        return CascadeGraph(cascadeId, nodes, edges)
    }
}