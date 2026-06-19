package borg.trikeshed.forge

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import kotlin.jvm.JvmInline

@JvmInline
value class GraphLabelOverlayId(val value: String)

@JvmInline
value class GraphNodeId(val value: String)

@JvmInline
value class GraphLabel(val value: String)

@JvmInline
value class UserGraphQuery(val value: String)

@JvmInline
value class ConsumingMethodId(val value: String)

@JvmInline
value class IsamColumnName(val value: String)

@JvmInline
value class ForgePrincipalId(val value: String)

@JvmInline
value class ForgeIdentityProfileId(val value: String)

@JvmInline
value class GraphLabelFacetValue(val value: String)

enum class ForgeOverlayPrincipalKind {
    USER,
    AGENT,
}

enum class OverlayPermission {
    READ_LABEL,
    DRILLDOWN,
    QUERY_GROUP,
    EDIT_OVERLAY,
}

enum class GraphLabelFacetKind {
    LCNC,
    WTK_HINT,
    WTK_LAYOUT,
    DAG_COORDINATE,
}

typealias ForgeOverlayPrincipal = Join<ForgeOverlayPrincipalKind, ForgePrincipalId>
typealias OverlayPermissionSeries = Series<OverlayPermission>
typealias GraphOverlayAclEntry = Join<ForgeOverlayPrincipal, OverlayPermissionSeries>
typealias GraphOverlayAcl = Series<GraphOverlayAclEntry>
typealias IsamColumnSeries = Series<IsamColumnName>
typealias IsamColumnGrouping = Join<ConsumingMethodId, IsamColumnSeries>
typealias GraphLabelFacet = Join<GraphLabelFacetKind, GraphLabelFacetValue>
typealias GraphLabelFacetSeries = Series<GraphLabelFacet>
typealias OverlayConsumptionHandle = Join<IsamColumnGrouping, GraphLabelFacetSeries>
typealias GraphLabelTarget = Join<GraphNodeId, GraphLabel>
typealias GraphLabelOverlayMeta = Join<GraphLabelOverlayId, Join<GraphLabelTarget, OverlayConsumptionHandle>>
typealias GraphLabelOverlay = Join<GraphLabelOverlayMeta, GraphOverlayAcl>
typealias GraphLabelOverlaySeries = Series<GraphLabelOverlay>
typealias GraphLabelQueryMatch = Join<GraphLabelOverlay, OverlayConsumptionHandle>
typealias GraphLabelQueryResult = Series<GraphLabelQueryMatch>
typealias ForgeOverlayProfile = Join<ForgeIdentityProfileId, ForgeOverlayPrincipal>
typealias ForgeOverlayProfileSeries = Series<ForgeOverlayProfile>

object ForgeOverlayProfiles {
    val PLACEHOLDER_AGENT_CODEX: ForgeOverlayProfile =
        ForgeIdentityProfileId("placeholder.agent.codex") j AgentType.CODEX.asOverlayPrincipal()

    val PLACEHOLDER_AGENT_GENERIC: ForgeOverlayProfile =
        ForgeIdentityProfileId("placeholder.agent.generic") j AgentType.GENERIC.asOverlayPrincipal()

    val PLACEHOLDER_USER_OWNER: ForgeOverlayProfile =
        ForgeIdentityProfileId("placeholder.user.owner") j
            (ForgeOverlayPrincipalKind.USER j ForgePrincipalId("owner"))

    val defaults: ForgeOverlayProfileSeries =
        s_[PLACEHOLDER_AGENT_CODEX, PLACEHOLDER_AGENT_GENERIC, PLACEHOLDER_USER_OWNER]
}

fun ForgeUser.asOverlayPrincipal(): ForgeOverlayPrincipal =
    ForgeOverlayPrincipalKind.USER j ForgePrincipalId(id.value)

fun AgentType.asOverlayPrincipal(): ForgeOverlayPrincipal =
    ForgeOverlayPrincipalKind.AGENT j ForgePrincipalId(name)

