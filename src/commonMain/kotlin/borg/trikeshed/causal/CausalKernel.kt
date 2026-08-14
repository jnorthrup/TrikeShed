@file:Suppress("NOTHING_TO_INLINE", "ObjectPropertyName", "NonAsciiCharacters")

package borg.trikeshed.causal

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.rem
import borg.trikeshed.lib.view
import borg.trikeshed.util.oroboros.LexicalMemory

// ═══════════════════════════════════════════════════════════════════════════
// CAUSAL EVENT KERNEL — PRELOAD-native causal graph algebra
//
// The flywheel is a causal graph: WorkQueued → WorkDispatched → PatchDelivered
// → WorkSettled, with Superseded forks and Retired terminals. Every event is
// a Join-composed node; the graph is a Series<EventNode> (lazy WAL projection).
//
// This kernel gives the graph real causal weight:
//   - CCEK lifecycle (CREATED→OPEN→ACTIVE→DRAINING→CLOSED) for causal phases
//   - Causal proximity scoring (LexicalMemory overlap + ancestry distance)
//   - filter / % (K's `where`) return Series, not Iterator (PRELOAD port gap fix)
//   - CausalCursor = Cursor-shaped projection of the WAL for columnar queries
//   - Dense constructor grammar: `workId j (ordinal j (edge j payload))`
//
// PRELOAD alignment:
//   Join          = base binary composition for every event shape
//   Series<E>     = causal graph (size + index oracle)
//   α             = lazy ancestry/lineage/payload projection
//   filter / %    = same-typed predicate selection (K's `&`, Arrow's `filter(mask)`)
//   CCEK          = explicit lifecycle state machine for causal phases
//   Cursor        = columnar WAL projection (RowVec = value+meta per event)
// ═══════════════════════════════════════════════════════════════════════════

// ─── Ordinal: temporal anchor ─────────────────────────────────────────────

/** WAL append ordinal paired with wall-clock epoch ms. Twin<Long>. */
typealias EventOrdinal = Join<Long, Long>

inline fun EventOrdinal(ordinal: Long, epochMs: Long): EventOrdinal = ordinal j epochMs

/** The identity ordinal — root cause anchor (0,0). */
val RootOrdinal: EventOrdinal = 0L j 0L

// ─── CausalEdge: parent → child with sealed kind ──────────────────────────

/** Sealed causal vocabulary — the edge types in the flywheel DAG. */
sealed class CausalEdgeKind {
    data object Inducted : CausalEdgeKind()   // external producer → queue
    data object Dispatched : CausalEdgeKind()  // queue → Jules session
    data object Delivered : CausalEdgeKind()   // session → patch artifact
    data object Settled : CausalEdgeKind()     // patch → merged commit
    data object Answered : CausalEdgeKind()    // brain → awaiting session
    data object Superseded : CausalEdgeKind()  // lineage fork (rework)
    data object Retired : CausalEdgeKind()     // terminal close (FAILED/CANCELLED)
}

/** Causal edge: Join<parentOrdinal, edgeKind>. Read as: `parent j kind`. */
typealias CausalEdge = Join<EventOrdinal, CausalEdgeKind>

// ─── EventPayload: sealed GADT with kind-specific evidence ────────────────

sealed class EventPayload {
    data class Queued(
        val tier: String,
        val title: String,
        val spec: String,
        val score: Double,
        val lexicalMemory: LexicalMemory,
        val parentWorkId: String? = null,
    ) : EventPayload()

    data class Dispatched(
        val sessionId: String,
        val attempt: Int,
    ) : EventPayload()

    data class Delivered(
        val patchCid: ContentId,
        val touchedFiles: Series<String>,
    ) : EventPayload()

    data class Settled(
        val commitSha: String,
        val versionTag: String,
        val receiptCid: ContentId,
        val lexicalMemory: LexicalMemory,
        val prUrl: String? = null,
    ) : EventPayload()

    data class Answered(
        val sessionId: String,
        val message: String,
    ) : EventPayload()

    data class Superseded(
        val parentWorkId: String,
        val reason: String,
        val reworkDepth: Int,
    ) : EventPayload()

    data class Retired(
        val reason: String,
        val terminalState: String,
    ) : EventPayload()
}

