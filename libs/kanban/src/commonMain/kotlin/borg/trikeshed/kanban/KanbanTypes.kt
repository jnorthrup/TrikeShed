package borg.trikeshed.kanban

import borg.trikeshed.forge.KanbanBoard
import borg.trikeshed.forge.KanbanBoardId
import borg.trikeshed.forge.KanbanCard
import borg.trikeshed.forge.KanbanCardId
import borg.trikeshed.forge.KanbanColumn
import borg.trikeshed.forge.KanbanColumnId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Kanban task — the unit of work on the board.
 * Aligns with Forge KanbanCard + HermesKanbanTask fields.
 */
@Serializable
data class KanbanTask(
    val id: KanbanTaskId,
    val title: String,
    val body: String = "",
    val columnId: KanbanColumnId,
    val order: Int = 0,
    val assignee: String? = null,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dependencies: List<KanbanTaskId> = emptyList(),
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val workspaceKind: WorkspaceKind = WorkspaceKind.SCRATCH,
    val workspacePath: String? = null,
    val branchName: String? = null,
    val tenant: String? = null,
    val skills: List<String> = emptyList(),
    val modelOverride: String? = null,
    val maxRetries: Int? = null,
    val sessionId: String? = null,
    val workflowTemplateId: String? = null,
    val currentStepKey: String? = null,
)

@Serializable
data class KanbanTaskId(val value: String) {
    companion object {
        fun generate(): KanbanTaskId = KanbanTaskId("t_${java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)}")
        fun parse(s: String): KanbanTaskId = KanbanTaskId(s)
    }
}

@Serializable
enum class TaskPriority { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class WorkspaceKind { SCRATCH, DIR, WORKTREE }

/**
 * Kanban run — execution record for a task.
 */
@Serializable
data class KanbanRun(
    val id: KanbanRunId,
    val taskId: KanbanTaskId,
    val profile: String,
    val stepKey: String? = null,
    val status: String = "started",
    val outcome: String? = null,
    val summary: String? = null,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val workerPid: Int? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
)

@Serializable
data class KanbanRunId(val value: Int) {
    companion object {
        private var counter = 0
        fun next(): KanbanRunId = KanbanRunId(++counter)
    }
}

/**
 * Kanban comment — thread on a task.
 */
@Serializable
data class KanbanComment(
    val id: KanbanCommentId,
    val taskId: KanbanTaskId,
    val author: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class KanbanCommentId(val value: Int) {
    companion object {
        private var counter = 0
        fun next(): KanbanCommentId = KanbanCommentId(++counter)
    }
}

/**
 * Kanban event — for fanout/subscription.
 */
sealed interface KanbanEvent {
    @Serializable data class TaskCreated(val task: KanbanTask) : KanbanEvent
    @Serializable data class TaskDeleted(val taskId: KanbanTaskId) : KanbanEvent
    @Serializable data class TaskCompleted(val taskId: KanbanTaskId, val summary: String, val metadata: Map<String, String>) : KanbanEvent
    @Serializable data class TaskBlocked(val taskId: KanbanTaskId, val reason: String) : KanbanEvent
    @Serializable data class TaskUnblocked(val taskId: KanbanTaskId, val comment: String?) : KanbanEvent
    @Serializable data class TaskMoved(val taskId: KanbanTaskId, val fromColumn: KanbanColumnId, val toColumn: KanbanColumnId) : KanbanEvent
    @Serializable data class DependencyAdded(val blockerId: KanbanTaskId, val blockedId: KanbanTaskId) : KanbanEvent
    @Serializable data class DependencyRemoved(val blockerId: KanbanTaskId, val blockedId: KanbanTaskId) : KanbanEvent
    @Serializable data class CommentAdded(val comment: KanbanComment) : KanbanEvent
    @Serializable data class RunStarted(val run: KanbanRun) : KanbanEvent
    @Serializable data class RunCompleted(val run: KanbanRun) : KanbanEvent
}

/**
 * Kanban store interface — CCEK element owning the board state.
 */
interface KanbanStore : AutoCloseable {
    val events: SharedFlow<KanbanEvent>

    suspend fun create(task: KanbanTask): KanbanTask
    suspend fun get(id: KanbanTaskId): KanbanTask?
    suspend fun update(task: KanbanTask): KanbanTask
    suspend fun delete(id: KanbanTaskId): Boolean
    suspend fun list(columnId: KanbanColumnId? = null, assignee: String? = null): List<KanbanTask>
    suspend fun search(query: String): List<KanbanTask>

    // Runs
    suspend fun createRun(run: KanbanRun): KanbanRun
    suspend fun getRun(id: KanbanRunId): KanbanRun?
    suspend fun updateRun(run: KanbanRun): KanbanRun
    suspend fun listRuns(taskId: KanbanTaskId): List<KanbanRun>

    // Comments
    suspend fun addComment(comment: KanbanComment): KanbanComment
    suspend fun listComments(taskId: KanbanTaskId): List<KanbanComment>

    // Dependencies
    suspend fun addDependency(blocker: KanbanTaskId, blocked: KanbanTaskId)
    suspend fun removeDependency(blocker: KanbanTaskId, blocked: KanbanTaskId)
    suspend fun getDependencies(taskId: KanbanTaskId): List<KanbanTaskId>
    suspend fun getDependents(taskId: KanbanTaskId): List<KanbanTaskId>

    // Columns
    suspend fun getColumns(): List<KanbanColumn>
    suspend fun addColumn(column: KanbanColumn): KanbanColumn

    // Board
    suspend fun getBoard(boardId: KanbanBoardId = KanbanBoardId("tshed")): KanbanBoard
}

/**
 * Create a KanbanStore implementation (SQLite-backed for JVM).
 */
fun kanbanStore(boardDir: Path, boardId: KanbanBoardId = KanbanBoardId("tshed")): KanbanStore =
    SqliteKanbanStore(boardDir, boardId)

/**
 * Board directory resolver using ~/.hermes/kanban/<boardId>/
 */
fun defaultBoardDir(boardId: KanbanBoardId = KanbanBoardId("tshed")): Path =
    java.nio.file.Paths.get(System.getProperty("user.home"), ".hermes", "kanban", boardId.value).toAbsolutePath().normalize()