fun graphOverlayAcl(
    principal: ForgeOverlayPrincipal,
    permissions: OverlayPermissionSeries = defaultOverlayPermissions(),
): GraphOverlayAcl = 1 j { principal j permissions }

fun placeholderGraphOverlayAcl(
    profiles: ForgeOverlayProfileSeries = ForgeOverlayProfiles.defaults,
): GraphOverlayAcl =
    profiles.size j { index ->
        val profile = profiles[index]
        profile.b j defaultOverlayPermissions()
    }

fun defaultOverlayPermissions(): OverlayPermissionSeries =
    s_[
        OverlayPermission.READ_LABEL,
        OverlayPermission.DRILLDOWN,
        OverlayPermission.QUERY_GROUP,
    ]

fun KanbanBoard.labelOverlays(
    principal: ForgeOverlayPrincipal,
    consumingMethod: ConsumingMethodId = ConsumingMethodId("KanbanBoard.toCascadeGraph"),
): GraphLabelOverlaySeries =
    toCascadeGraph().labelOverlaysWithAcl(graphOverlayAcl(principal), consumingMethod)

fun CascadeGraph.labelOverlays(
    principal: ForgeOverlayPrincipal,
    consumingMethod: ConsumingMethodId = ConsumingMethodId("CascadeGraph.render"),
): GraphLabelOverlaySeries {
    return labelOverlaysWithAcl(graphOverlayAcl(principal), consumingMethod)
}

fun KanbanBoard.labelOverlaysWithAcl(
    acl: GraphOverlayAcl,
    consumingMethod: ConsumingMethodId = ConsumingMethodId("KanbanBoard.toCascadeGraph"),
): GraphLabelOverlaySeries =
    toCascadeGraph().labelOverlaysWithAcl(acl, consumingMethod)

fun CascadeGraph.labelOverlaysWithAcl(
    acl: GraphOverlayAcl = placeholderGraphOverlayAcl(),
    consumingMethod: ConsumingMethodId = ConsumingMethodId("CascadeGraph.render"),
): GraphLabelOverlaySeries {
    val graph = this
    return graph.nodes.size j { index ->
        val node = graph.nodes[index]
        graphLabelOverlay(
            overlayId = GraphLabelOverlayId("${graph.cascadeId.value}.${node.id}.label"),
            nodeId = GraphNodeId(node.id),
            label = GraphLabel(node.label),
            handle = overlayConsumptionHandle(consumingMethod, graph.cascadeId, node),
            acl = acl,
        )
    }
}

fun GraphLabelOverlaySeries.visibleTo(principal: ForgeOverlayPrincipal): GraphLabelOverlaySeries =
    filterOverlays { overlay -> overlay.acl.allows(principal, OverlayPermission.READ_LABEL) }

fun GraphLabelOverlaySeries.resolveLabelQuery(
    query: UserGraphQuery,
    principal: ForgeOverlayPrincipal,
): GraphLabelQueryResult {
    val visible = filterOverlays { overlay ->
        overlay.acl.allows(principal, OverlayPermission.QUERY_GROUP) &&
            overlay.matches(query)
    }
    return visible.size j { index ->
        val overlay = visible[index]
        overlay j overlay.consumptionHandle
    }
}

fun KanbanBoard.toMermaidWithLabelOverlays(
    principal: ForgeOverlayPrincipal,
    consumingMethod: ConsumingMethodId = ConsumingMethodId("KanbanBoard.toMermaidWithLabelOverlays"),
): String {
    val graph = toCascadeGraph()
    val overlays = graph.labelOverlays(principal, consumingMethod).visibleTo(principal)
    return buildString {
        appendLine("graph LR")
        for (index in 0 until graph.nodes.size) {
            val node = graph.nodes[index]
            appendLine("  ${node.id.mermaidId()}[${node.id}]")
        }
        for (index in 0 until graph.edges.size) {
            val edge = graph.edges[index]
            appendLine("  ${edge.from.mermaidId()} -->|${edge.dataFlow}| ${edge.to.mermaidId()}")
        }
        for (index in 0 until overlays.size) {
            val overlay = overlays[index]
            val overlayId = overlay.overlayId.value.mermaidId()
            val targetId = overlay.nodeId.value.mermaidId()
            appendLine("  $overlayId[\"${overlay.label.value}\"] -. label .-> $targetId")
        }
    }
}

