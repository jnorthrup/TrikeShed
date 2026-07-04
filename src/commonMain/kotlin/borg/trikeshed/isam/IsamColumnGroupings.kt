package borg.trikeshed.isam

import borg.trikeshed.lib.*
import borg.trikeshed.lib.seriesOf

/**
 * ISAM Column Groupings — column-oriented data for user graph queries.
 * 
 * Stage 13: ISAM Column Groupings
 * 
 * User graph queries resolve into ISAM column-oriented groupings per consuming method.
 * 
 * @see TODO.md Stage 13
 */

// ==================== CONSUMING METHOD ====================

/**
 * Consuming method identifier — identifies the method that consumes graph data.
 */
data class ConsumingMethod(
    val className: String,
    val methodName: String,
    val descriptor: String
) {
    val id: String = "$className.$methodName${descriptor.take(20)}"
    
    companion object {
        val QUERY_BOARD = ConsumingMethod(
            className = "borg/trikeshed/forge/KanbanQuery",
            methodName = "queryBoard",
            descriptor = "(Ljava/lang/String;)Lorg/trikeshed/cursor/Cursor;"
        )
        
        val QUERY_CARDS = ConsumingMethod(
            className = "borg/trikeshed/forge/KanbanQuery",
            methodName = "queryCards",
            descriptor = "(Ljava/lang/String;)LORG/TRIKESHED/CURSOR/CURSOR;"
        )
        
        val QUERY_OVERLAYS = ConsumingMethod(
            className = "borg/trikeshed/forge/OverlayQuery",
            methodName = "queryOverlays",
            descriptor = "(Ljava/lang/String;)LORG/TRIKESHED/CURSOR/CURSOR;"
        )
        
        val QUERY_FACETS = ConsumingMethod(
            className = "borg/trikeshed/forge/FacetQuery",
            methodName = "queryFacets",
            descriptor = "(Ljava/lang/String;)LORG/TRIKESHED/CURSOR/CURSOR;"
        )
        
        val QUERY_DAG = ConsumingMethod(
            className = "borg/trikeshed/dag/DagQuery",
            methodName = "queryDag",
            descriptor = "(LORG/TRIKESHED/DAG/DAGCOORDINATE;)LORG/TRIKESHED/CURSOR/CURSOR;"
        )
    }
}

// ==================== ISAM COLUMN ====================

/**
 * ISAM column — a column in an ISAM record.
 */
sealed class IsamColumn {
    abstract val name: String
    abstract val type: IsamColumnType
    
    data class StringColumn(override val name: String, val value: String) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.STRING
    }
    
    data class IntColumn(override val name: String, val value: Int) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.INT
    }
    
    data class LongColumn(override val name: String, val value: Long) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.LONG
    }
    
    data class BoolColumn(override val name: String, val value: Boolean) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.BOOL
    }
    
    data class SeriesColumn(override val name: String, val value: Series<*>) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.SERIES
    }
    
    data class NullableColumn<T : Any>(override val name: String, val value: T?) : IsamColumn() {
        override val type: IsamColumnType = IsamColumnType.NULLABLE
    }
}

/**
 * Type of ISAM column.
 */
enum class IsamColumnType {
    STRING,
    INT,
    LONG,
    BOOL,
    SERIES,
    NULLABLE
}

// ==================== ISAM RECORD ====================

/**
 * ISAM record — a record in an ISAM index.
 */
data class IsamRecord(
    val recordId: String,
    val columns: Series<IsamColumn>
)

// ==================== ISAM COLUMN GROUPING ====================

/**
 * ISAM column grouping — columns grouped for a specific consuming method.
 */
sealed class IsamColumnGrouping {
    abstract val groupingId: String
    abstract val consumingMethod: ConsumingMethod
    abstract val records: Series<IsamRecord>
    abstract val primaryKey: String
}

// ==================== KANBAN COLUMN GROUPING ====================

/**
 * Column grouping for Kanban board columns.
 */
class KanbanColumnGrouping(
    override val groupingId: String,
    val boardId: String,
    val columns: Series<KanbanColumnRecord>
) : IsamColumnGrouping() {
    override val consumingMethod: ConsumingMethod = ConsumingMethod.QUERY_BOARD
    override val records: Series<IsamRecord> by lazy {
        columns.size j { i: Int -> IsamRecord(columns[i].columnId, columns[i].toColumns()) }
    }
    override val primaryKey: String = "columnId"
}

