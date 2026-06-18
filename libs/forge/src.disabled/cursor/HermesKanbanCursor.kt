package borg.trikeshed.forge.cursor

import borg.trikeshed.forge.CardPriority
import borg.trikeshed.forge.KanbanBoard
import borg.trikeshed.forge.KanbanBoardId
import borg.trikeshed.forge.KanbanCard
import borg.trikeshed.forge.KanbanCardId
import borg.trikeshed.forge.KanbanColumn
import borg.trikeshed.forge.KanbanColumnId
import borg.trikeshed.forge.toCascadeGraph
import borg.trikeshed.forge.toDot
import borg.trikeshed.forge.toMermaid
import borg.trikeshed.userspace.reactor.HermesKanbanCli
import borg.trikeshed.userspace.reactor.HermesKanbanTask
import borg.trikeshed.userspace.reactor.HermesKanbanTaskDetail
import kotlinx.coroutines.runBlocking

/**
 * CLI-backed projection of a Hermes kanban board into Forge Kanban types.
 *
 * This keeps Hermes as the durable operational truth while Forge owns the typed,
 * visual projection surface.
 */
class HermesKanbanCursor(
    private val cli: HermesKanbanCli = HermesKanbanCli(),
) {

    fun loadBoard(boardId: String = cli.board): KanbanBoard = runBlocking {
        val client = clientFor(boardId)
        val details = client.list().map { task -> client.show(task.id) }
        val columnIds = standardColumns.associate { it.status to it.id }
        KanbanBoard(
            id = KanbanBoardId(boardId),
            name = "Hermes Kanban ($boardId)",
            columns = standardColumns.mapIndexed { index, spec ->
                KanbanColumn(
                    id = spec.id,
                    name = spec.name,
                    order = index,
                )
            },
            cards = details.map { detail -> detail.toKanbanCard(columnIds) },
            metadata = mapOf(
                "board" to boardId,
                "source" to "hermes-cli",
            ),
        )
    }

    fun getCards(status: String? = null): List<KanbanCard> = runBlocking {
        val columnIds = standardColumns.associate { it.status to it.id }
        cli.list(status).map { task -> cli.show(task.id).toKanbanCard(columnIds) }
    }

    fun getDependencies(cardId: KanbanCardId): List<KanbanCardId> = runBlocking {
        cli.show(cardId.value).parents.map(::KanbanCardId)
    }

    fun getRuns(cardId: KanbanCardId): List<CardRun> = runBlocking {
        cli.show(cardId.value).runs.map { run ->
            CardRun(
                id = run.id,
                profile = run.profile,
                status = run.status,
                outcome = run.outcome,
                summary = run.summary,
                startedAt = toMillis(run.startedAt),
                endedAt = run.endedAt?.let(::toMillis),
            )
        }
    }

    fun getComments(cardId: KanbanCardId): List<CardComment> = runBlocking {
        cli.show(cardId.value).comments.map { comment ->
            CardComment(
                id = comment.id ?: -1,
                taskId = cardId,
                author = comment.author,
                body = comment.body,
                createdAt = comment.createdAt?.let(::toMillis) ?: 0L,
            )
        }
    }

    fun toCascadeGraph(board: KanbanBoard) = board.toCascadeGraph()

    fun renderMermaid(board: KanbanBoard): String = board.toMermaid()

    fun renderDot(board: KanbanBoard): String = board.toDot()

    private fun clientFor(boardId: String): HermesKanbanCli =
        if (boardId == cli.board) cli else cli.withBoard(boardId)

    private fun HermesKanbanTaskDetail.toKanbanCard(columnIds: Map<String, KanbanColumnId>): KanbanCard {
        val task = task
        val createdAtMs = toMillis(task.createdAt)
        val updatedAtMs = lastTimestamp(task)
        return KanbanCard(
            id = KanbanCardId(task.id),
            title = task.title,
            description = task.body ?: latestSummary.orEmpty(),
            columnId = columnIds[task.status] ?: KanbanColumnId(task.status),
            assignee = task.assignee,
            priority = mapPriority(task.priority),
            dependencies = parents.map(::KanbanCardId),
            tags = buildSet {
                add(task.status)
                task.assignee?.let { add("assignee:$it") }
            },
            metadata = metadataFor(task),
            createdAt = createdAtMs,
            updatedAt = updatedAtMs,
        )
    }

    private fun HermesKanbanTaskDetail.metadataFor(task: HermesKanbanTask): Map<String, String> = buildMap {
        task.createdBy?.let { put("createdBy", it) }
        task.tenant?.let { put("tenant", it) }
        task.workspaceKind?.let { put("workspaceKind", it) }
        task.workspacePath?.let { put("workspacePath", it) }
        task.branchName?.let { put("branchName", it) }
        task.sessionId?.let { put("sessionId", it) }
        task.workflowTemplateId?.let { put("workflowTemplateId", it) }
        task.currentStepKey?.let { put("currentStepKey", it) }
        latestSummary?.let { put("latestSummary", it) }
        put("priority.raw", task.priority.toString())
        put("comments.count", comments.size.toString())
        put("events.count", events.size.toString())
        put("runs.count", runs.size.toString())
        put("children.count", children.size.toString())
        if (task.skills.isNotEmpty()) {
            put("skills", task.skills.joinToString(","))
        }
    }

    private fun mapPriority(priority: Int): CardPriority = when {
        priority >= 100 -> CardPriority.CRITICAL
        priority > 0 -> CardPriority.HIGH
        priority < 0 -> CardPriority.LOW
        else -> CardPriority.MEDIUM
    }

    private fun lastTimestamp(task: HermesKanbanTask): Long = when {
        task.completedAt != null -> toMillis(task.completedAt)
        task.startedAt != null -> toMillis(task.startedAt)
        else -> toMillis(task.createdAt)
    }

    private fun toMillis(epochSeconds: Long?): Long = when {
        epochSeconds == null -> 0L
        epochSeconds >= 100_000_000_000L -> epochSeconds
        else -> epochSeconds * 1000L
    }

    private data class ColumnSpec(
        val status: String,
        val id: KanbanColumnId,
        val name: String,
    )

    private companion object {
        val standardColumns = listOf(
            ColumnSpec("triage", KanbanColumnId("triage"), "Triage"),
            ColumnSpec("todo", KanbanColumnId("todo"), "To Do"),
            ColumnSpec("ready", KanbanColumnId("ready"), "Ready"),
            ColumnSpec("running", KanbanColumnId("running"), "Running"),
            ColumnSpec("review", KanbanColumnId("review"), "Review"),
            ColumnSpec("blocked", KanbanColumnId("blocked"), "Blocked"),
            ColumnSpec("scheduled", KanbanColumnId("scheduled"), "Scheduled"),
            ColumnSpec("done", KanbanColumnId("done"), "Done"),
            ColumnSpec("archived", KanbanColumnId("archived"), "Archived"),
        )
    }
}

fun openHermesKanban(boardId: String = "tshed"): KanbanBoard = HermesKanbanCursor().loadBoard(boardId)

fun hermesKanbanMermaid(board: KanbanBoard): String = HermesKanbanCursor().renderMermaid(board)

fun hermesKanbanDot(board: KanbanBoard): String = HermesKanbanCursor().renderDot(board)

data class CardRun(
    val id: Int,
    val profile: String,
    val status: String,
    val outcome: String?,
    val summary: String?,
    val startedAt: Long,
    val endedAt: Long?,
)

data class CardComment(
    val id: Int,
    val taskId: KanbanCardId,
    val author: String,
    val body: String,
    val createdAt: Long,
)