fun renderGraphLabelOverlayQuery(
    matches: GraphLabelQueryResult,
): String = buildString {
    appendLine("graph label overlay query")
    for (index in 0 until matches.size) {
        val match = matches[index]
        val overlay = match.a
        val grouping = match.b
        append("  ")
        append(overlay.nodeId.value)
        append(" -> ")
        append(overlay.label.value)
        append(" | method=")
        append(grouping.a.a.value)
        append(" | isam=")
        append(grouping.a.b.renderColumns())
        append(" | facets=")
        append(grouping.b.renderFacets())
        appendLine()
    }
}

fun forgeKanbanOverlayDemo(
    principal: ForgeOverlayPrincipal = AgentType.CODEX.asOverlayPrincipal(),
): String {
    val todo = KanbanColumnId("todo")
    val doing = KanbanColumnId("doing")
    val card = KanbanCardId("card-user-query")
    val board = KanbanBoard(
        id = KanbanBoardId("forge-overlay-demo"),
        name = "Forge overlay demo",
        columns = listOf(
            KanbanColumn(todo, "Todo", 0),
            KanbanColumn(doing, "Doing", 1),
        ),
        cards = listOf(
            KanbanCard(
                id = card,
                title = "Expose kanban labels",
                columnId = todo,
                assignee = principal.b.value,
                priority = CardPriority.HIGH,
            ),
        ),
    )
    val overlays = board.labelOverlaysWithAcl(placeholderGraphOverlayAcl())
    val query = overlays.resolveLabelQuery(UserGraphQuery("label"), principal)
    return buildString {
        appendLine(board.toMermaidWithLabelOverlays(principal))
        append(renderGraphLabelOverlayQuery(query))
    }
}

private val GraphLabelOverlay.overlayId: GraphLabelOverlayId get() = a.a
private val GraphLabelOverlay.target: GraphLabelTarget get() = a.b.a
private val GraphLabelOverlay.consumptionHandle: OverlayConsumptionHandle get() = a.b.b
private val GraphLabelOverlay.grouping: IsamColumnGrouping get() = consumptionHandle.a
private val GraphLabelOverlay.acl: GraphOverlayAcl get() = b
private val GraphLabelOverlay.nodeId: GraphNodeId get() = target.a
private val GraphLabelOverlay.label: GraphLabel get() = target.b

private fun graphLabelOverlay(
    overlayId: GraphLabelOverlayId,
    nodeId: GraphNodeId,
    label: GraphLabel,
    handle: OverlayConsumptionHandle,
    acl: GraphOverlayAcl,
): GraphLabelOverlay =
    (overlayId j ((nodeId j label) j handle)) j acl

private fun overlayConsumptionHandle(
    consumingMethod: ConsumingMethodId,
    graphId: CascadeId,
    node: CascadeNode,
): OverlayConsumptionHandle =
    (consumingMethod j nodeLabelColumns(node)) j labelFacets(
        consumingMethod = consumingMethod,
        graphId = graphId,
        node = node,
    )

