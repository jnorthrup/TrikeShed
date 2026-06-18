@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlin.math.minOf
import kotlin.math.min

/**
 * Rete Observability — kernel algebra edition.
 *
 * Uses Series (size + index function) throughout. Alpha/beta/production nodes
 * are built via reified inline factory, not List accumulation.
 * The network is a Series of nodes; evaluation is a chain of α projections.
 */

// ---------------------------------------------------------------------------
// Node types (serializable, serializable)
// ---------------------------------------------------------------------------

@Serializable
sealed interface ReteNode {
    val nodeId: String
    val label: String
    val metrics: ReteNodeMetrics
}

@Serializable
data class ReteNodeMetrics(
    val factCount: Int = 0,
    val firingCount: Long = 0,
    val totalPropagationMs: Long = 0,
    val lastFiredMs: Long = 0,
) {
    val avgPropagationMs: Double
        get() = if (firingCount > 0) totalPropagationMs.toDouble() / firingCount else 0.0
        set(_) { /* synthetic for serialization */ }
}

@Serializable
data class AlphaNode(
    override val nodeId: String,
    override val label: String,
    val factType: FactType,
    val condition: String,
    val matchedFactIds: Series<String> = emptySeries(),
    override val metrics: ReteNodeMetrics = ReteNodeMetrics(),
) : ReteNode

@Serializable
data class BetaNode(
    override val nodeId: String,
    override val label: String,
    val leftParentId: String,
    val rightParentId: String,
    val joinCondition: String,
    val matchedPairs: Int = 0,
    override val metrics: ReteNodeMetrics = ReteNodeMetrics(),
) : ReteNode

@Serializable
data class ProductionNode(
    override val nodeId: String,
    override val label: String,
    val action: ProductionAction,
    val parentIds: Series<String> = emptySeries(),
    override val metrics: ReteNodeMetrics = ReteNodeMetrics(),
) : ReteNode

enum class FactType { CARD, KEY, LEASE, METRIC, TICK }
@Serializable enum class ProductionAction { SPAWN_AGENT, RECLAIM_LEASE, PROMOTE_CARD, BACKOFF_KEY, COMPLETE_CARD, BLOCK_CARD }

// ---------------------------------------------------------------------------
// Facts
// ---------------------------------------------------------------------------

@Serializable
sealed interface ReteFact { val factId: String; val timestampMs: Long }

@Serializable
data class CardFact(
    override val factId: String,
    val cardId: String,
    val columnOrdinal: Int,
    val assignee: String?,
    val priority: Int,
    val dependencyCount: Int,
    override val timestampMs: Long = platformUtils.currentTimeMillis(),
) : ReteFact

@Serializable
data class KeyFact(
    override val factId: String,
    val keyId: String,
    val provider: String,
    val isActive: Boolean,
    val leasedTo: String?,
    val leaseExpiresAt: Long,
    override val timestampMs: Long = platformUtils.currentTimeMillis(),
) : ReteFact

@Serializable
data class TickFact(
    override val factId: String,
    val tickNumber: Int,
    val currentlyRunning: Int,
    val availableKeys: Int,
    val queueDepth: Int,
    override val timestampMs: Long = platformUtils.currentTimeMillis(),
) : ReteFact

// ---------------------------------------------------------------------------
// Helpers: Series factories
// ---------------------------------------------------------------------------

inline fun <T> emptySeries(): Series<T> = 0 j { _ -> throw IndexOutOfBoundsException("emptySeries") }
inline fun <T> Series<T>.safeGet(i: Int): T? = if (i >= 0 && i < size) get(i) else null

inline fun <T> List<T>.toSeries(): Series<T> = size j ::get
inline fun <T> outSeries(block: SeriesBuilder<T>.() -> Unit): Series<T> = SeriesBuilder(size).apply(block).build()

class SeriesBuilder<T>(private val capacity: Int) {
    private val array = mutableListOf<T>()
    inline fun add(element: T) { array.add(element) }
    fun build(): Series<T> = array.size j { array[it] }
}

// ---------------------------------------------------------------------------
// Rete Compiler — reified inline builder
// ---------------------------------------------------------------------------

