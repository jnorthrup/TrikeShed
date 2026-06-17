package borg.trikeshed.forge

import borg.trikeshed.forge.platform.PlatformUtils
import kotlinx.coroutines.CoroutineScope
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
        val updated = file.copy(updatedAt = PlatformUtils.currentTimeMillis())
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
        CoroutineScope(PlatformUtils.ioDispatcher).launch {
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
        val now = PlatformUtils.currentTimeMillis()

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
            exportedAt = PlatformUtils.currentTimeMillis(),
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
        val allCandidateKeys = request.candidateKeys.filter { key ->
            sampleData.any { it.containsKey(key) }
        }
        // Key hierarchy = candidate keys that are NOT metrics (grouping keys only)
        val metricSet = request.candidateMetrics.toSet()
        val inferredKeys = allCandidateKeys.filterNot { it in metricSet }
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
            metadata = mapOf(
                "confidence" to confidence.toString(),
                "metrics" to inferredMetrics.joinToString(",")
            )
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
        val results = mutableListOf<Map<String, Any>>()
        for (line in lines) {
            try {
                // Use kotlinx.serialization with MapSerializer for more flexible parsing
                val map = Json.decodeFromString<Map<String, Any>>(line)
                if (map.isNotEmpty()) results.add(map)
            } catch (e: Exception) {
                // Fallback: simple manual parsing for basic JSON
                val manual = parseJsonManually(line)
                if (manual.isNotEmpty()) results.add(manual)
            }
            if (results.size >= 10) break
        }
        return results
    }

    // Simple manual JSON parser for basic key-value pairs (strings, numbers, booleans)
    private fun parseJsonManually(json: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var content = json.trim()
        if (!content.startsWith("{") || !content.endsWith("}")) return emptyMap()
        content = content.substring(1, content.length - 1).trim()
        if (content.isEmpty()) return result

        var pos = 0
        while (pos < content.length) {
            // Skip whitespace
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length) break

            // Parse key (expect quoted string)
            if (content[pos] != '"') break
            pos++
            val keyStart = pos
            while (pos < content.length && content[pos] != '"') pos++
            if (pos >= content.length) break
            val key = content.substring(keyStart, pos)
            pos++ // skip closing quote

            // Skip whitespace and colon
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length || content[pos] != ':') break
            pos++

            // Skip whitespace
            while (pos < content.length && content[pos].isWhitespace()) pos++

            // Parse value
            val value: Any
            if (pos >= content.length) break
            when (content[pos]) {
                '"' -> {
                    pos++
                    val valStart = pos
                    while (pos < content.length && content[pos] != '"') pos++
                    value = content.substring(valStart, pos)
                    pos++ // skip closing quote
                }
                't' -> { // true
                    value = true
                    pos += 4
                }
                'f' -> { // false
                    value = false
                    pos += 5
                }
                'n' -> { // null
                    value = ""
                    pos += 4
                }
                else -> { // number
                    val valStart = pos
                    while (pos < content.length && (content[pos].isDigit() || content[pos] == '.' || content[pos] == '-' || content[pos] == 'e' || content[pos] == 'E')) pos++
                    val numStr = content.substring(valStart, pos)
                    value = if (numStr.contains('.') || numStr.contains('e') || numStr.contains('E'))
                        numStr.toDouble() else numStr.toLong()
                }
            }
            result[key] = value

            // Skip whitespace and comma
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos < content.length && content[pos] == ',') pos++
        }
        return result
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

        // Get metrics from cascade metadata
        val metrics = cascade.metadata["metrics"]?.let { it.split(",").filter { it.isNotEmpty() } } ?: emptyList()

        // Simulate map: group by key hierarchy
        val grouped = mutableMapOf<List<String>, MutableList<Map<String, Any>>>()
        for (row in sampleData) {
            val key = cascade.keyHierarchy.map { row[it]?.toString() ?: "" }
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

        // Execute reduce: sum metrics per key
        val outputRows = mutableListOf<CascadeOutputRow>()

        for ((key, rows) in grouped) {
            val reduced = mutableMapOf<String, String>()
            for (metric in metrics) {
                val values = rows.mapNotNull { it[metric] as? Number }.map { it.toDouble() }
                if (values.isNotEmpty()) {
                    reduced["${metric}_sum"] = values.sum().toString()
                    reduced["${metric}_avg"] = (values.average() ?: 0.0).toString()
                    reduced["${metric}_min"] = (values.minOrNull() ?: 0.0).toString()
                    reduced["${metric}_max"] = (values.maxOrNull() ?: 0.0).toString()
                }
            }
            outputRows.add(CascadeOutputRow(key, Json.encodeToString(reduced)))
        }

        // Create a serializable stageOutputs without Any types
        val serializableStageOutputs = mapOf<String, List<CascadeOutputRow>>(
            "map" to outputRows,
            "reduce" to outputRows
        )

        return CascadeExecutionResult(
            executionId = CascadeExecutionId.generate(),
            cascadeId = cascade.id,
            output = outputRows,
            stageOutputs = serializableStageOutputs,
            startedAt = PlatformUtils.currentTimeMillis(),
            completedAt = PlatformUtils.currentTimeMillis(),
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

    // =========================================================================
    // Patch Bay / Cable Operations (Real-time Signal Routing) - Stub implementations
    // =========================================================================

    private val patchBays = mutableMapOf<PatchBayId, PatchBay>()
    private val patchBayModules = mutableMapOf<PatchBayId, MutableMap<String, ModuleSpec>>()
    private val patchBayCables = mutableMapOf<PatchBayId, MutableList<PatchCable>>()

    override suspend fun putPatchBay(patchBay: PatchBay): PatchBay {
        patchBays[patchBay.id] = patchBay
        patchBayModules[patchBay.id] = mutableMapOf()
        patchBayCables[patchBay.id] = mutableListOf()
        return patchBay
    }

    override suspend fun getPatchBay(id: PatchBayId): PatchBay? = patchBays[id]

    override suspend fun listPatchBays(): List<PatchBay> = patchBays.values.toList()

    override suspend fun deletePatchBay(id: PatchBayId): Boolean = patchBays.remove(id) != null

    override suspend fun putModule(patchBayId: PatchBayId, module: ModuleSpec): ModuleSpec {
        val modules = patchBayModules.getOrPut(patchBayId) { mutableMapOf() }
        modules[module.id] = module
        return module
    }

    override suspend fun getModule(patchBayId: PatchBayId, moduleId: String): ModuleSpec? =
        patchBayModules[patchBayId]?.get(moduleId)

    override suspend fun deleteModule(patchBayId: PatchBayId, moduleId: String): Boolean =
        patchBayModules[patchBayId]?.remove(moduleId) != null

    override suspend fun connectCable(patchBayId: PatchBayId, cable: PatchCable): PatchCable {
        val cables = patchBayCables.getOrPut(patchBayId) { mutableListOf() }
        cables.removeAll { it.id == cable.id }
        cables.add(cable)
        return cable
    }

    override suspend fun disconnectCable(patchBayId: PatchBayId, cableId: CableId): Boolean {
        val cables = patchBayCables[patchBayId] ?: return false
        val originalSize = cables.size
        cables.removeAll { it.id == cableId }
        return cables.size < originalSize
    }

    override suspend fun setCableState(patchBayId: PatchBayId, cableId: CableId, state: CableState): PatchCable? {
        val cables = patchBayCables[patchBayId] ?: return null
        val index = cables.indexOfFirst { it.id == cableId }
        if (index == -1) return null
        val updated = cables[index].copy(state = state)
        cables[index] = updated
        return updated
    }

    override suspend fun setCableTransform(patchBayId: PatchBayId, cableId: CableId, transform: CableTransform?): PatchCable? {
        val cables = patchBayCables[patchBayId] ?: return null
        val index = cables.indexOfFirst { it.id == cableId }
        if (index == -1) return null
        val updated = cables[index].copy(transform = transform)
        cables[index] = updated
        return updated
    }

    override suspend fun processPatchBay(patchBayId: PatchBayId, inputs: Map<String, String>, frameCount: Int): Map<String, String> {
        // Stub: pass through inputs as outputs
        return inputs
    }

    override fun streamPatchBay(patchBayId: PatchBayId, outputPort: PortAddress): kotlinx.coroutines.flow.Flow<Map<String, String>> {
        return kotlinx.coroutines.flow.flowOf(emptyMap())
    }

    override suspend fun getPatchBayGraph(patchBayId: PatchBayId): PatchBayGraph? {
        val patchBay = patchBays[patchBayId] ?: return null
        val modules = patchBayModules[patchBayId] ?: return null
        val cables = patchBayCables[patchBayId] ?: return null

        val nodes = modules.values.map { module ->
            PatchBayNode(
                id = module.id,
                moduleType = module.moduleType,
                label = module.id,
                position = module.position,
                inputPorts = module.inputPorts,
                outputPorts = module.outputPorts,
            )
        }.toList()

        val edges = cables.map { cable ->
            PatchBayEdge(
                id = cable.id,
                source = cable.source,
                destination = cable.destination,
                state = cable.state,
                transform = cable.transform,
            )
        }.toList()

        return PatchBayGraph(patchBayId, nodes, edges)
    }

    override suspend fun autoLayout(patchBayId: PatchBayId, algorithm: LayoutAlgorithm): PatchBay {
        // Stub: return patch bay unchanged
        return patchBays[patchBayId] ?: PatchBay(patchBayId, "empty", emptyMap(), emptyList())
    }

    override suspend fun createModuleFromStep(patchBayId: PatchBayId, step: WorkflowStep, position: ModulePosition): ModuleSpec {
        val inputPorts: List<PortSpec>
        val outputPorts: List<PortSpec>
        val moduleType: ModuleType
        
        when (step) {
            is WorkflowStep.LlmCall -> {
                inputPorts = step.inputs.keys.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.LLM_CALL
            }
            is WorkflowStep.CodeExecution -> {
                inputPorts = step.inputs.keys.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.CODE_EXECUTION
            }
            is WorkflowStep.AgentInvocation -> {
                inputPorts = step.context.keys.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.AGENT_INVOCATION
            }
            is WorkflowStep.FileTransform -> {
                inputPorts = step.inputFileIds.mapIndexed { idx, _ -> PortSpec("file$idx", PortType.DATA, PortDirection.INPUT, "ForgeFile") }
                outputPorts = listOf(PortSpec("outputPath", PortType.DATA, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.FILE_TRANSFORM
            }
            is WorkflowStep.Conditional -> {
                inputPorts = step.condition.split(" ").filter { it.startsWith("{{") }.map { it.trim('{', '}') }
                    .map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("then", PortType.CONTROL, PortDirection.OUTPUT, "String"), PortSpec("else", PortType.CONTROL, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.CONDITIONAL
            }
            is WorkflowStep.Parallel -> {
                val allKeys = mutableSetOf<String>()
                for (branch in step.branches) {
                    for (s in branch) {
                        val keys = when (s) {
                            is WorkflowStep.LlmCall -> s.inputs.keys
                            is WorkflowStep.CodeExecution -> s.inputs.keys
                            is WorkflowStep.AgentInvocation -> s.context.keys
                            is WorkflowStep.CascadeExecution -> s.inputs.keys
                            else -> emptySet()
                        }
                        allKeys.addAll(keys)
                    }
                }
                inputPorts = allKeys.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("branch1", PortType.DATA, PortDirection.OUTPUT, "String"), PortSpec("branch2", PortType.DATA, PortDirection.OUTPUT, "String"))
                moduleType = ModuleType.PARALLEL
            }
            is WorkflowStep.CascadeExecution -> {
                inputPorts = step.inputs.keys.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
                outputPorts = listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "CascadeOutputRow"))
                moduleType = ModuleType.CASCADE_EXECUTION
            }
            else -> {
                inputPorts = emptyList()
                outputPorts = emptyList()
                moduleType = ModuleType.CUSTOM
            }
        }

        return ModuleSpec(
            id = step.id,
            moduleType = moduleType,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            position = position,
        )
    }

    override suspend fun createModuleFromCascade(patchBayId: PatchBayId, cascade: OperationalCascade, position: ModulePosition): ModuleSpec {
        val inputPorts = cascade.keyHierarchy.map { PortSpec(it, PortType.DATA, PortDirection.INPUT, "String") }
        val outputPorts = cascade.stages.filterIsInstance<CascadeStage.ReduceStage>().flatMap { it.reduceFn.toString().split(" ") }.distinct()
            .map { PortSpec(it, PortType.DATA, PortDirection.OUTPUT, "String") }
            .ifEmpty { listOf(PortSpec("output", PortType.DATA, PortDirection.OUTPUT, "CascadeOutputRow")) }

        return ModuleSpec(
            id = cascade.id.value,
            moduleType = ModuleType.REDUCE,
            inputPorts = inputPorts,
            outputPorts = outputPorts,
            position = position,
        )
    }
}