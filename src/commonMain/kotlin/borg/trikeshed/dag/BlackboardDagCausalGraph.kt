package borg.trikeshed.dag

import borg.trikeshed.parse.confix.value
import borg.trikeshed.dag.BlackboardEvent
import borg.trikeshed.job.ContentId.Companion.of

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.cursor.provenance
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.project
import borg.trikeshed.job.Lens
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
        parentNodeIds = emptyList(), // These will become CIDs eventually.
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

/**
 * CAS-backed causal graph logic.
 * Every causal node is a Confix doc `{kind: "causal-node", causalKey: "...", deps: [CID...], payload: {...}}`.
 */
class CasBackedCausalGraph(val casStore: CasStore) {
    var rootCid: ContentId? = null
        private set

    fun snapshotRoot(cid: ContentId) {
        rootCid = cid
    }

    fun submitNode(
        causalKey: String,
        deps: List<ContentId>,
        payload: String
    ): ContentId {
        val depsJson = deps.joinToString(",") { "\"${it.value}\"" }
        val docJson = """{"kind":"causal-node", "causalKey":"$causalKey", "deps":[$depsJson], "payload":$payload}"""
        val newCid = casStore.put(docJson.encodeToByteArray())
        snapshotRoot(newCid)
        return newCid
    }

    fun traverse(startCid: ContentId): List<ContentId> {
        val result = mutableListOf<ContentId>()
        val visited = mutableSetOf<ContentId>()

        fun recurse(cid: ContentId) {
            if (cid in visited) return
            visited.add(cid)

            val lens = project(cid, casStore)
            if (lens is Lens.CausalNode) {
                result.add(cid)

                // Parse deps array manually since we don't have a typed mapping right here
                val depsRaw = lens.doc.value("deps")
                if (depsRaw is Iterable<*>) {
                    depsRaw.forEach {
                        if (it is String) {
                            recurse(ContentId.of(it.encodeToByteArray()))
                        }
                    }
                }
            }
        }

        recurse(startCid)
        return result
    }
}
