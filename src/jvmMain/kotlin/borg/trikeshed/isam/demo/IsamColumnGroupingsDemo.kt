package borg.trikeshed.isam.demo

import borg.trikeshed.isam.*
import borg.trikeshed.lib.toSeries

/**
 * ISAM Column Groupings Demo — demonstrates ISAM column-oriented data for user graph queries.
 */
object IsamColumnGroupingsDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("📊 ISAM Column Groupings Demo Starting...")
        println()

        // 1. Show consuming methods
        println("1. Consuming Methods")
        println("   QUERY_BOARD: ${ConsumingMethod.QUERY_BOARD.id}")
        println("   QUERY_CARDS: ${ConsumingMethod.QUERY_CARDS.id}")
        println("   QUERY_OVERLAYS: ${ConsumingMethod.QUERY_OVERLAYS.id}")
        println("   QUERY_FACETS: ${ConsumingMethod.QUERY_FACETS.id}")
        println("   QUERY_DAG: ${ConsumingMethod.QUERY_DAG.id}")
        println()

        // 2. Show ISAM column types
        println("2. ISAM Column Types")
        println("   ${IsamColumnType.entries.joinToString(", ")}")
        println()

        // 3. Create Kanban column record
        println("3. Kanban Column Record")
        val colRecord = KanbanColumnRecord("col-1", "To Do", 0, 3, "#FF5733")
        println("   columnId: ${colRecord.columnId}")
        println("   columnName: ${colRecord.columnName}")
        println("   position: ${colRecord.position}")
        println("   cardCount: ${colRecord.cardCount}")
        println("   color: ${colRecord.color}")
        println()

        // 4. Create Kanban card record
        println("4. Kanban Card Record")
        val cardRecord = KanbanCardRecord(
            "card-1", "col-1", "Implement feature", 
            "Add new feature", 0, "Alice", null
        )
        println("   cardId: ${cardRecord.cardId}")
        println("   columnId: ${cardRecord.columnId}")
        println("   label: ${cardRecord.label}")
        println("   assignee: ${cardRecord.assignee}")
        println()

        // 5. Create overlay ACL record
        println("5. Overlay ACL Record")
        val aclRecord = OverlayAclRecord(
            "overlay-1", "node-1", "Feature Label",
            "user:alice", true, true, false
        )
        println("   overlayId: ${aclRecord.overlayId}")
        println("   nodeId: ${aclRecord.nodeId}")
        println("   principal: ${aclRecord.principal}")
        println("   canRead: ${aclRecord.canRead}")
        println()

        // 6. Create LCNC facet record
        println("6. LCNC Facet Record")
        val facetRecord = LcncFacetRecord(
            "facet-1", "cursor-1", "logic",
            "LOGIC", "compute()", "dag:1"
        )
        println("   facetId: ${facetRecord.facetId}")
        println("   cursorId: ${facetRecord.cursorId}")
        println("   facetKey: ${facetRecord.facetKey}")
        println("   facetType: ${facetRecord.facetType}")
        println()

        // 7. Show factory methods
        println("7. Factory Methods")
        println("   IsamGroupings.createKanbanColumnGrouping(...)")
        println("   IsamGroupings.createKanbanCardGrouping(...)")
        println("   IsamGroupings.createOverlayAclGrouping(...)")
        println("   IsamGroupings.createLcncFacetGrouping(...)")
        println()

        // 8. Show query engine contracts
        println("8. Query Engine Contracts")
        println("   - executeQuery: run a query")
        println("   - getColumnGroupings: get groupings for a board")
        println("   - render: render query result")
        println()

        // 9. Renderable query output
        println("9. Renderable Query Output")
        val rendered = RenderableOutput(
            nodeId = "node-1",
            label = "Feature Card",
            consumingMethod = ConsumingMethod.QUERY_CARDS.id,
            isamColumns = cardRecord.toColumns(),
            facets = listOf("logic", "layout:flex-row", "dag:1").toSeries(),
            aclStatus = "ALLOW user:alice"
        )
        println(rendered.render())

        println("✅ ISAM Column Groupings Demo Complete!")
    }
}