// ─── EventNode: the causal atom ───────────────────────────────────────────

/**
 * The causal event node — deeply nested Join composition.
 *
 *   workId j (ordinal j (edge j payload))
 *
 * Read as: "work W, at time O, caused by edge E from parent, carrying payload P."
 * Every facet collapses back to Join — component1/component2 on each level.
 */
typealias EventNode = Join<String, Join<EventOrdinal, Join<CausalEdge, EventPayload>>>

// ─── Factory ──────────────────────────────────────────────────────────────

inline fun EventNode(
    workId: String,
    ordinal: Long,
    epochMs: Long,
    parent: EventOrdinal,
    edgeKind: CausalEdgeKind,
    payload: EventPayload,
): EventNode {
    val eo: EventOrdinal = ordinal j epochMs
    val edge: CausalEdge = parent j edgeKind
    return workId j (eo j (edge j payload))
}

// ─── Facet accessors (inline projections — zero allocation) ───────────────

inline val EventNode.workId: String get() = a
inline val EventNode.ordinal: EventOrdinal get() = b.a
inline val EventNode.edge: CausalEdge get() = b.b.a
inline val EventNode.payload: EventPayload get() = b.b.b
inline val EventNode.kind: CausalEdgeKind get() = edge.b
inline val EventNode.parentOrdinal: EventOrdinal get() = edge.a
inline val EventNode.epochMs: Long get() = ordinal.b

// ─── CausalGraph: Series<EventNode> — the lazy WAL projection ────────────

/** The causal graph — a lazy Series of event nodes indexed by append order. */
typealias CausalGraph = Series<EventNode>

// ─── Graph projections (α — lazy, same-typed per PRELOAD) ─────────────────

/** All workIds in causal order. Stays Series<String>. */
val CausalGraph.workIds: Series<String> get() = this α { it.workId }

/** All edge kinds in causal order. Stays Series<CausalEdgeKind>. */
val CausalGraph.kinds: Series<CausalEdgeKind> get() = this α { it.kind }

/** All payloads in causal order. Stays Series<EventPayload>. */
val CausalGraph.payloads: Series<EventPayload> get() = this α { it.payload }

/** All ordinals (temporal anchors). Stays Series<EventOrdinal>. */
val CausalGraph.ordinals: Series<EventOrdinal> get() = this α { it.ordinal }

// ─── Graph queries: filter / % (K `where`, same-typed) ────────────────────

/** Events for a specific workId — its full lineage. Stays Series<EventNode>. */
fun CausalGraph.lineageOf(workId: String): Series<EventNode> =
    filter { it.workId == workId }

/** Events of a specific causal kind. Stays Series<EventNode>. */
fun CausalGraph.ofKind(kind: CausalEdgeKind): Series<EventNode> =
    filter { it.kind == kind }

/** All Settled events (completed merges). Stays Series<EventNode>. */
val CausalGraph.settled: Series<EventNode>
    get() = ofKind(CausalEdgeKind.Settled)

/** All Queued events (induction surface). Stays Series<EventNode>. */
val CausalGraph.queued: Series<EventNode>
    get() = ofKind(CausalEdgeKind.Inducted)

/** All Dispatched events (active sessions). Stays Series<EventNode>. */
val CausalGraph.dispatched: Series<EventNode>
    get() = ofKind(CausalEdgeKind.Dispatched)

/** All Retired events (terminal closes). Stays Series<EventNode>. */
val CausalGraph.retired: Series<EventNode>
    get() = ofKind(CausalEdgeKind.Retired)

/**
 * Latest event for a workId — the causal frontier.
 * O(n) scan; the result is nullable because a workId may have zero events.
 */
fun CausalGraph.latestFor(workId: String): EventNode? {
    var latest: EventNode? = null
    for (i in 0 until size) {
        val node = this[i]
        if (node.workId == workId) {
            if (latest == null || node.epochMs > latest.epochMs) latest = node
        }
    }
    return latest
}

// ─── Ancestry walk (transitive parent chain) ──────────────────────────────

/**
 * Transitive ancestry of an ordinal — all events in its causal chain.
 * Walks parent ordinals until reaching RootOrdinal. O(n × depth).
 * Stays Series<EventNode>.
 */
