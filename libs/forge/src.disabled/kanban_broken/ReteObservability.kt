@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

sealed interface ReteNode { val nodeId: String; val label: String; val metrics: ReteNodeMetrics }

@Serializable
data class ReteNodeMetrics(
    val factCount: Int = 0, val firingCount: Long = 0,
    val totalPropagationMs: Long = 0, val lastFiredMs: Long = 0,
) { val avgPropagationMs: Double get() = if (firingCount > 0) totalPropagationMs.toDouble() / firingCount else 0.0 }

@Serializable
data class AlphaNode(
    override val nodeId: String,
    override val label: String,
    val factType: FactType,
    val condition: String,
    val matchedFactIds: List<String> = emptyList(),
    override val metrics: ReteNodeMetrics = ReteNodeMetrics()
) : ReteNode

@Serializable
data class BetaNode(
    override val nodeId: String,
    override val label: String,
    val leftParentId: String,
    val rightParentId: String,
    val joinCondition: String,
    val matchedPairs: Int = 0,
    override val metrics: ReteNodeMetrics = ReteNodeMetrics()
) : ReteNode

@Serializable
data class ProductionNode(
    override val nodeId: String,
    override val label: String,
    val action: ProductionAction,
    val parentIds: List<String>,
    override val metrics: ReteNodeMetrics = ReteNodeMetrics()
) : ReteNode

enum class FactType { CARD, KEY, LEASE, METRIC, TICK }

@Serializable
enum class ProductionAction { SPAWN_AGENT, RECLAIM_LEASE, PROMOTE_CARD, BACKOFF_KEY, COMPLETE_CARD, BLOCK_CARD }

sealed interface ReteFact { val factId: String; val timestampMs: Long }

@Serializable
data class CardFact(
    override val factId: String,
    val cardId: String,
    val columnOrdinal: Int,
    val assignee: String?,
    val priority: Int,
    val dependencyCount: Int,
    override val timestampMs: Long = platformUtils.currentTimeMillis()
) : ReteFact

@Serializable
data class KeyFact(
    override val factId: String,
    val keyId: String,
    val provider: String,
    val isActive: Boolean,
    val leasedTo: String?,
    val leaseExpiresAt: Long,
    override val timestampMs: Long = platformUtils.currentTimeMillis()
) : ReteFact

@Serializable
data class TickFact(
    override val factId: String,
    val tickNumber: Int,
    val currentlyRunning: Int,
    val availableKeys: Int,
    val queueDepth: Int,
    override val timestampMs: Long = platformUtils.currentTimeMillis()
) : ReteFact

object ReteCompiler {
    fun compile(policy: DispatchPolicy): CompiledReteNetwork {
        val alphaNodes = mutableListOf<AlphaNode>()
        val betaNodes = mutableListOf<BetaNode>()
        val prodNodes = mutableListOf<ProductionNode>()
        alphaNodes.add(AlphaNode("a1", "card.column==TODO", FactType.CARD, "columnOrdinal == 0"))
        alphaNodes.add(AlphaNode("a2", "card.priority>=2", FactType.CARD, "priority >= 2"))
        alphaNodes.add(AlphaNode("a3", "key.active+free", FactType.KEY, "isActive && leasedTo == null"))
        alphaNodes.add(AlphaNode("a4", "tick.running<${policy.maxInProgress}", FactType.TICK, "currentlyRunning < ${policy.maxInProgress}"))
        betaNodes.add(BetaNode("b1", "TODO+priority", "a1", "a2", "same cardId"))
        betaNodes.add(BetaNode("b2", "TODO-card+free-key", "b1", "a3", "card needs key"))
        betaNodes.add(BetaNode("b3", "spawn-capacity", "b2", "a4", "spawnCount < ${policy.maxSpawn}"))
        prodNodes.add(ProductionNode("p1", "SPAWN", ProductionAction.SPAWN_AGENT, listOf("b3")))
        alphaNodes.add(AlphaNode("a5", "lease.expired", FactType.KEY, "leaseExpiresAt > 0 && now > leaseExpiresAt"))
        if (policy.reclaimBlocked) prodNodes.add(ProductionNode("p2", "RECLAIM", ProductionAction.RECLAIM_LEASE, listOf("a5")))
        if (policy.promoteOnDone) { alphaNodes.add(AlphaNode("a6", "card.column==DONE", FactType.CARD, "columnOrdinal == 2")); prodNodes.add(ProductionNode("p3", "PROMOTE", ProductionAction.PROMOTE_CARD, listOf("a6"))) }
        if (policy.backoffOnError) { alphaNodes.add(AlphaNode("a7", "key.error", FactType.KEY, "isActive == false && status == backoff")); prodNodes.add(ProductionNode("p4", "BACKOFF", ProductionAction.BACKOFF_KEY, listOf("a7"))) }
        return CompiledReteNetwork(alphaNodes, betaNodes, prodNodes, policy)
    }
}

