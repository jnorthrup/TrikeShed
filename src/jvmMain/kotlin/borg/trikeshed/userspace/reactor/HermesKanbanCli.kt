package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.Paths

class HermesKanbanCli(
    val workspaceRoot: Path = Paths.get(".").toAbsolutePath().normalize(),
    val board: String = "tshed",
    val assignee: String = "kanban-worker",
    val processOperations: ProcessOperations = JvmProcessOperations(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createTriageCard(
        title: String,
        body: String,
        idempotencyKey: String,
        workspace: String = workspaceSpec(),
    ): HermesKanbanTaskRef {
        val args = mutableListOf("kanban", "--board", board, "create", title)
        if (body.isNotBlank()) {
            args += listOf("--body", body)
        }
        if (assignee.isNotBlank()) {
            args += listOf("--assignee", assignee)
        }
        args += listOf(
            "--workspace", workspace,
            "--idempotency-key", idempotencyKey,
            "--triage",
            "--json",
        )
        val output = runHermes(args)
        val payload = parseJsonOrNull(output)
        val taskId = payload?.extractTaskId() ?: extractTaskIdFromText(output)
            ?: error("Could not parse task id from hermes kanban create output: $output")
        return HermesKanbanTaskRef(taskId, payload)
    }

    suspend fun comment(taskId: String, text: String) {
        runHermes(listOf("kanban", "--board", board, "comment", taskId, text))
    }

    suspend fun block(taskId: String, reason: String) {
        runHermes(listOf("kanban", "--board", board, "block", taskId, reason))
    }

    suspend fun complete(taskId: String, summary: String, metadata: Map<String, String> = emptyMap()) {
        val args = mutableListOf("kanban", "--board", board, "complete", "--summary", summary)
        if (metadata.isNotEmpty()) {
            args += listOf("--metadata", metadataJson(metadata))
        }
        args += taskId
        runHermes(args)
    }

    suspend fun list(status: String? = null): List<HermesKanbanTask> {
        val args = mutableListOf("kanban", "--board", board, "list", "--json")
        if (!status.isNullOrBlank()) {
            args += listOf("--status", status)
        }
        val payload = parseJsonOrNull(runHermes(args)) ?: return emptyList()
        val array = payload as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let(::parseTask)
        }
    }

    suspend fun show(taskId: String): HermesKanbanTaskDetail {
        val payload = parseJsonOrNull(runHermes(listOf("kanban", "--board", board, "show", taskId, "--json")))
            ?: error("Empty hermes kanban show output for $taskId")
        val root = payload as? JsonObject ?: error("Expected JSON object from hermes kanban show for $taskId: $payload")
        val task = root.objectOrNull("task")?.let(::parseTask)
            ?: error("Missing task object in hermes kanban show output for $taskId: $payload")
        return HermesKanbanTaskDetail(
            task = task,
            latestSummary = root.stringOrNull("latest_summary"),
            parents = root.stringList("parents"),
            children = root.stringList("children"),
            comments = root.objectList("comments").map(::parseComment),
            events = root.objectList("events").map(::parseEvent),
            runs = root.objectList("runs").map(::parseRun),
        )
    }

    fun workspaceSpec(): String = "dir:${workspaceRoot.toAbsolutePath().normalize()}"

    fun withBoard(board: String): HermesKanbanCli = HermesKanbanCli(
        workspaceRoot = workspaceRoot,
        board = board,
        assignee = assignee,
        processOperations = processOperations,
    )

    private suspend fun runHermes(args: List<String>): String {
        val result = processOperations.exec("hermes", args)
        val stdout = result.stdout.decodeToString().trim()
        val stderr = result.stderr.decodeToString().trim()
        check(result.exitCode == 0) {
            listOf(stderr, stdout)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .ifBlank { "hermes ${args.joinToString(" ")} failed with exit ${result.exitCode}" }
        }
        return stdout
    }

    private fun parseJsonOrNull(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return json.parseToJsonElement(trimmed)
    }

    private fun metadataJson(metadata: Map<String, String>): String = buildJsonObject {
        metadata.toSortedMap().forEach { (key, value) ->
            put(key, value)
        }
    }.toString()

    private fun extractTaskIdFromText(text: String): String? = Regex("""t_[0-9a-f]+""").find(text)?.value

    private fun JsonElement.extractTaskId(): String? = when (this) {
        is JsonObject -> stringOrNull("task_id")
            ?: stringOrNull("id")
            ?: objectOrNull("task")?.stringOrNull("id")
        else -> null
    }

    private fun parseTask(json: JsonObject): HermesKanbanTask = HermesKanbanTask(
        id = json.stringOrNull("id") ?: error("kanban task missing id: $json"),
        title = json.stringOrNull("title") ?: "",
        body = json.stringOrNull("body"),
        assignee = json.stringOrNull("assignee"),
        status = json.stringOrNull("status") ?: "todo",
        priority = json.intOrNull("priority") ?: 0,
        tenant = json.stringOrNull("tenant"),
        workspaceKind = json.stringOrNull("workspace_kind"),
        workspacePath = json.stringOrNull("workspace_path"),
        branchName = json.stringOrNull("branch_name"),
        createdBy = json.stringOrNull("created_by"),
        createdAt = json.longOrNull("created_at"),
        startedAt = json.longOrNull("started_at"),
        completedAt = json.longOrNull("completed_at"),
        result = json.stringOrNull("result"),
        skills = json.stringList("skills"),
        maxRetries = json.intOrNull("max_retries"),
        sessionId = json.stringOrNull("session_id"),
        workflowTemplateId = json.stringOrNull("workflow_template_id"),
        currentStepKey = json.stringOrNull("current_step_key"),
    )

    private fun parseComment(json: JsonObject): HermesKanbanComment = HermesKanbanComment(
        id = json.intOrNull("id"),
        author = json.stringOrNull("author") ?: json.stringOrNull("by") ?: "unknown",
        body = json.stringOrNull("body") ?: json.stringOrNull("text") ?: "",
        createdAt = json.longOrNull("created_at"),
    )

    private fun parseEvent(json: JsonObject): HermesKanbanEvent = HermesKanbanEvent(
        kind = json.stringOrNull("kind") ?: "unknown",
        payload = json["payload"],
        createdAt = json.longOrNull("created_at"),
        runId = json.intOrNull("run_id"),
    )

    private fun parseRun(json: JsonObject): HermesKanbanRun = HermesKanbanRun(
        id = json.intOrNull("id") ?: -1,
        profile = json.stringOrNull("profile") ?: "",
        stepKey = json.stringOrNull("step_key"),
        status = json.stringOrNull("status") ?: "",
        outcome = json.stringOrNull("outcome"),
        summary = json.stringOrNull("summary"),
        error = json.stringOrNull("error"),
        metadata = json["metadata"],
        workerPid = json.intOrNull("worker_pid"),
        startedAt = json.longOrNull("started_at"),
        endedAt = json.longOrNull("ended_at"),
    )

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringList(key: String): List<String> = when (val element = this[key]) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun JsonObject.objectList(key: String): List<JsonObject> = when (val element = this[key]) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
}