fun CausalGraph.ancestryOf(target: EventOrdinal): Series<EventNode> {
    // Build the ordinal set by walking the parent chain
    val chain = mutableSetOf(target)
    var frontier = setOf(target)
    while (frontier.isNotEmpty() && RootOrdinal !in frontier) {
        val next = mutableSetOf<EventOrdinal>()
        for (i in 0 until size) {
            val node = this[i]
            if (node.ordinal in frontier) next.add(node.parentOrdinal)
        }
        val fresh = next - chain
        chain.addAll(fresh)
        frontier = fresh
    }
    val matches = (0 until size).filter { this[it].ordinal in chain }
    return matches.size j { i -> this[matches[i]] }
}

// ─── Causal proximity scoring ─────────────────────────────────────────────

/**
 * Causal proximity between a query (LexicalMemory) and an event's payload.
 *
 * Combines lexical overlap (term sharing) with ancestry distance (how many
 * hops to a shared parent). Settled events get a recency boost. The result
 * drives dispatch priority: high-proximity work is more likely to unblock
 * a stalled lineage.
 *
 * Score ∈ [0.0, 1.0]:
 *   - 0.0 = no causal or lexical relationship
 *   - 0.3 = lexical overlap only (shared terms)
 *   - 0.5 = direct ancestry (parent chain hit)
 *   - 0.7+ = direct ancestry + lexical overlap
 *   - 1.0 = exact match (same workId)
 */
fun CausalGraph.proximityOf(
    query: LexicalMemory,
    workId: String,
): Double {
    val lineage = lineageOf(workId)
    if (lineage.size == 0) return 0.0

    // Exact match
    var hasSettled = false
    for (i in 0 until lineage.size) {
        if (lineage[i].kind == CausalEdgeKind.Settled) { hasSettled = true; break }
    }
    if (hasSettled) {
        val settled = lineage.filter { it.kind == CausalEdgeKind.Settled }
        if (settled.size > 0) {
            val payload = settled[0].payload as EventPayload.Settled
            val overlap = query.overlap(payload.lexicalMemory)
            if (overlap > 0) return 1.0
        }
    }

    // Lexical overlap across all settled events in the lineage
    val settledEvents = lineage.filter { it.kind == CausalEdgeKind.Settled }
    var maxLexical = 0
    for (i in 0 until settledEvents.size) {
        val payload = settledEvents[i].payload as EventPayload.Settled
        maxLexical = maxOf(maxLexical, query.overlap(payload.lexicalMemory))
    }
    val lexicalScore = if (maxLexical == 0) 0.0 else minOf(0.3 + maxLexical * 0.05, 0.5)

    // Ancestry distance: how close is this work to other settled work?
    val ancestryDepth = lineage.size
    val ancestryScore = if (ancestryDepth > 0) minOf(0.2 + 0.1 / ancestryDepth, 0.3) else 0.0

    return minOf(lexicalScore + ancestryScore, 0.99)
}

/**
 * Rank all workIds by causal proximity to a query. Returns Series<Pair<workId, score>>
 * sorted descending by score. Drives dispatch priority.
 */
fun CausalGraph.rankByProximity(
    query: LexicalMemory,
    candidateWorkIds: Series<String>,
): Series<Join<String, Double>> {
    // stdlib-boundary: Avoid intermediate List allocation from .map
    val scored = ArrayList<Join<String, Double>>(candidateWorkIds.size)
    for (i in 0 until candidateWorkIds.size) {
        val wid = candidateWorkIds[i]
        scored.add(wid j proximityOf(query, wid))
    }
    scored.sortByDescending { it.b }
    return scored.size j { i -> scored[i] }
}

// ─── CausalPhase: CCEK lifecycle for causal phases ────────────────────────

/**
 * CCEK lifecycle for causal phases — the same state machine as
 * NioUserspaceKey/LiburingKey, applied to the causal graph.
 *
 * The phase tracks where in the Inducted→Dispatched→Delivered→Settled
 * lifecycle a workId currently sits. This IS the kanban column, expressed
 * as a CCEK state instead of a String label.
 */