@Serializable
data class CompiledReteNetwork(
    val alphaNodes: List<AlphaNode>,
    val betaNodes: List<BetaNode>,
    val productionNodes: List<ProductionNode>,
    val policy: DispatchPolicy
) {
    val allNodes: List<ReteNode> get() = alphaNodes + betaNodes + productionNodes
}

data class ReteKeySnapshot(
    val keyId: String,
    val provider: String,
    val status: KeyStatus,
    val leasedTo: String?,
    val leaseExpiresAt: Long
)

class ReteEngine(val network: CompiledReteNetwork) {
    private val _snapshots = MutableStateFlow<ReteSnapshot?>(null)
    val snapshots: StateFlow<ReteSnapshot?> = _snapshots.asStateFlow()

    fun evaluate(cards: List<BoardCard>, keys: List<ReteKeySnapshot>, tickState: CoordinatorState): ReteSnapshot {
        val now = platformUtils.currentTimeMillis()
        val cardFacts = cards.map { CardFact("card:${it.id}", it.id, it.column.ordinalValue, it.assignee, it.priority, it.dependencies.size) }
        val keyFacts = keys.map { KeyFact("key:${it.keyId}", it.keyId, it.provider, it.status == KeyStatus.ACTIVE, it.leasedTo, it.leaseExpiresAt) }
        val tickFact = TickFact("tick:${tickState.tickCount}", tickState.tickCount, tickState.currentlyRunning, tickState.availableKeys, tickState.queueDepth)

        val alphaResults = network.alphaNodes.map { node ->
            val matchedList: List<ReteFact> = when (node.factType) {
                FactType.CARD -> evaluateCardAlpha(node, cardFacts)
                FactType.KEY -> evaluateKeyAlpha(node, keyFacts, now)
                FactType.TICK -> evaluateTickAlpha(node, tickFact)
                else -> emptyList<ReteFact>()
            }
            val matchedIds = matchedList.map { it.factId }
            node.copy(
                matchedFactIds = matchedIds,
                metrics = node.metrics.copy(
                    factCount = matchedList.size,
                    firingCount = node.metrics.firingCount + if (matchedList.isNotEmpty()) 1 else 0,
                    totalPropagationMs = node.metrics.totalPropagationMs + (if (matchedList.isNotEmpty()) 1 else 0),
                    lastFiredMs = if (matchedList.isNotEmpty()) now else node.metrics.lastFiredMs,
                ),
            )
        }
        val alphaMap = mutableMapOf<String, AlphaNode>()
        alphaResults.forEach { alphaMap[it.nodeId] = it }

        val betaResults = network.betaNodes.map { node ->
            val leftCount = alphaMap[node.leftParentId]?.matchedFactIds?.size ?: 0
            val rightCount = alphaMap[node.rightParentId]?.matchedFactIds?.size ?: 0
            val matchedPairs = minOf(leftCount, rightCount)
            node.copy(
                matchedPairs = matchedPairs,
                metrics = node.metrics.copy(
                    factCount = matchedPairs,
                    firingCount = node.metrics.firingCount + if (matchedPairs > 0) 1 else 0,
                    lastFiredMs = if (matchedPairs > 0) now else node.metrics.lastFiredMs,
                ),
            )
        }
        val betaMap = betaResults.associateBy { it.nodeId }

        val prodResults = network.productionNodes.map { node ->
            val fired = node.parentIds.all { pid ->
                when {
                    alphaMap.containsKey(pid) -> alphaMap[pid]!!.matchedFactIds.isNotEmpty()
                    betaMap.containsKey(pid) -> betaMap[pid]!!.matchedPairs > 0
                    else -> false
                }
            }
            node.copy(
                metrics = node.metrics.copy(
                    factCount = if (fired) 1 else 0,
                    firingCount = node.metrics.firingCount + if (fired) 1 else 0,
                    lastFiredMs = if (fired) now else node.metrics.lastFiredMs,
                ),
            )
        }

        val snapshot = ReteSnapshot(
            tickState.tickCount, now, alphaResults, betaResults, prodResults,
            cardFacts.size + keyFacts.size + 1,
            prodResults.count { it.metrics.factCount > 0 }
        )
        _snapshots.value = snapshot
        return snapshot
    }

