package borg.trikeshed.kanban

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.dag.BetaJoin
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteBetaMemory
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.dag.ReteWorkingMemory
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.job.ContentId

data class ForgeKanbanCorrelation(
    val taskId: String,
    val parentIds: List<String>,
    val childIds: List<String>,
    val ready: Boolean,
    val causalKey: String,
)

data class ForgeKanbanReduction(
    val source: ForgeKanbanSource,
    val board: KanbanBoard,
    val causalNodes: List<CausalGraphNode>,
    val reteFacts: List<ReteStoredFact>,
    val correlations: List<ForgeKanbanCorrelation>,
)

private data class SourceTask(
    val id: String,
    val title: String,
    val body: String,
    val parentIds: List<String>,
)

/** Deterministic ingest reducer. It performs no model call and stores no derived board dump. */
object ForgeKanbanIngest {
    private val packageHeader = Regex("^([A-Z][0-9]+)\\s+[—-]\\s+(.+)$")
    private val dependencyId = Regex("\\b[A-Z][0-9]+\\b")

    private val columns = listOf(
        KanbanColumn(KanbanColumnId("triage"), "Triage", 0),
        KanbanColumn(KanbanColumnId("todo"), "Todo", 1),
        KanbanColumn(KanbanColumnId("ready"), "Ready", 2),
        KanbanColumn(KanbanColumnId("running"), "Running", 3, wipLimit = 3),
        KanbanColumn(KanbanColumnId("blocked"), "Blocked", 4),
        KanbanColumn(KanbanColumnId("done"), "Done", 5),
        KanbanColumn(KanbanColumnId("archived"), "Archived", 6),
    )

    fun persistMarkdown(userId: String, markdownPath: String): ForgeKanbanReduction {
        val markdown = borg.trikeshed.common.Files.readString(markdownPath)
        val source = ForgeBoardPersistence.source(userId, markdown, markdownPath)
        ForgeBoardPersistence.persist(source).getOrThrow()
        return reduce(source)
    }

    fun load(userId: String): ForgeKanbanReduction =
        reduce(ForgeBoardPersistence.load(userId).getOrThrow())

    /**
     * Browser-safe fallback — builds a minimal reduction entirely in memory
     * without touching disk.  Used when [load] and [persistMarkdown] both fail
     * (e.g. browser bundle where require('fs') is unavailable).
     */
    fun fallbackReduction(): ForgeKanbanReduction {
        val source = ForgeKanbanSource(
            version = 1,
            userId = "forge",
            title = "Forge local-first workspace",
            sourcePath = "",
            description = """
                TARGET: Forge local-first workspace

                G0 — Root-only Gradle graph
                Make the default Gradle graph describe the root project.

                F0 — Widget gallery + blackboard
                Gallery catalog and 3D blackboard view as sections of the workspace.

                C1 — Browser + JVM targets
                Kotlin/JS browser bundle and JVM Compose Desktop shell.
            """.trimIndent(),
            contentId = "fallback",
        )
        return reduce(source)
    }