object ReteCompiler {
    inline fun compile(
        crossinline policyBuilder: DispatchPolicyBuilder.() -> Unit,
    ): CompiledReteNetwork = DispatchPolicyBuilder().apply(policyBuilder).build()

    @JvmInline
    value class DispatchPolicyBuilder(
        var maxInProgress: Int = 4,
        var maxSpawn: Int = 4,
        var leaseTtlMs: Long = 300_000,
        var tickIntervalMs: Long = 5_000,
        var backoffOnError: Boolean = true,
        var promoteOnDone: Boolean = true,
        var reclaimBlocked: Boolean = true,
    ) {
        fun build(): DispatchPolicy = DispatchPolicy(
            maxInProgress, maxSpawn, leaseTtlMs, tickIntervalMs,
            backoffOnError, promoteOnDone, reclaimBlocked,
        )
    }
}

@Serializable
data class DispatchPolicy(
    val maxInProgress: Int = 4,
    val maxSpawn: Int = 4,
    val leaseTtlMs: Long = 300_000,
    val tickIntervalMs: Long = 5_000,
    val backoffOnError: Boolean = true,
    val promoteOnDone: Boolean = true,
    val reclaimBlocked: Boolean = true,
)

// ---------------------------------------------------------------------------
// Compiled Network — Series all the way down
// ---------------------------------------------------------------------------

@Serializable
data class CompiledReteNetwork(
    val alphaNodes: Series<AlphaNode>,
    val betaNodes: Series<BetaNode>,
    val productionNodes: Series<ProductionNode>,
    val policy: DispatchPolicy,
)

// ---------------------------------------------------------------------------
// Rete Engine — α projections chain
// ---------------------------------------------------------------------------

class ReteEngine(val network: CompiledReteNetwork) {
    private val _snapshots = MutableStateFlow<ReteSnapshot?>(null)
    val snapshots: StateFlow<ReteSnapshot?> = _snapshots.asStateFlow()

