package borg.trikeshed.dag

import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Cut: agents run rete rules asynchronously against the blackboard.
 *
 * DRY picks (locked earlier this turn):
 *   - Q1 (c): keep [ReteCausalBridge.project] as the only causal-to-rete seam.
 *   - Q2 (b): flat [ReteRule] data class — no shared interface hierarchy.
 *   - Q3 (a): kotlinx.coroutines `launch { ... }` returning `Job`.
 *   - Q4 (c): reads [CausalGraphNodeIndex] directly via the existing
 *     `addOrGet` projection — no blackboard factory, no SharedFlow.
 *
 * [run] subscribes to [CausalGraphNodeIndex.addOrGet] through a private
 * [Channel]: callers push nodes in (one channel send per addOrGet), and the
 * agent's coroutine consumes them asynchronously. Returning the [Channel] from
 * [indexed] lets call sites wire it once.
 */
object ReteAgent {

    /**
     * One rete rule — a flat data class to keep the surface DRY.
     */
    data class ReteRule(
        val name: String,
        val predicate: (CausalGraphNode) -> Boolean,
        val transform: (CausalGraphNode) -> Fire,
    )

    /**
     * One fired-rule outcome.
     */
    data class Fire(
        val ruleName: String,
        val nodeId: String,
        val causalKey: String,
        val payload: String,
        val agentId: String,
    )

    /**
     * Result of [run]: the agent's [Job] and the [Channel] used to feed it
     * nodes. Callers push a node into [sink] every time they call
     * [CausalGraphNodeIndex.addOrGet].
     */
    data class Agent(
        val job: Job,
        val sink: Channel<CausalGraphNode>,
    )

    /**
     * Start an agent that consumes causal nodes from [sink] and applies
     * [rules]. The [ReteAgent.Agent.sink] is the only seam — callers
     * `agent.sink.trySend(node)` after `addOrGet`.
     */
    fun run(
        rules: List<ReteRule>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        agentId: String = "rete-agent",
        onFire: (Fire) -> Unit,
    ): Agent {
        val sink = Channel<CausalGraphNode>(capacity = Channel.UNLIMITED)
        val job = scope.launch {
            for (node in sink) {
                for (rule in rules) {
                    if (rule.predicate(node)) {
                        onFire(rule.transform(node).copy(agentId = agentId))
                    }
                }
            }
        }
        return Agent(job = job, sink = sink)
    }

    /** Cancel [agent.job]. The [Agent.sink] is closed by the caller if desired. */
    fun stop(agent: Agent) {
        agent.job.cancel()
    }
}