    fun reduce(source: ForgeKanbanSource): ForgeKanbanReduction {
        val tasks = parseWorkPackages(source.description)
        require(tasks.isNotEmpty()) { "no work packages found in source description" }
        require(tasks.map { it.id }.toSet().size == tasks.size) { "duplicate work package id" }

        val knownIds = tasks.map { it.id }.toSet()
        tasks.forEach { task ->
            val unknown = task.parentIds.filterNot { it in knownIds }
            require(unknown.isEmpty()) { "${task.id} has unknown parents: $unknown" }
        }

        val boardId = "forge-${source.userId}"
        val context = blackboardContext(
            id = boardId,
            provenance = provenance(
                source = source.sourcePath,
                timestamp = 0L,
                transformations = listOf("ForgeKanbanIngest.reduce"),
            ),
            tags = mapOf("sourceContentId" to source.contentId),
        )

        val dependencyFacts = tasks.flatMap { child ->
            child.parentIds.map { parentId ->
                ReteStoredFact(
                    factId = FactId(boardId, "link:$parentId->${child.id}"),
                    fields = mapOf(
                        "kind" to "link",
                        "parentId" to parentId,
                        "childId" to child.id,
                    ),
                    versionCid = ContentId.of("$parentId->${child.id}".encodeToByteArray()),
                    board = context,
                )
            }
        }

        val provisionalTaskFacts = tasks.map { task ->
            ReteStoredFact(
                factId = FactId(boardId, "task:${task.id}"),
                fields = mapOf(
                    "kind" to "task",
                    "taskId" to task.id,
                    "title" to task.title,
                    "bodyContentId" to ContentId.of(task.body.encodeToByteArray()).value,
                ),
                versionCid = ContentId.of(task.body.encodeToByteArray()),
                board = context,
            )
        }

        val parents = ReteBetaMemory(BetaJoin("taskId", "childId"))
        val children = ReteBetaMemory(BetaJoin("taskId", "parentId"))
        provisionalTaskFacts.forEach {
            parents.acceptLeft(it)
            children.acceptLeft(it)
        }
        dependencyFacts.forEach {
            parents.acceptRight(it)
            children.acceptRight(it)
        }

        val parentIdsByTask = parents.tokens().groupBy { it.left.fields["taskId"] as String }
            .mapValues { (_, tokens) -> tokens.map { it.right.fields["parentId"] as String }.sorted() }
        val childIdsByTask = children.tokens().groupBy { it.left.fields["taskId"] as String }
            .mapValues { (_, tokens) -> tokens.map { it.right.fields["childId"] as String }.sorted() }

        val cards = tasks.mapIndexed { order, task ->
            val parentIds = parentIdsByTask[task.id].orEmpty()
            val status = if (parentIds.isEmpty()) "ready" else "todo"
            KanbanCard(
                id = KanbanCardId(task.id),
                title = "${task.id} — ${task.title}",
                description = task.body,
                columnId = KanbanColumnId(status),
                order = order,
                assignee = source.userId,
                priority = if (parentIds.isEmpty()) CardPriority.HIGH else CardPriority.MEDIUM,
                dependencies = parentIds.map(::KanbanCardId),
                tags = setOf("work-package", task.id),
                metadata = mapOf(
                    "sourceContentId" to source.contentId,
                    "bodyContentId" to ContentId.of(task.body.encodeToByteArray()).value,
                ),
                createdAt = 0L,
                updatedAt = 0L,
            )
        }

        val taskFacts = cards.map { card ->
            val fields = provisionalTaskFacts.first { it.fields["taskId"] == card.id.value }.fields +
                mapOf("status" to card.columnId.value)
            provisionalTaskFacts.first { it.fields["taskId"] == card.id.value }.copy(fields = fields)
        }
        val workingMemory = ReteWorkingMemory()
        (taskFacts + dependencyFacts).forEach { fact ->
            workingMemory.assert(fact.factId, fact.fields, fact.versionCid, fact.board)
        }

        val causalNodes = tasks.mapIndexed { order, task ->
            causalGraphNode(
                nodeId = task.id,
                opId = "kanban-work-package",
                opVersion = "forge-ingest-v1",
                parentNodeIds = parentIdsByTask[task.id].orEmpty(),
                inputFingerprint = ContentId.of(task.body.encodeToByteArray()).value,
                blackboard = context,
                causalClock = order.toLong(),
                topoOrdinal = order,
                outputHash = null,
            )
        }
        val causalById = causalNodes.associateBy { it.nodeId }
        val correlations = tasks.map { task ->
            ForgeKanbanCorrelation(
                taskId = task.id,
                parentIds = parentIdsByTask[task.id].orEmpty(),
                childIds = childIdsByTask[task.id].orEmpty(),
                ready = parentIdsByTask[task.id].isNullOrEmpty(),
                causalKey = causalById.getValue(task.id).causalKey,
            )
        }

        return ForgeKanbanReduction(
            source = source,
            board = KanbanBoard(
                id = KanbanBoardId(boardId),
                name = source.title,
                columns = columns,
                cards = cards,
                metadata = mapOf(
                    "sourcePath" to source.sourcePath,
                    "sourceContentId" to source.contentId,
                    "reducer" to "ForgeKanbanIngest/v1",
                ),
            ),
            causalNodes = causalNodes,
            reteFacts = taskFacts + dependencyFacts,
            correlations = correlations,
        )
    }

    private fun parseWorkPackages(markdown: String): List<SourceTask> {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trim() == "6. Work packages" }
        require(start >= 0) { "source description has no '6. Work packages' section" }
        val relativeEnd = lines.drop(start + 1).indexOfFirst { it.trim().startsWith("7. ") }
        val end = if (relativeEnd < 0) lines.size else start + 1 + relativeEnd

        val headers = (start + 1 until end).mapNotNull { index ->
            packageHeader.matchEntire(lines[index].trim())?.let { index to it }
        }
        return headers.mapIndexed { position, (lineIndex, match) ->
            val next = headers.getOrNull(position + 1)?.first ?: end
            val bodyLines = lines.subList(lineIndex, next)
            val dependsLine = bodyLines.firstOrNull { it.trim().startsWith("Depends on:") }
            val parents = dependsLine
                ?.let { dependencyId.findAll(it.substringAfter(':')).map { id -> id.value }.toList() }
                .orEmpty()
            SourceTask(
                id = match.groupValues[1],
                title = match.groupValues[2].trim(),
                body = bodyLines.joinToString("\n").trim(),
                parentIds = parents,
            )
        }
    }
}