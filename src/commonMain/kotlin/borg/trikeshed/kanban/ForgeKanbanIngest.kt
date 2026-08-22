package borg.trikeshed.kanban

import borg.trikeshed.cursor.BlackboardContext
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
import borg.trikeshed.lib.cascade.Shape
import borg.trikeshed.lib.cascade.key
import borg.trikeshed.lib.cascade.shape
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries

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
    /** Line alphabet. Gate symbols first (the plan grammar reads only 6/7/W); the generic tail grows with new formats. */
    private val planRules = listOf(
        '_' to Regex("^$"), '6' to Regex("^6\\. Work packages$"), '7' to Regex("^7\\. "), 'S' to Regex("^\\d+\\.\\s"),
        'W' to packageHeader, 'D' to Regex("^Depends on:"),
        'H' to Regex("^#{1,6}\\s"), 'B' to Regex("^[-*+]\\s"), 'T' to Regex("^\\|"), 'C' to Regex("^```"), 'J' to Regex("^[{}\\[\\]]|[;{}]$|^\"[^\"]+\":"),
    )
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

    /**
     * Persist the markdown envelope, then reduce it and assert the derived facts into
     * [workingMemory].  The default is a fresh memory, which reproduces the historical
     * behaviour of an assertion pass whose memory is immediately discarded; callers that
     * care about the facts pass their own.
     */
    suspend fun persistMarkdown(
        userId: String,
        markdownPath: String,
        workingMemory: ReteWorkingMemory = ReteWorkingMemory(),
    ): ForgeKanbanReduction {
        val markdown = borg.trikeshed.common.Files.readString(markdownPath)
        requirePlanShape(markdown, markdownPath)
        val source = ForgeBoardPersistence.source(userId, markdown, markdownPath)
        ForgeBoardPersistence.persist(source).getOrThrow()
        return reduce(source, workingMemory)
    }

    // ── Shape gate: classify lines, run-length collapse, match the key BEFORE anything touches disk. ──

    /** One symbol per line: `6` work-packages header, `7` next section, `S` numbered section, `W` package header, `D` depends, `_` blank, `P` anything else. */
    fun classifyPlanLine(line: String): Char = line.trim().let { t -> planRules.firstOrNull { it.second.containsMatchIn(t) }?.first ?: 'P' }

    /** The run-length facet of a plan source — runs with spans, addressable back into the lines. */
    fun planRuns(markdown: String): Shape<Char> = markdown.lines().toSeries().shape(::classifyPlanLine)

    /** The run-length key of a plan source, e.g. `SP_6_WP_WDP_SP`. */
    fun planShape(markdown: String): String = planRuns(markdown).key.toList().joinToString("")

    fun isPlan(markdown: String): Boolean = planShapeGrammar.matches(planShape(markdown))

    /** A plan has a `6` followed by at least one `W` before any `7`. Everything [parseWorkPackages] needs, and nothing it doesn't. */
    private val planShapeGrammar = Regex("^[^67]*6[^7W]*W[^7]*(7.*)?$")

    fun requirePlanShape(markdown: String, sourcePath: String) {
        val shape = planShape(markdown)
        require(isPlan(markdown)) {
            "$sourcePath is not a kanban plan — shape `$shape`, want `…6…W…[7…]`; refusing to persist"
        }
    }

    suspend fun persistArchive(
        userId: String,
        archive: borg.trikeshed.lib.Series<Any?>,
        pipeline: borg.trikeshed.treedoc.TreeDocPipeline,
        workingMemory: ReteWorkingMemory = ReteWorkingMemory(),
    ): ForgeKanbanReduction {
        val documents = archive.b(borg.trikeshed.treedoc.TreeDocK.Documents.ordinal) as borg.trikeshed.cursor.Cursor
        val allMarkdown = StringBuilder()
        for (i in 0 until documents.a) {
            val bytes = pipeline.restoreDocument(archive, i)
            allMarkdown.append(bytes.decodeToString()).append("\n\n")
        }
        val source = ForgeBoardPersistence.source(userId, allMarkdown.toString(), "archive:${(archive.b(borg.trikeshed.treedoc.TreeDocK.ArchiveId.ordinal) as borg.trikeshed.job.ContentId).value}")
        return reduce(source, workingMemory)
    }

    suspend fun load(
        userId: String,
        workingMemory: ReteWorkingMemory = ReteWorkingMemory(),
    ): ForgeKanbanReduction =
        reduce(ForgeBoardPersistence.load(userId).getOrThrow(), workingMemory)

    /**
     * Read-only sibling of [load] for render/report paths that only want the projection
     * and cannot suspend (e.g. the `@JsExport` wasm shell entry point).  It performs no
     * working-memory assertion, so it stays a pure function of the persisted envelope.
     */
    fun loadProjection(userId: String): ForgeKanbanReduction =
        project(ForgeBoardPersistence.load(userId).getOrThrow())

    /**
     * Browser-safe fallback — builds a minimal reduction entirely in memory
     * without touching disk.  Used when [loadProjection] and [persistMarkdown] both fail
     * (e.g. browser bundle where require('fs') is unavailable).  Like [loadProjection]
     * it is a pure projection, so render paths stay non-suspending.
     */
    fun fallbackReduction(): ForgeKanbanReduction {
        val source = ForgeKanbanSource(
            version = 1,
            userId = "forge",
            title = "Forge local-first workspace",
            sourcePath = "",
            description = """
                TARGET: Forge local-first workspace

                6. Work packages

                G0 — Root-only Gradle graph
                Make the default Gradle graph describe the root project.

                F0 — Widget gallery + blackboard
                Gallery catalog and 3D blackboard view as sections of the workspace.

                C1 — Browser + JVM targets
                Kotlin/JS browser bundle and JVM Compose Desktop shell.

                7.
            """.trimIndent(),
            contentId = "fallback",
        )
        return project(source)
    }

    /**
     * Reduce [source] and assert the derived facts into [workingMemory].
     *
     * RGA N3 note: this path still bypasses `JobSupervisor` — the JobCommand bridge is a
     * sibling concern and is deliberately untouched here.
     */
    suspend fun reduce(
        source: ForgeKanbanSource,
        workingMemory: ReteWorkingMemory = ReteWorkingMemory(),
    ): ForgeKanbanReduction =
        project(source).also { assertFacts(workingMemory, it.reteFacts) }

    /** Pure deterministic projection — no suspension and no working-memory writes. */
    fun project(source: ForgeKanbanSource): ForgeKanbanReduction {
        require(!Regex("(?i)ignore all previous instructions").containsMatchIn(source.description)) { "Prompt injection detected" }
        val tasks = parseWorkPackages(source.description)
        validateTasks(tasks)

        val boardId = "forge-${source.userId}"
        val context = blackboardContext(
            id = boardId,
            provenance = provenance(
                source = source.sourcePath,
                timestamp = 0L,
                transformations = listOf("ForgeKanbanIngest.project"),
            ),
            tags = mapOf("sourceContentId" to source.contentId),
        )

        val dependencyFacts = buildDependencyFacts(tasks, boardId, context)
        val provisionalTaskFacts = buildProvisionalTaskFacts(tasks, boardId, context)

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

        val parentIdsByTask = computeParentIdsByTask(parents)
        val childIdsByTask = computeChildIdsByTask(children)

        val cards = buildCards(tasks, parentIdsByTask, source)
        val taskFacts = updateTaskFactsWithStatus(cards, provisionalTaskFacts)

        val causalNodes = buildCausalNodes(tasks, parentIdsByTask, context)
        val correlations = buildCorrelations(tasks, parentIdsByTask, childIdsByTask, causalNodes)

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

    private fun validateTasks(tasks: List<SourceTask>) {
        require(tasks.isNotEmpty()) { "no work packages found in source description" }
        require(tasks.map { it.id }.toSet().size == tasks.size) { "duplicate work package id" }
        val knownIds = tasks.map { it.id }.toSet()
        tasks.forEach { task ->
            val unknown = task.parentIds.filterNot { it in knownIds }
            require(unknown.isEmpty()) { "${task.id} has unknown parents: $unknown" }
        }
    }

    private fun buildDependencyFacts(tasks: List<SourceTask>, boardId: String, context: BlackboardContext): List<ReteStoredFact> {
        return tasks.flatMap { child ->
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
    }

    private fun buildProvisionalTaskFacts(tasks: List<SourceTask>, boardId: String, context: BlackboardContext): List<ReteStoredFact> {
        return tasks.map { task ->
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
    }

    private fun computeParentIdsByTask(parents: ReteBetaMemory): Map<String, List<String>> {
        return parents.tokens().groupBy { it.left.fields["taskId"] as String }
            .mapValues { (_, tokens) -> tokens.map { it.right.fields["parentId"] as String }.sorted() }
    }

    private fun computeChildIdsByTask(children: ReteBetaMemory): Map<String, List<String>> {
        return children.tokens().groupBy { it.left.fields["taskId"] as String }
            .mapValues { (_, tokens) -> tokens.map { it.right.fields["childId"] as String }.sorted() }
    }

    private fun buildCards(tasks: List<SourceTask>, parentIdsByTask: Map<String, List<String>>, source: ForgeKanbanSource): List<KanbanCard> {
        return tasks.mapIndexed { order, task ->
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
    }

    private fun updateTaskFactsWithStatus(cards: List<KanbanCard>, provisionalTaskFacts: List<ReteStoredFact>): List<ReteStoredFact> {
        return cards.map { card ->
            val fields = provisionalTaskFacts.first { it.fields["taskId"] == card.id.value }.fields +
                mapOf("status" to card.columnId.value)
            provisionalTaskFacts.first { it.fields["taskId"] == card.id.value }.copy(fields = fields)
        }
    }

    private suspend fun assertFacts(workingMemory: ReteWorkingMemory, facts: List<ReteStoredFact>) {
        facts.forEach { fact ->
            workingMemory.assert(fact.factId, fact.fields, fact.versionCid, fact.board)
        }
    }

    private fun buildCausalNodes(tasks: List<SourceTask>, parentIdsByTask: Map<String, List<String>>, context: BlackboardContext): List<CausalGraphNode> {
        return tasks.mapIndexed { order, task ->
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
    }

    private fun buildCorrelations(
        tasks: List<SourceTask>,
        parentIdsByTask: Map<String, List<String>>,
        childIdsByTask: Map<String, List<String>>,
        causalNodes: List<CausalGraphNode>
    ): List<ForgeKanbanCorrelation> {
        val causalById = causalNodes.associateBy { it.nodeId }
        return tasks.map { task ->
            ForgeKanbanCorrelation(
                taskId = task.id,
                parentIds = parentIdsByTask[task.id].orEmpty(),
                childIds = childIdsByTask[task.id].orEmpty(),
                ready = parentIdsByTask[task.id].isNullOrEmpty(),
                causalKey = causalById.getValue(task.id).causalKey,
            )
        }
    }

    private fun parseWorkPackages(markdown: String): List<SourceTask> {
        val lines = markdown.lines()
        val cls = lines.map(::classifyPlanLine)
        val start = cls.indexOf('6')
        require(start >= 0) { "source description has no '6. Work packages' section" }
        val end = (start + 1 until lines.size).firstOrNull { cls[it] == '7' } ?: lines.size
        val headers = (start + 1 until end).filter { cls[it] == 'W' }.map { it to packageHeader.matchEntire(lines[it].trim())!! }
        return headers.mapIndexed { position, (lineIndex, match) ->
            val next = headers.getOrNull(position + 1)?.first ?: end
            val bodyLines = lines.subList(lineIndex, next)
            val dependsLine = bodyLines.indices.firstOrNull { cls[lineIndex + it] == 'D' }?.let(bodyLines::get)
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