/**
 * Kanban column record.
 */
data class KanbanColumnRecord(
    val columnId: String,
    val columnName: String,
    val position: Int,
    val cardCount: Int,
    val color: String
) {
    fun toColumns(): Series<IsamColumn> = listOf(
        IsamColumn.StringColumn("columnId", columnId),
        IsamColumn.StringColumn("columnName", columnName),
        IsamColumn.IntColumn("position", position),
        IsamColumn.IntColumn("cardCount", cardCount),
        IsamColumn.StringColumn("color", color)
    ).toSeries()
}

// ==================== KANBAN CARD GROUPING ====================

/**
 * Column grouping for Kanban cards.
 */
class KanbanCardGrouping(
    override val groupingId: String,
    val boardId: String,
    val cards: Series<KanbanCardRecord>
) : IsamColumnGrouping() {
    override val consumingMethod: ConsumingMethod = ConsumingMethod.QUERY_CARDS
    override val records: Series<IsamRecord> by lazy {
        cards.size j { i: Int -> IsamRecord(cards[i].cardId, cards[i].toColumns()) }
    }
    override val primaryKey: String = "cardId"
}

/**
 * Kanban card record.
 */
data class KanbanCardRecord(
    val cardId: String,
    val columnId: String,
    val label: String,
    val description: String,
    val position: Int,
    val assignee: String?,
    val dueDate: Long?
) {
    fun toColumns(): Series<IsamColumn> = listOf(
        IsamColumn.StringColumn("cardId", cardId),
        IsamColumn.StringColumn("columnId", columnId),
        IsamColumn.StringColumn("label", label),
        IsamColumn.StringColumn("description", description),
        IsamColumn.IntColumn("position", position),
        IsamColumn.NullableColumn("assignee", assignee),
        IsamColumn.NullableColumn("dueDate", dueDate)
    ).toSeries()
}

// ==================== CASCADE GRAPH NODE GROUPING ====================

/**
 * Column grouping for Cascade graph nodes.
 */
class CascadeNodeGrouping(
    override val groupingId: String,
    val boardId: String,
    val nodes: Series<CascadeNodeRecord>
) : IsamColumnGrouping() {
    override val consumingMethod: ConsumingMethod = ConsumingMethod.QUERY_DAG
    override val records: Series<IsamRecord> by lazy {
        nodes.size j { i: Int -> IsamRecord(nodes[i].nodeId, nodes[i].toColumns()) }
    }
    override val primaryKey: String = "nodeId"
}

/**
 * Cascade node record.
 */
data class CascadeNodeRecord(
    val nodeId: String,
    val parentNodeId: String?,
    val label: String,
    val dagCoordinate: String,
    val facets: Series<Any>
) {
    fun toColumns(): Series<IsamColumn> = listOf(
        IsamColumn.StringColumn("nodeId", nodeId),
        IsamColumn.NullableColumn("parentNodeId", parentNodeId),
        IsamColumn.StringColumn("label", label),
        IsamColumn.StringColumn("dagCoordinate", dagCoordinate),
        IsamColumn.SeriesColumn("facets", facets as Series<*>)
    ).toSeries()
}

// ==================== OVERLAY ACL ROW GROUPING ====================

/**
 * Column grouping for overlay ACL rows.
 */
class OverlayAclGrouping(
    override val groupingId: String,
    val boardId: String,
    val aclRows: Series<OverlayAclRecord>
) : IsamColumnGrouping() {
    override val consumingMethod: ConsumingMethod = ConsumingMethod.QUERY_OVERLAYS
    override val records: Series<IsamRecord> by lazy {
        aclRows.size j { i: Int -> IsamRecord(aclRows[i].overlayId, aclRows[i].toColumns()) }
    }
    override val primaryKey: String = "overlayId"
}

/**
 * Overlay ACL record.
 */
data class OverlayAclRecord(
    val overlayId: String,
    val nodeId: String,
    val label: String,
    val principal: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canDelete: Boolean
) {
    fun toColumns(): Series<IsamColumn> = listOf(
        IsamColumn.StringColumn("overlayId", overlayId),
        IsamColumn.StringColumn("nodeId", nodeId),
        IsamColumn.StringColumn("label", label),
        IsamColumn.StringColumn("principal", principal),
        IsamColumn.BoolColumn("canRead", canRead),
        IsamColumn.BoolColumn("canWrite", canWrite),
        IsamColumn.BoolColumn("canDelete", canDelete)
    ).toSeries()
}

