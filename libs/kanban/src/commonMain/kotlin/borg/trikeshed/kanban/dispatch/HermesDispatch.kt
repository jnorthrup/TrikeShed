package borg.trikeshed.kanban.dispatch

import borg.trikeshed.kanban.KanbanTask
import borg.trikeshed.kanban.KanbanTaskId
import borg.trikeshed.kanban.KanbanRun
import borg.trikeshed.kanban.KanbanRunId
import borg.trikeshed.kanban.env.ModelMuxEnvConfig
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToString
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * HermesDispatch — CCEK element for dispatching tasks to Hermes CLI.
 * Reads ~/.hermes/.env, selects model via modelmux, executes hermes kanban commands.
 */
class HermesDispatch(
    parentJob: CompletableJob? = null,
    private val modelMuxConfig: ModelMuxEnvConfig? = null,
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private val hermesHome = Paths.get(System.getProperty("user.home"), ".hermes")
    private val kanbanBoard = "tshed"

    /**
     * Dispatch a task to Hermes for execution.
     * @param task The task to execute
     * @param profile Override profile (defaults to task.assignee or "kanban-worker")
     * @param workspaceKind Override workspace (defaults to task.workspaceKind)
     * @return The run record
     */
    suspend fun dispatch(
        task: KanbanTask,
        profile: String? = null,
        workspaceKind: String? = null,
    ): KanbanRun = withContext(Dispatchers.IO) {
        val run = KanbanRun(
            id = KanbanRunId.next(),
            taskId = task.id,
            profile = profile ?: task.assignee ?: "kanban-worker",
            stepKey = task.currentStepKey,
            status = "running",
            startedAt = System.currentTimeMillis(),
        )

        // Build hermes command
        val args = buildArgs(task, run.profile, workspaceKind ?: task.workspaceKind.name)
        val output = runHermes(args)

        // Parse result
        val completed = run.copy(
            status = "completed",
            outcome = "success",
            summary = output.take(500),
            endedAt = System.currentTimeMillis(),
        )
        completed
    }

    /**
     * Build hermes CLI arguments for task execution.
     */
    private fun buildArgs(task: KanbanTask, profile: String, workspaceKind: String): List<String> {
        val args = mutableListOf<String>(
            "hermes",
            "run",
            "--profile", profile,
            "--workspace", "dir:${workspaceRoot(task)}",
        )

        // Add model override if present
        task.modelOverride?.let { args += listOf("--model", it) }
        modelMuxConfig?.defaultModel?.let { args += listOf("--model", it) }

        // Add skills
        if (task.skills.isNotEmpty()) {
            args += listOf("--skills", task.skills.joinToString(","))
        }

        // Add task body as prompt
        val prompt = buildPrompt(task)
        args += listOf("--prompt", prompt)

        return args
    }

    private fun workspaceRoot(task: KanbanTask): String =
        task.workspacePath ?: Paths.get(".").toAbsolutePath().toString()

    private fun buildPrompt(task: KanbanTask): String = buildJsonObject {
        put("task_id", task.id.value)
        put("title", task.title)
        put("body", task.body)
        put("skills", task.skills)
        put("metadata", task.metadata)
        put("max_retries", task.maxRetries)
    }.toString()

    /**
     * Execute hermes command and return stdout.
     */
    private suspend fun runHermes(args: List<String>): String = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(args)
            .directory(hermesHome.toFile())
            .redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.readBytes().decodeToString()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("hermes ${args.joinToString(" ")} failed with exit $exitCode:\n$output")
        }
        output.trim()
    }

    /**
     * Create a triage card in Hermes kanban.
     */
    suspend fun createTriageCard(
        title: String,
        body: String = "",
        assignee: String = "kanban-worker",
        idempotencyKey: String,
        skills: List<String> = emptyList(),
    ): KanbanTask = withContext(Dispatchers.IO) {
        val args = mutableListOf<String>(
            "hermes", "kanban", "--board", kanbanBoard, "create", title,
            "--assignee", assignee,
            "--workspace", "dir:${workspaceRoot(KanbanTask(
                id = KanbanTaskId.generate(),
                title = title,
                body = body,
                assignee = assignee,
                skills = skills,
            ))}",
            "--idempotency-key", idempotencyKey,
            "--triage",
            "--json",
        )
        if (body.isNotBlank()) args += listOf("--body", body)
        if (skills.isNotEmpty()) args += listOf("--skills", skills.joinToString(","))

        val output = runHermes(args)
        val parsed = json.parseToJsonElement(output)
        val taskId = parsed.objectOrNull("task")?.stringOrNull("id")
            ?: parsed.stringOrNull("id")
            ?: error("Could not parse task id from hermes output: $output")

        KanbanTask(
            id = KanbanTaskId(taskId),
            title = title,
            body = body,
            assignee = assignee,
            skills = skills,
        )
    }

    /**
     * Complete a task in Hermes kanban.
     */
    suspend fun completeTask(
        taskId: KanbanTaskId,
        summary: String,
        metadata: Map<String, String> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        val args = mutableListOf<String>(
            "hermes", "kanban", "--board", kanbanBoard, "complete",
            "--summary", summary,
            taskId.value,
        )
        if (metadata.isNotEmpty()) {
            args += listOf("--metadata", json.encodeToString(metadata))
        }
        runHermes(args)
    }

    /**
     * Block a task in Hermes kanban.
     */
    suspend fun blockTask(taskId: KanbanTaskId, reason: String) = withContext(Dispatchers.IO) {
        runHermes(listOf("hermes", "kanban", "--board", kanbanBoard, "block", taskId.value, reason))
    }

    /**
     * List tasks from Hermes kanban.
     */
    suspend fun listTasks(status: String? = null): String = withContext(Dispatchers.IO) {
        val args = mutableListOf<String>("hermes", "kanban", "--board", kanbanBoard, "list", "--json")
        status?.let { args += listOf("--status", it) }
        runHermes(args)
    }

    override fun close() {
        // No resources to close
    }

    private fun JsonElement.stringOrNull(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonElement.objectOrNull(key: String): kotlinx.serialization.json.JsonObject? = this[key] as? kotlinx.serialization.json.JsonObject
}