data class HermesKanbanTaskRef(
    val taskId: String,
    val payload: JsonElement? = null,
)

data class HermesKanbanTask(
    val id: String,
    val title: String,
    val body: String?,
    val assignee: String?,
    val status: String,
    val priority: Int,
    val tenant: String?,
    val workspaceKind: String?,
    val workspacePath: String?,
    val branchName: String?,
    val createdBy: String?,
    val createdAt: Long?,
    val startedAt: Long?,
    val completedAt: Long?,
    val result: String?,
    val skills: List<String>,
    val maxRetries: Int?,
    val sessionId: String?,
    val workflowTemplateId: String?,
    val currentStepKey: String?,
)

data class HermesKanbanTaskDetail(
    val task: HermesKanbanTask,
    val latestSummary: String?,
    val parents: List<String>,
    val children: List<String>,
    val comments: List<HermesKanbanComment>,
    val events: List<HermesKanbanEvent>,
    val runs: List<HermesKanbanRun>,
)

data class HermesKanbanComment(
    val id: Int?,
    val author: String,
    val body: String,
    val createdAt: Long?,
)

data class HermesKanbanEvent(
    val kind: String,
    val payload: JsonElement?,
    val createdAt: Long?,
    val runId: Int?,
)

data class HermesKanbanRun(
    val id: Int,
    val profile: String,
    val stepKey: String?,
    val status: String,
    val outcome: String?,
    val summary: String?,
    val error: String?,
    val metadata: JsonElement?,
    val workerPid: Int?,
    val startedAt: Long?,
    val endedAt: Long?,
)
