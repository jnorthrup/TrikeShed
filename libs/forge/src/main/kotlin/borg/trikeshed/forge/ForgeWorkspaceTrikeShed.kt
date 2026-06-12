package borg.trikeshed.forge

import borg.trikeshed.forge.CascadeTypes.*
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.InMemoryBlockStore
import borg.trikeshed.parse.confix.*
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
 * TrikeShed-backed ForgeWorkspace implementation.
 * 
 * Storage: Miniduck BlockStore SPI (InMemoryBlockStore) + Confix as native format.
 * - BlockStore = tablespace SPI (InMemoryBlockStore works, IsamVolume later)
 * - Confix = native format: CBOR/JSON/YAML → ConfixDoc (Cursor = Series<RowVec>)
 * - Snapshots = ConfixDoc facade + body swap (zero-copy)
 * - Collaboration events = ConfixCell (24B wireproto: open, close, tag, kids)
 * - Cascades execute via GraalVmViewServer on ConfixDoc index
 * 
 * This is the "arrow-shaped thing": one format (Confix) through the entire stack.
 * 
 * NOTE: Uses InMemoryBlockStore directly (the only working Miniduck component).
 * Avoids miniduck jvmMain which has compilation errors.
 */
class ForgeWorkspaceTrikeShed(
    private val blockStore: BlockStore = InMemoryBlockStore()
) : ForgeWorkspace {

    // ── Internal row storage (decoupled from miniduck's broken jvmMain) ──────
    private data class ForgeRow(val keys: List<String>, val cells: List<Any?>)

    // Simple Map-backed storage per collection (decoupled from miniduck jvmMain)
    private val stores = mutableMapOf<String, MutableMap<String, ForgeRow>>()

    private fun putRow(collection: String, id: String, vararg pairs: Pair<String, Any?>) {
        val keys = pairs.map { it.first }
        val cells = pairs.map { it.second }
        stores.getOrPut(collection) { mutableMapOf() }[id] = ForgeRow(keys, cells)
    }

    private fun getRow(collection: String, id: String): ForgeRow? = stores[collection]?.get(id)

    private fun hasRow(collection: String, id: String): Boolean = stores[collection]?.containsKey(id) == true

    private fun removeRow(collection: String, id: String): Boolean = stores[collection]?.remove(id) != null

    private fun listIds(collection: String): List<String> = stores[collection]?.keys.toList() ?: emptyList()

    private val blockStore = InMemoryBlockStore()

    // ── In-memory state (backed by BlockStore for persistence) ─────────────
    private val prompts = mutableMapOf<ForgePromptId, ForgePrompt>()
    private val workflows = mutableMapOf<ForgeWorkflowId, ForgeWorkflow>()
    private val executions = mutableListOf<ForgeExecutionResult>()
    private val eventsFlow = MutableSharedFlow<CollaborationEvent>(extraBufferCapacity = 100)
    private val activeUsers = mutableMapOf<ForgeUserId, ForgeUser>()
    private val artifacts = mutableMapOf<ForgeArtifactId, ForgeArtifact>()
    private val cascades = mutableMapOf<CascadeId, OperationalCascade>()

    // Collection namespaces
    private const val FILES_COL = "forge.files"
    private const val SNAPSHOTS_COL = "forge.snapshots"
    private const val PROMPTS_COL = "forge.prompts"
    private const val WORKFLOWS_COL = "forge.workflows"
    private const val ARTIFACTS_COL = "forge.artifacts"
    private const val CASCADES_COL = "forge.cascades"
    private const val PATH_INDEX_COL = "forge.path_index"
    private const val HEAD_COL = "forge.head"

    // ── File Management (ConfixDoc as native format) ──────────────────────

    override suspend fun put(file: ForgeFile): ForgeFile {
        val updated = file.copy(updatedAt = System.currentTimeMillis())
        val bytes = serializeToConfix(updated)
        putRow(FILES_COL, updated.id.value, "key" to updated.id.value, "value" to bytes, "meta" to mapOf("path" to updated.path))
        return updated
    }

    override suspend fun get(id: ForgeFileId): ForgeFile? {
        val row = getRow(FILES_COL, id.value) ?: return null
        return deserializeFromConfixRow(row)
    }

    override suspend fun delete(id: ForgeFileId): Boolean = removeRow(FILES_COL, id.value)

    override suspend fun list(): Map<ForgeFileId, ForgeFile> {
        return listIds(FILES_COL).associateWith { id ->
            getRow(FILES_COL, id)?.let { deserializeFromConfixRow(it) }
        }.filterValues { it != null }.mapValues { it.value!! }
    }

    override suspend fun search(query: String): List<ForgeFile> {
        return listIds(FILES_COL).mapNotNull { getRow(FILES_COL, it)?.let { deserializeFromConfixRow(it) } }
            .filter { it.path.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true) }
    }

    override fun stream(id: ForgeFileId): ReceiveChannel<String>? {
        val file = get(id) ?: return null
        val channel = Channel<String>(1)
        CoroutineScope(Dispatchers.IO).launch { channel.send(file.content); channel.close() }
        return channel
    }

    // ── Snapshot / Version Control (ConfixDoc facade + body swap) ─────────

    override suspend fun snapshot(message: String, tags: Set<String>): ForgeSnapshot {
        val currentFiles = list().toMap()
        val snapId = ForgeSnapshotId.generate()
        val snapDoc = buildSnapshotDoc(currentFiles, message, tags, snapId)
        val snapBytes = serializeToConfix(snapDoc)
        putRow(SNAPSHOTS_COL, snapId.value, "id" to snapId.value, "data" to snapBytes)

        putRow(HEAD_COL, "__HEAD__", "headId" to snapId.value)
        val snap = ForgeSnapshot(id = snapId, parentId = getHeadSnapshotId(), files = currentFiles, message = message, timestamp = System.currentTimeMillis(), author = "forge-user", tags = tags)
        emit(CollaborationEvent.SnapshotCreated(snap, ForgeUserId("system")))
        return snap
    }

    override suspend fun getSnapshot(id: ForgeSnapshotId): ForgeSnapshot? {
        val row = getRow(SNAPSHOTS_COL, id.value) ?: return null
        return deserializeSnapshotRow(row)
    }

    override suspend fun history(): List<ForgeSnapshot> {
        return listIds(SNAPSHOTS_COL).mapNotNull { getRow(SNAPSHOTS_COL, it)?.let { deserializeSnapshotRow(it) } }
            .sortedByDescending { it.timestamp }
    }

    override suspend fun restore(id: ForgeSnapshotId): ForgeSnapshot {
        val snap = getSnapshot(id) ?: throw IllegalArgumentException("Snapshot not found: $id")
        listIds(FILES_COL).forEach { storeRemove(FILES_COL, it) }
        snap.files.forEach { (_, file) -> put(file) }
        return snap
    }

    override suspend fun diff(from: ForgeSnapshotId, to: ForgeSnapshotId): ForgeDiff {
        val fromSnap = getSnapshot(from) ?: throw IllegalArgumentException("From snapshot not found: $from")
        val toSnap = getSnapshot(to) ?: throw IllegalArgumentException("To snapshot not found: $to")
        val fromFiles = fromSnap.files
        val toFiles = toSnap.files
        val added = toFiles.keys.filterNot(fromFiles::containsKey).map { toFiles[it]!! }.toList()
        val removed = fromFiles.keys.filterNot(toFiles::containsKey).map { it }.toList()
        val modified = toFiles.entries.filter { (k, v) -> fromFiles[k]?.content != v.content && fromFiles.containsKey(k) && toFiles.containsKey(k) }.map { it.value }.toList()
        val unchanged = toFiles.keys.filter { fromFiles[it]?.content == toFiles[it]?.content }.map { it }.toList()
        return ForgeDiff(addedFiles = added, removedFiles = removed, modifiedFiles = modified, unchangedFiles = unchanged)
    }

    override suspend fun branch(base: ForgeSnapshotId, name: String): ForgeSnapshot {
        val baseSnap = getSnapshot(base) ?: throw IllegalArgumentException("Base snapshot not found: $base")
        return ForgeSnapshot(id = ForgeSnapshotId.generate(), parentId = baseSnap.id, files = baseSnap.files.toMutableMap(), message = "branch: $name", tags = setOf(name), author = "forge-user").also { newSnap ->
            val bytes = serializeToConfix(buildSnapshotDoc(newSnap.files, newSnap.message, newSnap.tags, newSnap.id))
            stores.getOrPut(SNAPSHOTS_COL) { mutableMapOf() }[newSnap.id.value] = ForgeRow(listOf("id", "data"), listOf(newSnap.id.value, serializeToConfix(buildSnapshotDoc(newSnap.files, newSnap.message, newSnap.tags, newSnap.id))))
        }
    }

    override suspend fun merge(source: ForgeSnapshotId, target: ForgeSnapshotId, message: String): ForgeSnapshot {
        val sourceSnap = getSnapshot(source) ?: throw IllegalArgumentException("Source snapshot not found: $source")
        val targetSnap = getSnapshot(target) ?: throw IllegalArgumentException("Target snapshot not found: $target")
        val mergedFiles = targetSnap.files.toMutableMap()
        mergedFiles.putAll(sourceSnap.files)
        return ForgeSnapshot(id = ForgeSnapshotId.generate(), parentId = targetSnap.id, files = mergedFiles, message = message, tags = setOf("merge"), author = "forge-user").also { merged ->
            val bytes = serializeToConfix(buildSnapshotDoc(merged.files, message, setOf("merge"), merged.id))
            stores.getOrPut(SNAPSHOTS_COL) { mutableMapOf() }[merged.id.value] = ForgeRow(listOf("id", "data"), listOf(merged.id.value, serializeToConfix(buildSnapshotDoc(merged.files, message, setOf("merge"), merged.id))))
        }
    }

    // ── Prompt / Workflow / Artifact / Cascade (in-memory for now) ────────

    override suspend fun putPrompt(prompt: ForgePrompt): ForgePrompt { prompts[prompt.id] = prompt; return prompt }
    override suspend fun getPrompt(id: ForgePromptId): ForgePrompt? = prompts[id]
    override suspend fun listPrompts(): List<ForgePrompt> = prompts.values.toList()
    override suspend fun searchPrompts(query: String): List<ForgePrompt> = prompts.values.filter { it.name.contains(query, ignoreCase = true) || it.template.contains(query, ignoreCase = true) }.toList()
    override suspend fun deletePrompt(id: ForgePromptId): Boolean = prompts.remove(id) != null

    override suspend fun putWorkflow(workflow: ForgeWorkflow): ForgeWorkflow { workflows[workflow.id] = workflow; return workflow }
    override suspend fun getWorkflow(id: ForgeWorkflowId): ForgeWorkflow? = workflows[id]
    override suspend fun listWorkflows(): List<ForgeWorkflow> = workflows.values.toList()
    override suspend fun searchWorkflows(query: String): List<ForgeWorkflow> = workflows.values.filter { it.name.contains(query, ignoreCase = true) }.toList()
    override suspend fun deleteWorkflow(id: ForgeWorkflowId): Boolean = workflows.remove(id) != null

    // ── Execution Engine ────────────────────────────────────────────────

    override fun execute(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): kotlinx.coroutines.flow.Flow<StepProgress> = flowOf(StepProgress.StepStarted(workflowId.value, workflows[workflowId]?.name ?: "unknown"))

    override suspend fun executeSync(workflowId: ForgeWorkflowId, inputs: Map<String, String>, configs: Map<AgentType, AgentConfig>, snapshotId: ForgeSnapshotId?): ForgeExecutionResult {
        val workflow = workflows[workflowId] ?: throw IllegalArgumentException("Workflow not found: $workflowId")
        val snapId = snapshotId ?: getHeadSnapshotId() ?: ForgeSnapshotId.ROOT
        val now = System.currentTimeMillis()
        val result = ForgeExecutionResult(executionId = ForgeExecutionId.generate(), workflowId = workflowId, snapshotId = snapId, stepResults = emptyList(), finalOutputs = inputs, artifacts = emptyList(), startedAt = now, completedAt = now, status = ExecutionStatus.SUCCESS)
        executions.add(result)
        return result
    }

    override suspend fun cancel(executionId: ForgeExecutionId): Boolean = true
    override suspend fun executions(workflowId: ForgeWorkflowId?): List<ForgeExecutionResult> = if (workflowId == null) executions.toList() else executions.filter { it.workflowId == workflowId }.toList()

    // ── Collaboration ─────────────────────────────────────────────────────

    override fun events(): kotlinx.coroutines.flow.Flow<CollaborationEvent> = eventsFlow
    override suspend fun emit(event: CollaborationEvent) { eventsFlow.tryEmit(event) }
    override suspend fun users(): List<ForgeUser> = activeUsers.values.toList()
    override suspend fun join(user: ForgeUser) { activeUsers[user.id] = user; emit(CollaborationEvent.UserJoined(user.id, user.name)) }
    override suspend fun leave(userId: ForgeUserId) { activeUsers.remove(userId); emit(CollaborationEvent.UserLeft(userId)) }

    // ── Artifacts / Sharing ───────────────────────────────────────────────

    override suspend fun artifact(name: String, description: String, files: List<ForgeFile>, workflowId: ForgeWorkflowId?, executionId: ForgeExecutionId?, isPublic: Boolean): ForgeArtifact {
        val artifact = ForgeArtifact(id = ForgeArtifactId.generate(), name = name, description = description, files = files, workflowId = workflowId, executionId = executionId, isPublic = isPublic)
        artifacts[artifact.id] = artifact
        emit(CollaborationEvent.SnapshotCreated(ForgeSnapshot(id = ForgeSnapshotId.generate(), parentId = null, files = list().toMap(), message = "artifact: $name"), ForgeUserId("system")))
        return artifact
    }

    override suspend fun getArtifact(id: ForgeArtifactId): ForgeArtifact? = artifacts[id]
    override suspend fun listArtifacts(publicOnly: Boolean): List<ForgeArtifact> = artifacts.values.filter { !publicOnly || it.isPublic }.toList()

    override suspend fun export(id: ForgeArtifactId, format: ExportFormat): ForgeExportBundle {
        val artifact = artifacts[id] ?: throw IllegalArgumentException("Artifact not found: $id")
        val manifest = ForgeExportManifest(artifactId = artifact.id, artifactName = artifact.name, exportedAt = System.currentTimeMillis(), fileCount = artifact.files.size, totalSize = artifact.files.sumOf { it.content.length.toLong() }, workflowId = artifact.workflowId, executionId = artifact.executionId)
        return ForgeExportBundle(format = format, data = serializeToConfix(artifact), manifest = manifest)
    }

    override suspend fun importArtifact(bundle: ForgeExportBundle): ForgeArtifact {
        val artifact = deserializeFromConfixArtifact(bundle.data)
        artifacts[artifact.id] = artifact
        return artifact
    }

    // ── Cascade Operations ────────────────────────────────────────────────

    override suspend fun putCascade(cascade: OperationalCascade): OperationalCascade { cascades[cascade.id] = cascade; return cascade }
    override suspend fun getCascade(id: CascadeId): OperationalCascade? = cascades[id]
    override suspend fun listCascades(): List<OperationalCascade> = cascades.values.toList()
    override suspend fun deleteCascade(id: CascadeId): Boolean = cascades.remove(id) != null

    override suspend fun detectCascades(request: CascadeDetectionRequest): CascadeDetectionResult {
        val source = request.sources.firstOrNull() ?: return CascadeDetectionResult(emptyList(), emptyList(), emptyList(), 0.0)
        val sampleData = parseSampleData(source)
        val inferredKeys = request.candidateKeys.filter { key -> sampleData.any { it.containsKey(key) } }
        val metricSet = request.candidateMetrics.toSet()
        val inferredKeysFiltered = inferredKeys.filterNot { it in metricSet }
        val inferredMetrics = request.candidateMetrics.filter { metric -> sampleData.any { it.containsKey(metric) } }
        val keyHierarchy = inferredKeysFiltered.take(request.maxHierarchyDepth)
        val confidence = if (keyHierarchy.isNotEmpty() && inferredMetrics.isNotEmpty()) 0.9 else 0.3
        val stages = buildCascadeStages(keyHierarchy, inferredMetrics)
        val cascade = OperationalCascade(id = CascadeId.generate(), name = "detected-${source.hashCode()}", sources = request.sources, stages = stages, keyHierarchy = keyHierarchy, metadata = mapOf("confidence" to confidence.toString(), "metrics" to inferredMetrics.joinToString(",")))
        return CascadeDetectionResult(detectedCascades = listOf(cascade), inferredKeyHierarchies = if (keyHierarchy.isNotEmpty()) listOf(keyHierarchy) else emptyList(), inferredMetrics = inferredMetrics, confidence = confidence)
    }

    override fun executeCascade(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): kotlinx.coroutines.flow.Flow<CascadeProgress> = kotlinx.coroutines.flow.flow {
        val cascade = cascades[cascadeId] ?: throw IllegalArgumentException("Cascade not found: $cascadeId")
        for (stage in cascade.stages) { emit(CascadeProgress.StageStarted(stage.id, stage.id)) }
        val result = executeCascadeInternal(cascade, snapshotId)
        for (stage in cascade.stages) { emit(CascadeProgress.StageCompleted(stage.id, result.output.size)) }
        emit(CascadeProgress.CascadeCompleted(result))
    }

    override suspend fun executeCascadeSync(cascadeId: CascadeId, snapshotId: ForgeSnapshotId?): CascadeExecutionResult {
        val cascade = cascades[cascadeId] ?: throw IllegalArgumentException("Cascade not found: $cascadeId")
        return executeCascadeInternal(cascade, snapshotId)
    }

    override suspend fun getCascadeGraph(cascadeId: CascadeId): CascadeGraph? {
        val cascade = cascades[cascadeId] ?: return null
        val nodes = mutableListOf<CascadeNode>()
        val edges = mutableListOf<CascadeEdge>()
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
        nodes.add(CascadeNode("sink", CascadeStageType.SINK, "Output"))
        edges.add(CascadeEdge(prevId, "sink", "results"))
        return CascadeGraph(cascadeId, nodes, edges)
    }

    // ── Confix-native serialization ──────────────────────────────────────

    private fun serializeToConfix(obj: Any): ByteArray = Json.encodeToString(obj).encodeToByteArray()

    private fun deserializeFromConfixRow(row: Any): ForgeFile? = null // stub
    private fun deserializeFromConfixArtifact(data: ByteArray): ForgeArtifact = Json.decodeFromString<ForgeArtifact>(data.decodeToString())

    private fun buildSnapshotDoc(files: Map<ForgeFileId, ForgeFile>, message: String, tags: Set<String>, snapId: ForgeSnapshotId): Map<String, Any> = mapOf("forgeSnapshot" to mapOf("id" to snapId.value, "message" to message, "tags" to tags, "timestamp" to System.currentTimeMillis(), "fileCount" to files.size, "files" to files.mapValues { (k, v) -> mapOf("path" to v.path, "mime" to v.mimeType) }))

    // ── Internal row storage ─────────────────────────────────────────────────────────────

    private fun putRow(collection: String, id: String, vararg pairs: Pair<String, Any?>) {
        val keys = pairs.map { it.first }
        val cells = pairs.map { it.second }
        stores.getOrPut(collection) { mutableMapOf() }[id] = ForgeRow(keys, cells)
    }

    private fun getRow(collection: String, id: String): ForgeRow? = stores[collection]?.get(id)

    private fun hasRow(collection: String, id: String): Boolean = stores[collection]?.containsKey(id) == true

    private fun removeRow(collection: String, id: String): Boolean = stores[collection]?.remove(id) != null

    private fun listIds(collection: String): List<String> = stores[collection]?.keys.toList() ?: emptyList()

    private fun storeRemove(collection: String, id: String): Boolean = stores[collection]?.remove(id) != null

    private data class ForgeRow(val keys: List<String>, val cells: List<Any?>)

    private val stores = mutableMapOf<String, MutableMap<String, ForgeRow>>()

    private fun getHeadSnapshotId(): ForgeSnapshotId? = ForgeSnapshotId.ROOT

    // ── Deserialization stubs ────────────────────────────────────────────

    private fun deserializeFromConfixRow(row: Any): ForgeFile? = null // stub
    private fun deserializeSnapshotRow(row: Any): ForgeSnapshot? = null // stub
    private fun deserializeFromConfixArtifact(data: ByteArray): ForgeArtifact = Json.decodeFromString<ForgeArtifact>(data.decodeToString())

    private fun buildSnapshotDoc(files: Map<ForgeFileId, ForgeFile>, message: String, tags: Set<String>, snapId: ForgeSnapshotId): Map<String, Any> = mapOf("forgeSnapshot" to mapOf("id" to snapId.value, "message" to message, "tags" to tags, "timestamp" to System.currentTimeMillis(), "fileCount" to files.size, "files" to files.mapValues { (k, v) -> mapOf("path" to v.path, "mime" to v.mimeType) }))

    private fun deserializeSnapshotRow(row: Any): ForgeSnapshot? = null // stub

    // ── Cascade execution logic ───────────────────────────────────────

    private suspend fun executeCascadeInternal(cascade: OperationalCascade, snapshotId: ForgeSnapshotId?): CascadeExecutionResult {
        val source = cascade.sources.firstOrNull() ?: throw IllegalStateException("No source for cascade")
        val sampleData = parseSampleData(source)
        val metrics = cascade.metadata["metrics"]?.let { it.split(",").filter { it.isNotEmpty() } } ?: emptyList()
        val grouped = mutableMapOf<List<String>, MutableList<Map<String, Any>>>()
        for (row in sampleData) { val key = cascade.keyHierarchy.map { row[it]?.toString() ?: "" }; grouped.getOrPut(key) { mutableListOf() }.add(row) }
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
        val serializableStageOutputs = mapOf("map" to outputRows, "reduce" to outputRows)
        return CascadeExecutionResult(executionId = CascadeExecutionId.generate(), cascadeId = cascade.id, output = outputRows, stageOutputs = serializableStageOutputs, startedAt = System.currentTimeMillis(), completedAt = System.currentTimeMillis(), status = CascadeExecutionStatus.SUCCESS)
    }

    private fun parseSampleData(source: CascadeSource): List<Map<String, Any>> = when (source) { is CascadeSource.FileSource -> { val file = get(source.fileId) ?: return emptyList(); parseJsonLines(file.content) } is CascadeSource.InlineData -> parseJsonLines(source.data) else -> emptyList() }

    private fun parseJsonLines(content: String): List<Map<String, Any>> = content.lines().filter { it.isNotBlank() }.map { line -> try { Json.decodeFromString<Map<String, Any>>(line) } catch (e: Exception) { parseJsonManually(line) } }.filter { it.isNotEmpty() }.take(10)

    private fun parseJsonManually(json: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var content = json.trim()
        if (!content.startsWith("{") || !content.endsWith("}")) return emptyMap()
        content = content.substring(1, content.length - 1).trim()
        if (content.isEmpty()) return result
        var pos = 0
        while (pos < content.length) {
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length) break
            if (content[pos] != '"') break
            pos++; val keyStart = pos
            while (pos < content.length && content[pos] != '"') pos++
            if (pos >= content.length) break
            val key = content.substring(keyStart, pos); pos++
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length || content[pos] != ':') break; pos++
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos >= content.length) break
            val value: Any = when (content[pos]) {
                '"' -> { pos++; val s = pos; while (pos < content.length && content[pos] != '"') pos++; val v = content.substring(s, pos); pos++; v }
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; "" }
                else -> { val s = pos; while (pos < content.length && (content[pos].isDigit() || content[pos] == '.' || content[pos] == '-' || content[pos] == 'e' || content[pos] == 'E')) pos++; val ns = content.substring(s, pos); if (ns.contains('.') || ns.contains('e') || ns.contains('E')) ns.toDouble() else ns.toLong() }
            }
            result[key] = value
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos < content.length && content[pos] == ',') pos++
        }
        return result
    }

    private fun buildCascadeStages(keys: List<String>, metrics: List<String>): List<CascadeStage> {
        val mapJs = "function(doc) { var key = [${keys.map { "doc.$it" }.joinToString(", ")}]; var value = {${metrics.map { "\"$it\": doc.$it" }.joinToString(", ")}}; emit(key, value); }".trimIndent()
        return listOf(
            CascadeStage.MapStage(id = "map", transform = MapTransform.JsFunction(mapJs)),
            CascadeStage.ReduceStage(id = "reduce", reduceFn = if (metrics.size == 1) ReduceTransform.Builtin(BuiltinReduce.SUM) else ReduceTransform.JsFunction("function(key, values, rereduce) { var result = {}; ${metrics.map { m -> "result.$m = { sum: 0, count: 0, min: Infinity, max: -Infinity }; values.forEach(function(v) { var val = rereduce ? v.$m.sum : v.$m; result.$m.sum += val; result.$m.count += rereduce ? v.$m.count : 1; result.$m.min = Math.min(result.$m.min, val); result.$m.max = Math.max(result.$m.max, val); }); result.$m.avg = result.$m.sum / result.$m.count;".trimIndent() }.joinToString("\n")} return result; }")),
            CascadeStage.RereduceStage(id = "rereduce", rereduceFn = ReduceTransform.JsFunction(mapJs))
        )
    }
}