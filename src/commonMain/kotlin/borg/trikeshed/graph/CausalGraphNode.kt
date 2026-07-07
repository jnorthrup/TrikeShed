package borg.trikeshed.graph

import borg.trikeshed.collections.multiindex.MultiIndexContainer
import borg.trikeshed.collections.multiindex.MultiIndexK
import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of a causal graph node, for passing through the
 * HTML seed JSON to the JS graph renderer.
 */
@Serializable
data class CausalGraphNodeDTO(
    val nodeId: String,
    val opId: String,
    val opVersion: String,
    val parentNodeIds: List<String>,
    val causalKey: String,
    val topoOrdinal: Int,
    val causalClock: Long,
)

/**
 * Causal graph node row: deterministic construction identity plus blackboard context.
 *
 * The node is intentionally value-shaped. Graph identity comes from [causalKey],
 * while [nodeId] is an external label and [topoOrdinal] is a replay projection.
 */
data class CausalGraphNode(
    val nodeId: String,
    val opId: String,
    val opVersion: String,
    val parentNodeIds: List<String>,
    val inputFingerprint: String,
    val blackboard: BlackboardContext,
    val causalClock: Long,
    val topoOrdinal: Int,
    val outputHash: String?,
    val causalKey: String = causalGraphKey(opId, opVersion, parentNodeIds, inputFingerprint, blackboard.id, outputHash),
)

fun causalGraphNode(
    nodeId: String,
    opId: String,
    opVersion: String,
    parentNodeIds: List<String>,
    inputFingerprint: String,
    blackboard: BlackboardContext,
    causalClock: Long,
    topoOrdinal: Int,
    outputHash: String?,
): CausalGraphNode = CausalGraphNode(
    nodeId = nodeId,
    opId = opId,
    opVersion = opVersion,
    parentNodeIds = parentNodeIds,
    inputFingerprint = inputFingerprint,
    blackboard = blackboard,
    causalClock = causalClock,
    topoOrdinal = topoOrdinal,
    outputHash = outputHash,
)

fun causalGraphKey(
    opId: String,
    opVersion: String,
    parentNodeIds: List<String>,
    inputFingerprint: String,
    blackboardId: String,
    outputHash: String?,
): String = buildString {
    append(opId)
    append('\u001f')
    append(opVersion)
    append('\u001f')
    append(parentNodeIds.joinToString("\u001e"))
    append('\u001f')
    append(inputFingerprint)
    append('\u001f')
    append(blackboardId)
    append('\u001f')
    append(outputHash.orEmpty())
}

/** One backing node store with deterministic hash/order/range/blackboard facets. */
class CausalGraphNodeIndex {
    private val nodes = MultiIndexContainer<CausalGraphNode>()

    private val byCausalKey = MultiIndexK.ByHash { (it as CausalGraphNode).causalKey }
    private val byNodeId = MultiIndexK.ByHash { (it as CausalGraphNode).nodeId }
    private val byTopoOrdinal = MultiIndexK.ByOrder { (it as CausalGraphNode).topoOrdinal }
    private val byCausalClock = MultiIndexK.ByRange { (it as CausalGraphNode).causalClock }
    private val byBlackboardId = MultiIndexK.ByRange { (it as CausalGraphNode).blackboard.id }

    init {
        nodes.registerHash(byCausalKey)
        nodes.registerHash(byNodeId)
        nodes.registerOrder(byTopoOrdinal)
        nodes.registerOrder(byCausalClock)
        nodes.registerOrder(byBlackboardId)
    }

    val size: Int get() = nodes.size

    operator fun get(pos: Int): CausalGraphNode = nodes[pos]

    fun addOrGet(node: CausalGraphNode): Int {
        val existing = nodes.facet(byCausalKey)(node.causalKey)
        val pos = existing ?: nodes.add(node)
        boundAgent?.sink?.trySend(node)
        return pos
    }

    /** Bound agent that receives newly added causal nodes. */
    internal var boundAgent: ReteAgent.Agent? = null

    /** Bind an agent to receive all future nodes added via [addOrGet]. Replaces any existing agent. */
    fun bindAgent(agent: ReteAgent.Agent) {
        boundAgent = agent
    }

    /** Unbind the current agent, if any. */
    fun unbindAgent() {
        boundAgent = null
    }

    /** Returns true if an agent is currently bound. */
    fun hasBoundAgent(): Boolean = boundAgent != null

    fun byNodeId(nodeId: String): Int? = nodes.facet(byNodeId)(nodeId)

    fun byTopoOrdinal(): Series<Int> = nodes.facet(byTopoOrdinal)

    fun byCausalClockRange(lo: Long, hi: Long): Series<Int> = nodes.facet(byCausalClock)(lo, hi)

    fun byBlackboard(blackboardId: String): Series<Int> = nodes.facet(byBlackboardId)(blackboardId, blackboardId)

    fun asCursor(): Cursor = nodes.size j { row: Int -> nodes[row].asRowVec() }
}

private val CAUSAL_NODE_COLUMNS: List<Pair<String, IOMemento>> = listOf(
    "nodeId" to IOMemento.IoString,
    "opId" to IOMemento.IoString,
    "opVersion" to IOMemento.IoString,
    "parentNodeIds" to IOMemento.IoArray,
    "blackboardId" to IOMemento.IoString,
    "inputFingerprint" to IOMemento.IoString,
    "causalClock" to IOMemento.IoLong,
    "topoOrdinal" to IOMemento.IoInt,
    "outputHash" to IOMemento.IoString,
    "provenanceSource" to IOMemento.IoString,
)

fun CausalGraphNode.asRowVec(): RowVec {
    val values = listOf<Any?>(
        nodeId,
        opId,
        opVersion,
        parentNodeIds,
        blackboard.id,
        inputFingerprint,
        causalClock,
        topoOrdinal,
        outputHash,
        blackboard.provenance?.source,
    )
    return CAUSAL_NODE_COLUMNS.size j { col: Int ->
        values[col] j { ColumnMeta(CAUSAL_NODE_COLUMNS[col].first, CAUSAL_NODE_COLUMNS[col].second) }
    }
}
