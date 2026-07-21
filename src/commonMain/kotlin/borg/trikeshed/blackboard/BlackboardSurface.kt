package borg.trikeshed.blackboard

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.lcnc.isam.LcncEntity
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.forge.causalKey
import borg.trikeshed.forge.facet
import borg.trikeshed.forge.lane

/**
 * One row of the visual blackboard.
 *
 * Intentionally a value-shaped projection: the UI consumes `card_id`, `lane`,
 * `phase`, `facet`, `provenance`, `causalKey`, `lcncKind` as familiar metaphors.
 */
@kotlinx.serialization.Serializable
data class BlackboardSurfaceRow(
    val cardId: String,
    val lane: String,
    val phase: String,
    val facet: String,
    val provenance: String?,
    val causalKey: String,
    val lcncKind: String,
)

/**
 * Lcnc entity contribution to the visual blackboard.
 *
 * Lcnc-entity-first ordering: every entity produces one row. Causal graph
 * nodes attach to rows by `causalKey` so the forge projection can render
 * causal edges on top of lcnc cards.
 */

/**
 * Visual blackboard surface.
 *
 * Joins lcnc entities (lcnc-entity-first) with causal graph nodes and the
 * canonical causal preflight board. The result is a deterministic
 * `Cursor`-shaped row set the UI can render without learning a new model.
 */
class BlackboardSurface private constructor(
    val rows: List<BlackboardSurfaceRow>,
    val board: KanbanBoard,
) {

    /** Cursor over the projected rows so UI code can read it via the canonical algebra. */
    fun asCursor(): Cursor = rows.size j { i -> rowToRowVec(rows[i]) }

    fun cards(): List<KanbanCard> = board.cards

    fun columns(): List<KanbanColumn> = board.columns

    companion object {
        private val SURFACE_COLUMNS: List<Pair<String, IOMemento>> = listOf(
            "card_id" to IOMemento.IoString,
            "lane" to IOMemento.IoString,
            "phase" to IOMemento.IoString,
            "facet" to IOMemento.IoString,
            "provenance" to IOMemento.IoString,
            "causalKey" to IOMemento.IoString,
            "lcncKind" to IOMemento.IoString,
        )

        fun project(
            blackboardId: String,
            index: CausalGraphNodeIndex,
            entities: List<LcncEntity>,
            context: BlackboardContext = blackboardContext(
                id = blackboardId,
                provenance = provenance(
                    source = "blackboard-surface",
                    timestamp = 0L,
                    transformations = listOf("BlackboardSurface.project"),
                ),
            ),
        ): BlackboardSurface {
            val positions: Series<Int> = index.byBlackboard(blackboardId)
            val nodeByKey: Map<String, CausalGraphNode> = (0 until positions.size)
                .map { index[positions[it]] }
                .associateBy { it.causalKey }

            val readyLane = KanbanColumnId("col-causal-ready")
            val blockedLane = KanbanColumnId("col-causal-blocked")
            val agenticLane = KanbanColumnId("col-agentic")

            val validEntities = entities.filterIsInstance<LcncBlock>()
            val orderedEntities = validEntities.sortedWith(
                compareBy({ it.lane ?: "" }, { it.facet ?: "" }, { it.id })
            )

            val rows = orderedEntities.map { entity ->
                val causalKey = entity.causalKey
                val node = causalKey?.let { nodeByKey[it] }
                val laneId = resolveLane(entity, node)
                val phase = when (laneId) {
                    readyLane -> "pre-agent"
                    blockedLane -> "pre-agent"
                    agenticLane -> "agent"
                    else -> "pre-agent"
                }
                BlackboardSurfaceRow(
                    cardId = entity.id,
                    lane = laneId.value,
                    phase = phase,
                    facet = entity.facet ?: "",
                    provenance = context.provenance?.source,
                    causalKey = causalKey ?: node?.causalKey ?: "lcnc:${entity.id}",
                    lcncKind = entity.type,
                )
            }

            val cards = rows.mapIndexed { order, row ->
                val priority = when (KanbanColumnId(row.lane)) {
                    agenticLane -> CardPriority.CRITICAL
                    readyLane -> CardPriority.HIGH
                    else -> CardPriority.MEDIUM
                }
                KanbanCard(
                    id = KanbanCardId(row.cardId),
                    title = rows[order].cardId,
                    description = "lcnc:${rows[order].lcncKind}",
                    columnId = KanbanColumnId(row.lane),
                    order = order,
                    priority = priority,
                    tags = setOf("lcnc-entity", rows[order].lcncKind),
                    metadata = mapOf(
                        "facet" to row.facet,
                        "phase" to row.phase,
                        "causalKey" to row.causalKey,
                    ),
                )
            }

            val board = KanbanBoard(
                id = KanbanBoardId(blackboardId),
                name = "Blackboard surface: $blackboardId",
                columns = listOf(
                    KanbanColumn(readyLane, "Causal Ready", 0, metadata = mapOf("phase" to "pre-agent")),
                    KanbanColumn(blockedLane, "Causal Blocked", 1, metadata = mapOf("phase" to "pre-agent")),
                    KanbanColumn(agenticLane, "Agentic Work", 2, metadata = mapOf("phase" to "agent")),
                ),
                cards = cards,
                metadata = mapOf("blackboardId" to blackboardId, "source" to "BlackboardSurface"),
            )
            return BlackboardSurface(rows, board)
        }

        /** Project a persisted Confix source envelope into the cursor surface. */
        fun project(
            blackboardId: String,
            index: CausalGraphNodeIndex,
            document: ConfixDoc,
            entities: List<LcncEntity>,
        ): BlackboardSurface {
            val source = document.value("sourcePath")?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?: "confix"
            val contentId = document.value("contentId")?.toString().orEmpty()
            return project(
                blackboardId = blackboardId,
                index = index,
                entities = entities,
                context = blackboardContext(
                    id = blackboardId,
                    provenance = provenance(
                        source = source,
                        timestamp = 0L,
                        transformations = listOf("confixDoc", "BlackboardSurface.project"),
                    ),
                    tags = mapOf("sourceContentId" to contentId),
                ),
            )
        }

        private fun resolveLane(
            entity: LcncBlock,
            node: CausalGraphNode?,
        ): KanbanColumnId = when {
            node == null -> KanbanColumnId(entity.lane ?: "")
            node.parentNodeIds.isEmpty() -> KanbanColumnId("col-causal-ready")
            else -> KanbanColumnId("col-causal-blocked")
        }

        private fun rowToRowVec(row: BlackboardSurfaceRow): RowVec {
            val values = listOf<Any?>(
                row.cardId,
                row.lane,
                row.phase,
                row.facet,
                row.provenance,
                row.causalKey,
                row.lcncKind,
            )
            return SURFACE_COLUMNS.size j { col: Int ->
                values[col] j { ColumnMeta(SURFACE_COLUMNS[col].first, SURFACE_COLUMNS[col].second) }
            }
        }
    }
}