private fun labelFacets(
    consumingMethod: ConsumingMethodId,
    graphId: CascadeId,
    node: CascadeNode,
): GraphLabelFacetSeries =
    s_[
        GraphLabelFacetKind.LCNC j GraphLabelFacetValue(
            "lcnc.facet.${consumingMethod.value}.${node.id}".safeHandle(),
        ),
        GraphLabelFacetKind.WTK_HINT j GraphLabelFacetValue("overlay.label.visible"),
        GraphLabelFacetKind.WTK_LAYOUT j GraphLabelFacetValue(wtkLayoutHint(node)),
        GraphLabelFacetKind.DAG_COORDINATE j GraphLabelFacetValue(
            "dag.${graphId.value}.${node.id}".safeHandle(),
        ),
    ]

private fun GraphOverlayAcl.allows(
    principal: ForgeOverlayPrincipal,
    permission: OverlayPermission,
): Boolean {
    for (entryIndex in 0 until size) {
        val entry = this[entryIndex]
        if (entry.a.samePrincipal(principal) && entry.b.containsPermission(permission)) return true
    }
    return false
}

private fun ForgeOverlayPrincipal.samePrincipal(other: ForgeOverlayPrincipal): Boolean =
    a == other.a && b == other.b

private fun OverlayPermissionSeries.containsPermission(permission: OverlayPermission): Boolean {
    for (index in 0 until size) if (this[index] == permission) return true
    return false
}

private fun GraphLabelOverlaySeries.filterOverlays(
    predicate: (GraphLabelOverlay) -> Boolean,
): GraphLabelOverlaySeries {
    val source = this
    var count = 0
    for (index in 0 until source.size) if (predicate(source[index])) count++
    return count j { visibleIndex ->
        var seen = 0
        for (sourceIndex in 0 until source.size) {
            val overlay = source[sourceIndex]
            if (predicate(overlay)) {
                if (seen == visibleIndex) return@j overlay
                seen++
            }
        }
        throw IndexOutOfBoundsException("overlay index $visibleIndex")
    }
}

private fun GraphLabelOverlay.matches(query: UserGraphQuery): Boolean {
    val needle = query.value
    if (needle.isBlank()) return true
    return nodeId.value.contains(needle, ignoreCase = true) ||
        label.value.contains(needle, ignoreCase = true)
}

private fun nodeLabelColumns(node: CascadeNode): IsamColumnSeries {
    val hasKanbanCardColumns = node.config.containsKey("column") ||
        node.config.containsKey("priority") ||
        node.config.containsKey("assignee")
    val hasKanbanColumnColumns = node.config.containsKey("wipLimit")
    return when {
        hasKanbanCardColumns -> s_[
            IsamColumnName("graph_node_id"),
            IsamColumnName("graph_label"),
            IsamColumnName("kanban_column"),
            IsamColumnName("kanban_priority"),
            IsamColumnName("kanban_assignee"),
        ]
        hasKanbanColumnColumns -> s_[
            IsamColumnName("graph_node_id"),
            IsamColumnName("graph_label"),
            IsamColumnName("kanban_wip_limit"),
        ]
        else -> s_[
            IsamColumnName("graph_node_id"),
            IsamColumnName("graph_label"),
            IsamColumnName("graph_stage_type"),
        ]
    }
}

private fun IsamColumnSeries.renderColumns(): String = buildString {
    for (index in 0 until size) {
        if (index > 0) append(",")
        append(this@renderColumns[index].value)
    }
}

private fun GraphLabelFacetSeries.renderFacets(): String = buildString {
    for (index in 0 until size) {
        val facet = this@renderFacets[index]
        if (index > 0) append(";")
        append(facet.a.name)
        append(":")
        append(facet.b.value)
    }
}

private fun wtkLayoutHint(node: CascadeNode): String =
    when {
        node.config.containsKey("column") -> "kanban.card"
        node.config.containsKey("wipLimit") -> "kanban.column"
        else -> "graph.node"
    }

private fun String.mermaidId(): String = buildString {
    for (char in this@mermaidId) {
        append(
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> char
                else -> '_'
            },
        )
    }
}

private fun String.safeHandle(): String = buildString {
    for (char in this@safeHandle) {
        append(
            when {
                char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> char
                else -> '_'
            },
        )
    }
}
