package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.dag.ReteAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import borg.trikeshed.lib.size

/**
 * Minimal real bridge from the existing blackboard DAG event model into the
 * causal graph-node index. This is intentionally a narrow adapter: it indexes
 * NodePlanning events without claiming to implement the full Stage 12 fabric.
 */
fun CausalGraphNodeIndex.indexNodePlanning(event: BlackboardEvent.NodePlanning): Int {
    val coordinate = event.coordinate
    val node = causalGraphNode(
        nodeId = event.nodeId,
        opId = "node-planning",
        opVersion = "${coordinate.className}.${coordinate.methodName}@${coordinate.bytecodeOffset}",
        parentNodeIds = emptyList(),
        inputFingerprint = "board=${event.boardId};overlays=${event.overlays.size}",
        blackboard = blackboardContext(
            id = event.boardId,
            provenance = provenance(
                source = "blackboard-dag",
                timestamp = event.timestamp,
                transformations = listOf("NodePlanning")
            ),
            tags = mapOf(
                "className" to coordinate.className,
                "methodName" to coordinate.methodName,
                "threadId" to coordinate.threadId.toString()
            )
        ),
        causalClock = event.timestamp,
        topoOrdinal = coordinate.bytecodeOffset,
        outputHash = null
    )
    return addOrGet(node)
}

/**
 * Ensure a [ReteAgent] is bound to this index, creating a default one if needed.
 * Returns the bound agent.
 */
fun CausalGraphNodeIndex.bindOrCreateAgent(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    agentId: String = "node-planning-agent",
    onFire: (ReteAgent.Fire) -> Unit = { /* no-op by default */ },
): ReteAgent.Agent {
    if (!hasBoundAgent()) {
        val agent = ReteAgent.run(
            rules = listOf(
                ReteAgent.ReteRule(
                    name = "node-planning",
                    predicate = { true },
                    transform = { n -> ReteAgent.Fire("node-planning", n.nodeId, n.causalKey, "planned", agentId) },
                ),
            ),
            scope = scope,
            agentId = agentId,
            onFire = onFire,
        )
        bindAgent(agent)
    }
    return boundAgent!!
}