    fun evaluate(cards: Series<BoardCard>, keys: Series<ReteKeySnapshot>, tickState: CoordinatorState): ReteSnapshot {
        val now = platformUtils.currentTimeMillis()

        // Facts as Series
        val cardFacts = cards.α { CardFact("card:${it.id}", it.id, it.column.ordinalValue, it.assignee, it.priority, it.dependencies.size) }
        val keyFacts = keys.α { KeyFact("key:${it.keyId}", it.keyId, it.provider, it.status == KeyStatus.ACTIVE, it.leasedTo, it.leaseExpiresAt) }
        val tickFact = TickFact("tick:${tickState.tickCount}", tickState.tickCount, tickState.currentlyRunning, tickState.availableKeys, tickState.queueDepth)

        // Alpha: single-fact tests → series of updated alpha nodes
        val alphaResults = network.alphaNodes.α { node ->
            val matched = when (node.factType) {
                FactType.CARD -> evaluateCardAlpha(node, cardFacts)
                FactType.KEY -> evaluateKeyAlpha(node, keyFacts, now)
                FactType.TICK -> evaluateTickAlpha(node, tickFact)
                else -> emptySeries()
            }
            node.copy(
                matchedFactIds = matched.α { it.factId },
                metrics = node.metrics.copy(
                    factCount = matched.size,
                    firingCount = node.metrics.firingCount + if (matched.size > 0) 1 else 0,
                    totalPropagationMs = node.metrics.totalPropagationMs + if (matched.size > 0) 1 else 0,
                    lastFiredMs = if (matched.size > 0) now else node.metrics.lastFiredMs,
                ),
            )
        }

        val alphaMap = seriesToMap<String, AlphaNode>(alphaResults) { it.nodeId }

        // Beta: joins between alpha results
        val betaResults = network.betaNodes.α { node ->
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
        val betaMap = seriesToMap<String, BetaNode>(betaResults) { it.nodeId }

        // Production: fires when all parents satisfied
        val prodResults = network.productionNodes.α { node ->
            val fired = node.parentIds.all { pid ->
                when {
                    alphaMap.containsKey(pid) -> alphaMap[pid]!!.matchedFactIds.size > 0
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

        val firedCount = prodResults.count { it.metrics.factCount > 0 }
        val snapshot = ReteSnapshot(
            tickNumber = tickState.tickCount,
            timestampMs = now,
            alphaNodes = alphaResults,
            betaNodes = betaResults,
            productionNodes = prodResults,
            totalFacts = cardFacts.size + keyFacts.size + 1,
            firedProductions = firedCount,
        )

        _snapshots.value = snapshot
        return snapshot
    }

    // -------------------------------------------------------------------------
    // Alpha evaluators (return Series of matching facts)
    // -------------------------------------------------------------------------

    private fun evaluateCardAlpha(node: AlphaNode, facts: Series<CardFact>): Series<ReteFact> = outSeries {
        if (node.condition.contains("columnOrdinal == 0")) facts.filter { it.columnOrdinal == 0 }.forEach { add(it) }
        if (node.condition.contains("columnOrdinal == 2")) facts.filter { it.columnOrdinal == 2 }.forEach { add(it) }
        if (node.condition.contains("priority >= 2")) facts.filter { it.priority >= 2 }.forEach { add(it) }
    }

    private fun evaluateKeyAlpha(node: AlphaNode, facts: Series<KeyFact>, now: Long): Series<ReteFact> = outSeries {
        if (node.condition.contains("isActive && leasedTo == null")) facts.filter { it.isActive && it.leasedTo == null }.forEach { add(it) }
        if (node.condition.contains("leaseExpiresAt > 0")) facts.filter { it.leaseExpiresAt > 0 && now > it.leaseExpiresAt }.forEach { add(it) }
        if (node.condition.contains("isActive == false")) facts.filter { !it.isActive }.forEach { add(it) }
    }

    private fun evaluateTickAlpha(node: AlphaNode, fact: TickFact): Series<ReteFact> = outSeries {
        if (node.condition.contains("currentlyRunning <")) {
            val lim = node.condition.substringAfter("<").trim().toIntOrNull() ?: 0
            if (fact.currentlyRunning < lim) add(fact)
        }
    }
}

// ---------------------------------------------------------------------------
// Key snapshot for evaluation
// ---------------------------------------------------------------------------

data class ReteKeySnapshot(
    val keyId: String,
    val provider: String,
    val status: KeyStatus,
    val leasedTo: String?,
    val leaseExpiresAt: Long,
)

// ---------------------------------------------------------------------------
// Snapshot (pure data, all Series)
// ---------------------------------------------------------------------------

@Serializable
data class ReteSnapshot(
    val tickNumber: Int,
    val timestampMs: Long,
    val alphaNodes: Series<AlphaNode>,
    val betaNodes: Series<BetaNode>,
    val productionNodes: Series<ProductionNode>,
    val totalFacts: Int,
    val firedProductions: Int,
) {
    fun toMermaid(): String = buildString {
        appendLine("graph TD")
        alphaNodes.forEach { n ->
            appendLine("  ${n.nodeId}[${n.label}<br/>facts: ${n.matchedFactIds.size}]")
        }
        betaNodes.forEach { n ->
            appendLine("  ${n.nodeId}{{${n.label}<br/>joined: ${n.matchedPairs}}}")
            appendLine("  ${n.leftParentId} --> ${n.nodeId}")
            appendLine("  ${n.rightParentId} --> ${n.nodeId}")
        }
        productionNodes.forEach { n ->
            val fired = n.metrics.factCount > 0
            appendLine("  ${n.nodeId}[/${n.label}/]${if (fired) ":::fired" else ""}")
            n.parentIds.forEach { p -> appendLine("  $p --> ${n.nodeId}") }
        }
        appendLine("  classDef fired fill:#238636,stroke:#2ea043,color:white;")
    }
}

// ---------------------------------------------------------------------------
// Helpers for Series Map
// ---------------------------------------------------------------------------

inline fun <K, V> Series<V>.toMapBy(keySelector: (V) -> K): Map<K, V> = 
    mutableMapOf<K, V>().apply {
        for (i in 0 until size) {
            val v = get(i)
            this[keySelector(v)] = v
        }
    }

// Module-level helper with explicit type for Map inference
fun <K, V> seriesToMap(series: Series<V>, keySelector: (V) -> K): Map<K, V> {
    val map = mutableMapOf<K, V>()
    for (i in 0 until series.size) {
        val v = series.get(i)
        map[keySelector(v)] = v
    }
    return map
}