// ==================== LCNC FACET ROW GROUPING ====================

/**
 * Column grouping for LCNC facet rows.
 */
class LcncFacetGrouping(
    override val groupingId: String,
    val boardId: String,
    val facetRows: Series<LcncFacetRecord>
) : IsamColumnGrouping() {
    override val consumingMethod: ConsumingMethod = ConsumingMethod.QUERY_FACETS
    override val records: Series<IsamRecord> by lazy {
        facetRows.size j { i: Int -> IsamRecord(facetRows[i].facetId, facetRows[i].toColumns()) }
    }
    override val primaryKey: String = "facetId"
}

/**
 * LCNC facet record.
 */
data class LcncFacetRecord(
    val facetId: String,
    val cursorId: String,
    val facetKey: String,
    val facetType: String,
    val facetValue: Any?,
    val dagCoordinate: String
) {
    fun toColumns(): Series<IsamColumn> = listOf(
        IsamColumn.StringColumn("facetId", facetId),
        IsamColumn.StringColumn("cursorId", cursorId),
        IsamColumn.StringColumn("facetKey", facetKey),
        IsamColumn.StringColumn("facetType", facetType),
        IsamColumn.SeriesColumn("facetValue", listOf(facetValue).toSeries()),
        IsamColumn.StringColumn("dagCoordinate", dagCoordinate)
    ).toSeries()
}

// ==================== RENDERABLE OUTPUT ====================

/**
 * Renderable query output format.
 */
data class RenderableOutput(
    val nodeId: String,
    val label: String,
    val consumingMethod: String,
    val isamColumns: Series<IsamColumn>,
    val facets: Series<Any>,
    val aclStatus: String
) {
    fun render(): String = buildString {
        appendLine("Node: $nodeId")
        appendLine("Label: $label")
        appendLine("Consuming Method: $consumingMethod")
        appendLine("ISAM Columns:")
        isamColumns.forEach { col ->
            val colValue = when (col) {
                is IsamColumn.StringColumn -> col.value
                is IsamColumn.IntColumn -> col.value
                is IsamColumn.LongColumn -> col.value
                is IsamColumn.BoolColumn -> col.value
                is IsamColumn.SeriesColumn -> col.value.toString()
                is IsamColumn.NullableColumn<*> -> col.value
            }
            appendLine("  ${col.name}: $colValue")
        }
        appendLine("Facets: ${facets.toList().joinToString(", ")}")
        appendLine("ACL Status: $aclStatus")
    }
}

// ==================== FACTORY ====================

/**
 * Factory for ISAM column groupings.
 */
object IsamGroupings {
    /**
     * Create a Kanban column grouping.
     */
    fun createKanbanColumnGrouping(
        boardId: String,
        columns: List<KanbanColumnRecord>
    ): KanbanColumnGrouping = KanbanColumnGrouping(
        groupingId = "board:$boardId:columns",
        boardId = boardId,
        columns = columns.toSeries()
    )
    
    /**
     * Create a Kanban card grouping.
     */
    fun createKanbanCardGrouping(
        boardId: String,
        cards: List<KanbanCardRecord>
    ): KanbanCardGrouping = KanbanCardGrouping(
        groupingId = "board:$boardId:cards",
        boardId = boardId,
        cards = cards.toSeries()
    )
    
    /**
     * Create an overlay ACL grouping.
     */
    fun createOverlayAclGrouping(
        boardId: String,
        aclRows: List<OverlayAclRecord>
    ): OverlayAclGrouping = OverlayAclGrouping(
        groupingId = "board:$boardId:acl",
        boardId = boardId,
        aclRows = aclRows.toSeries()
    )
    
    /**
     * Create an LCNC facet grouping.
     */
    fun createLcncFacetGrouping(
        boardId: String,
        facets: List<LcncFacetRecord>
    ): LcncFacetGrouping = LcncFacetGrouping(
        groupingId = "board:$boardId:facets",
        boardId = boardId,
        facetRows = facets.toSeries()
    )
}