    private fun evaluateCardAlpha(node: AlphaNode, facts: List<CardFact>): List<ReteFact> = when {
        node.condition.contains("columnOrdinal == 0") -> facts.filter { it.columnOrdinal == 0 }
        node.condition.contains("columnOrdinal == 2") -> facts.filter { it.columnOrdinal == 2 }
        node.condition.contains("priority >= 2") -> facts.filter { it.priority >= 2 }
        else -> emptyList()
    }

    private fun evaluateKeyAlpha(node: AlphaNode, facts: List<KeyFact>, now: Long): List<ReteFact> = when {
        node.condition.contains("isActive && leasedTo == null") -> facts.filter { it.isActive && it.leasedTo == null }
        node.condition.contains("leaseExpiresAt > 0") -> facts.filter { it.leaseExpiresAt > 0 && now > it.leaseExpiresAt }
        node.condition.contains("isActive == false") -> facts.filter { !it.isActive }
        else -> emptyList()
    }

    private fun evaluateTickAlpha(node: AlphaNode, fact: TickFact): List<ReteFact> = when {
        node.condition.contains("currentlyRunning <") -> {
            val lim = node.condition.substringAfter("<").trim().toIntOrNull() ?: 0
            if (fact.currentlyRunning < lim) listOf(fact) else emptyList()
        }
        else -> emptyList()
    }
}

@Serializable
data class ReteSnapshot(
    val tickNumber: Int,
    val timestampMs: Long,
    val alphaNodes: List<AlphaNode>,
    val betaNodes: List<BetaNode>,
    val productionNodes: List<ProductionNode>,
    val totalFacts: Int,
    val firedProductions: Int
) {
    fun toMermaid(): String {
        val sb = StringBuilder()
        sb.append("graph TD\n")
        for (n in alphaNodes) sb.append("  ${n.nodeId}[${n.label}<br/>facts: ${n.matchedFactIds.size}]\n")
        for (n in betaNodes) { sb.append("  ${n.nodeId}{{${n.label}<br/>joined: ${n.matchedPairs}}}\n"); sb.append("  ${n.leftParentId} --> ${n.nodeId}\n"); sb.append("  ${n.rightParentId} --> ${n.nodeId}\n") }
        for (n in productionNodes) {
            val fired = n.metrics.factCount > 0
            val label = if (fired) ":::fired" else ""
            sb.append("  ${n.nodeId}[/${n.label}/]$label\n")
            for (p in n.parentIds) sb.append("  $p --> ${n.nodeId}\n")
        }
        sb.append("classDef fired fill:#238636,stroke:#2ea043,color:white;\n")
        return sb.toString()
    }
}