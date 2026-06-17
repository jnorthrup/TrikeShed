package borg.trikeshed.kanban

import borg.trikeshed.kanban.collective.Collective
import borg.trikeshed.kanban.collective.DeviceMesh
import borg.trikeshed.kanban.collective.MeshAxis
import borg.trikeshed.kanban.dispatch.HermesDispatch
import borg.trikeshed.kanban.env.EnvConfig
import borg.trikeshed.kanban.env.ModelMuxEnvConfig
import borg.trikeshed.context.CompletableJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path

/**
 * KanbanEngine — the main CCEK element composing the full kanban system.
 * 
 * Architecture:
 *   EnvConfig (reads ~/.hermes/.env + per-profile .env)
 *       ↓ provides ModelMuxEnvConfig
 *   ModelMux (selects provider/model via keymux quotas)
 *       ↓ provides selected model
 *   KanbanStore (SQLite/ISAM local state)
 *       ↓ owns task lifecycle
 *   HermesDispatch (executes tasks via hermes CLI)
 *       ↓ emits events
 *   Collective (AllGather/ReduceScatter/AllReduce/AllToAll on Cursor)
 *       ↓ distributed compute algebra
 *   Events (SharedFlow for fanout)
 */
class KanbanEngine(
    private val parentJob: CompletableJob? = null,
    private val boardDir: Path = defaultBoardDir(),
    private val boardId: KanbanBoardId = KanbanBoardId("tshed"),
) : AutoCloseable {

    private val job = SupervisorJob(parentJob)
    private val envConfig = EnvConfig(job)
    private val modelMuxConfig: ModelMuxEnvConfig? = null // lazy init
    private val store: KanbanStore = kanbanStore(boardDir, boardId)
    private val dispatch: HermesDispatch = HermesDispatch(job, modelMuxConfig)
    private val collective: Collective? = null // lazy init with mesh

    /** Initialize the engine — load env, open store, connect mesh. */
    suspend fun initialize(profile: String? = null): KanbanEngine {
        envConfig.load(profile)
        // modelMuxConfig = envConfig.getModelMuxConfig()
        // collective = Collective(meshForProfile(profile))
        return this
    }

    /** Get the environment config */
    fun env(): EnvConfig = envConfig

    /** Get the modelmux config (after initialize) */
    fun modelMux(): ModelMuxEnvConfig? = modelMuxConfig

    /** Get the kanban store */
    fun store(): KanbanStore = store

    /** Get the hermes dispatch */
    fun dispatch(): HermesDispatch = dispatch

    /** Get the collective algebra (after initialize with mesh) */
    fun collective(): Collective? = collective

    /** Get events flow for fanout */
    val events = store.events

    /** Create a triage task and dispatch to Hermes */
    suspend fun triage(
        title: String,
        body: String = "",
        assignee: String = "kanban-worker",
        skills: List<String> = emptyList(),
        modelOverride: String? = null,
    ): KanbanTask {
        val idempotencyKey = "triage_${title.hashCode()}_${System.currentTimeMillis()}"
        val task = dispatch.createTriageCard(
            title = title,
            body = body,
            assignee = assignee,
            idempotencyKey = idempotencyKey,
            skills = skills,
        )
        if (modelOverride != null) {
            // Update task with model override
            val updated = task.copy(modelOverride = modelOverride)
            store.update(updated)
        }
        return task
    }

    /** Execute a task by ID */
    suspend fun execute(taskId: KanbanTaskId): KanbanRun {
        val task = store.get(taskId) ?: error("Task not found: $taskId")
        val run = dispatch.dispatch(task)
        store.createRun(run)
        return run
    }

    /** Complete a task */
    suspend fun complete(taskId: KanbanTaskId, summary: String, metadata: Map<String, String> = emptyMap()) {
        dispatch.completeTask(taskId, summary, metadata)
        val task = store.get(taskId) ?: return
        val updated = task.copy(
            columnId = KanbanColumnId("done"),
            metadata = task.metadata + metadata,
        )
        store.update(updated)
    }

    /** Block a task */
    suspend fun block(taskId: KanbanTaskId, reason: String) {
        dispatch.blockTask(taskId, reason)
        val task = store.get(taskId) ?: return
        val updated = task.copy(columnId = KanbanColumnId("blocked"))
        store.update(updated)
    }

    /** Get board projection */
    suspend fun board(): KanbanBoard = store.getBoard(boardId)

    override fun close() {
        store.close()
        job.cancel()
    }
}

/**
 * Create a DeviceMesh for a profile.
 * Maps profile → device topology for collective ops.
 */
fun meshForProfile(profile: String?): DeviceMesh {
    val devices = (0..7).toList() // 8 devices default
    return when (profile) {
        "chimera" -> DeviceMesh(
            axes = listOf(
                MeshAxis.x(4, devices),
                MeshAxis.y(2, devices),
            ),
            devices = devices,
        )
        "large", "ultra" -> DeviceMesh(
            axes = listOf(
                MeshAxis.x(8, devices),
            ),
            devices = devices,
        )
        else -> DeviceMesh(
            axes = listOf(MeshAxis.x(1, devices)),
            devices = devices,
        )
    }
}

/**
 * CLI entry point for running the kanban engine.
 */
fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
    val engine = KanbanEngine().initialize(args.firstOrNull())
    println("KanbanEngine initialized for profile: ${args.firstOrNull() ?: "default"}")
    println("Board: ${engine.board()}")
    
    // Example: create a task
    val task = engine.triage(
        title = "Test collective algebra",
        body = "Verify AllGather/ReduceScatter/AllReduce/AllToAll on Cursor",
        assignee = "kanban-worker",
        skills = listOf("collective", "cursor"),
    )
    println("Created task: ${task.id.value}")
    
    // Example: execute it
    val run = engine.execute(task.id)
    println("Run: ${run.id.value} ${run.status}")
    
    // Complete
    engine.complete(task.id, "Collective algebra verified")
    println("Completed")
    
    engine.close()
}