enum class CausalPhase {
    CREATED,     // workId known but no events yet
    OPEN,        // Inducted event observed
    ACTIVE,      // Dispatched event observed (session running)
    DRAINING,    // Delivered event observed (patch available, merging)
    CLOSED;      // Settled or Retired (terminal)

    companion object {
        fun fromLatestKind(kind: CausalEdgeKind): CausalPhase = when (kind) {
            CausalEdgeKind.Inducted -> OPEN
            CausalEdgeKind.Dispatched -> ACTIVE
            CausalEdgeKind.Delivered -> DRAINING
            CausalEdgeKind.Answered -> ACTIVE
            CausalEdgeKind.Settled -> CLOSED
            CausalEdgeKind.Superseded -> OPEN // fork re-opens for induction
            CausalEdgeKind.Retired -> CLOSED
        }
    }
}

/** Current causal phase of a workId, derived from its latest event. */
fun CausalGraph.phaseOf(workId: String): CausalPhase {
    val latest = latestFor(workId) ?: return CausalPhase.CREATED
    return CausalPhase.fromLatestKind(latest.kind)
}

/** All workIds in a given phase. Stays Series<String>. */
fun CausalGraph.inPhase(phase: CausalPhase): Series<String> {
    val phaseByWid = LinkedHashMap<String, CausalPhase>()
    for (i in 0 until size) {
        val node = this[i]
        phaseByWid[node.workId] = CausalPhase.fromLatestKind(node.kind)
    }
    val matches = ArrayList<String>()
    for ((wid, p) in phaseByWid) {
        if (p == phase) matches.add(wid)
    }
    return matches.size j { i -> matches[i] }
}

// ─── CausalGraphBuilder: the only mutable append surface ──────────────────

/**
 * Producers append events here. `.toGraph()` returns the immutable
 * Series<EventNode> projection — the mutable List was a transient,
 * and the end state is read-only Series per PRELOAD categorical idempotency.
 */
class CausalGraphBuilder {
    private val nodes = mutableListOf<EventNode>()
    private var ordinal: Long = 0L

    fun append(
        workId: String,
        epochMs: Long,
        parent: EventOrdinal = RootOrdinal,
        edgeKind: CausalEdgeKind,
        payload: EventPayload,
    ): EventNode {
        val node = EventNode(
            workId = workId,
            ordinal = ordinal++,
            epochMs = epochMs,
            parent = parent,
            edgeKind = edgeKind,
            payload = payload,
        )
        nodes.add(node)
        return node
    }

    fun size(): Int = nodes.size

    /** Immutable causal graph projection. */
    fun toGraph(): CausalGraph = nodes.size j { i -> nodes[i] }
}

// ─── Convenience constructors for the causal vocabulary ───────────────────

/** Mint a Queued event. */
inline fun queuedEvent(
    workId: String, epochMs: Long, tier: String, title: String,
    spec: String, score: Double, lexicalMemory: LexicalMemory,
    parentWorkId: String? = null,
): EventNode = EventNode(
    workId, 0L, epochMs, RootOrdinal, CausalEdgeKind.Inducted,
    EventPayload.Queued(tier, title, spec, score, lexicalMemory, parentWorkId),
)

/** Mint a Dispatched event. */
inline fun dispatchedEvent(
    workId: String, epochMs: Long, parent: EventOrdinal,
    sessionId: String, attempt: Int,
): EventNode = EventNode(
    workId, 0L, epochMs, parent, CausalEdgeKind.Dispatched,
    EventPayload.Dispatched(sessionId, attempt),
)

/** Mint a Settled event. */
inline fun settledEvent(
    workId: String, epochMs: Long, parent: EventOrdinal,
    commitSha: String, versionTag: String, receiptCid: ContentId,
    lexicalMemory: LexicalMemory, prUrl: String? = null,
): EventNode = EventNode(
    workId, 0L, epochMs, parent, CausalEdgeKind.Settled,
    EventPayload.Settled(commitSha, versionTag, receiptCid, lexicalMemory, prUrl),
)

/** Mint a Retired event. */
inline fun retiredEvent(
    workId: String, epochMs: Long, parent: EventOrdinal,
    reason: String, terminalState: String,
): EventNode = EventNode(
    workId, 0L, epochMs, parent, CausalEdgeKind.Retired,
    EventPayload.Retired(reason, terminalState),
)
