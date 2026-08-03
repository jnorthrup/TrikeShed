@file:Suppress("NOTHING_TO_INLINE")

package borg.trikeshed.causal

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.util.oroboros.LexicalMemory

// ─────────────────────────────────────────────────────────────────────────
// Causal Event Kernel — PRELOAD-native causal graph algebra
//
// Every event in the flywheel (WorkQueued, WorkDispatched, WorkDrained,
// PatchArrived, etc.) is a Join-composed node in a causal graph. The graph
// is a Series<EventNode> indexed by ordinal — a lazy projection over the
// WAL, never materialized unless a stdlib boundary demands it.
//
// Design follows PRELOAD.md:
//   - Join = base binary composition for every event shape
//   - Series<EventNode> = the causal graph (size + index oracle)
//   - α projection for causal ancestry queries (never List)
//   - CCEK lifecycle for causal edge transitions
//   - typealiases compress semantics, not substance
// ─────────────────────────────────────────────────────────────────────────

/**
 * Causal event ordinal — a content-addressed identity for one event in the
 * causal graph. The ordinal is the WAL append position; the CID is the
 * content hash of the event payload. Twin<Long> = Join<Long,Long> where
 * `a` = ordinal, `b` = epochMs.
 *
 * Read as: `ordinal j epochMs` — the temporal anchor of a cause.
 */
typealias EventOrdinal = Join<Long, Long>

/** Convenience: mint an EventOrdinal from raw values. */
inline fun eventOrdinal(ordinal: Long, epochMs: Long): EventOrdinal = ordinal j epochMs

/**
 * Causal edge — one event's relationship to its antecedent.
 *
 * `a` = the parent EventOrdinal (what caused this), `b` = the edge kind.
 * A root cause has `a = 0 j 0` (the identity anchor). The edge kind is a
 * sealed-key singleton so the result type is fixed by the key — the same
 * GADT-key pattern PRELOAD cites from Haskell Lens'/Monocle optics.
 */
typealias CausalEdge = Join<EventOrdinal, CausalEdgeKind>

/** Sealed edge kinds — the causal vocabulary. Each is a singleton key. */
sealed class CausalEdgeKind {
    /** Work was queued from an external producer (induction). */
    data object Inducted : CausalEdgeKind()
    /** Work was dispatched to a Jules session. */
    data object Dispatched : CausalEdgeKind()
    /** A session produced a patch (delivery). */
    data object Delivered : CausalEdgeKind()
    /** A patch was merged into the tree (settlement). */
    data object Settled : CausalEdgeKind()
    /** Work was superseded by a rework (lineage fork). */
    data object Superseded : CausalEdgeKind()
    /** Work was retired without merge (terminal close). */
    data object Retired : CausalEdgeKind()
}

/**
 * The causal event node — Join<WorkId, Join<EventOrdinal, Join<CausalEdge, Payload>>>
 *
 * Compressed via nested Join composition. Read as:
 *   `workId j (ordinal j (edge j payload))`
 *
 * Every shape collapses back to Join. The payload is a sealed class carrying
 * the event-specific evidence (spec, sessionId, commitSha, receipt, etc.).
 * The payload is NOT a flat data class — it's a sealed GADT so the result
 * type of any facet query is fixed by the payload kind.
 */
typealias EventNode = Join<String, Join<EventOrdinal, Join<CausalEdge, EventPayload>>>

/** Sealed payload kinds — the evidence each event kind carries. */
sealed class EventPayload {
    /** Work queued from an external producer. */
    data class Queued(
        val tier: String,
        val title: String,
        val spec: String,
        val score: Double,
        val parent: ContentId? = null,
    ) : EventPayload()

    /** Work dispatched to a session. */
    data class Dispatched(
        val sessionId: String,
        val attempt: Int,
    ) : EventPayload()

    /** Session produced a patch. */
    data class Delivered(
        val patchCid: ContentId,
        val touchedFiles: Series<String>,
    ) : EventPayload()

    /** Patch merged into the tree. */
    data class Settled(
        val commitSha: String,
        val versionTag: String,
        val receiptCid: ContentId,
        val lexicalMemory: LexicalMemory,
    ) : EventPayload()

    /** Work superseded — lineage forked from the parent. */
    data class Superseded(
        val parentWorkId: String,
        val reason: String,
    ) : EventPayload()

    /** Work retired without merge. */
    data class Retired(
        val reason: String,
    ) : EventPayload()
}

// ─── Factory: mint EventNode from raw parts ──────────────────────────────

/** Compose an EventNode from its parts using the `j` constructor grammar. */
inline fun eventNode(
    workId: String,
    ordinal: Long,
    epochMs: Long,
    parentOrdinal: Long,
    parentEpochMs: Long,
    edgeKind: CausalEdgeKind,
    payload: EventPayload,
): EventNode {
    val eo = eventOrdinal(ordinal, epochMs)
    val parent = eventOrdinal(parentOrdinal, parentEpochMs)
    val edge: CausalEdge = parent j edgeKind
    return workId j (eo j (edge j payload))
}

// ─── EventNode facet accessors (projection, not reflection) ──────────────

/** The workId of this event node. */
val EventNode.workId: String inline get() = a

/** The temporal ordinal of this event node. */
val EventNode.ordinal: EventOrdinal inline get() = b.a

/** The causal edge (parent + kind) of this event node. */
val EventNode.edge: CausalEdge inline get() = b.b.a

/** The payload of this event node. */
val EventNode.payload: EventPayload inline get() = b.b.b

/** The edge kind (Inducted/Dispatched/Delivered/Settled/Superseded/Retired). */
val EventNode.kind: CausalEdgeKind inline get() = edge.b

/** The parent ordinal this event was caused by. */
val EventNode.parentOrdinal: EventOrdinal inline get() = edge.a

// ─── CausalGraph: Series<EventNode> — the lazy causal graph ──────────────

/**
 * The causal graph is a Series of EventNodes indexed by append ordinal.
 * It is a lazy projection over the WAL — never materialized unless a stdlib
 * boundary (e.g. JSON rendering, Set membership) demands it.
 *
 * Per PRELOAD.md categorical idempotency: a CausalGraph that is projected,
 * filtered, or mapped stays a Series<CausalGraph>. Only `.toList()` at the
 * boundary demotes it, and that demotion is debt.
 */
typealias CausalGraph = Series<EventNode>

// ─── Graph queries (α projections — no materialization) ──────────────────

/** Project the workIds touched by this causal graph. Stays Series<String>. */
val CausalGraph.workIds: Series<String> get() = this α { it.workId }

/** Project the edge kinds in declaration order. Stays Series<CausalEdgeKind>. */
val CausalGraph.kinds: Series<CausalEdgeKind> get() = this α { it.kind }

/** Project the payloads for filtering by sealed-kind. Stays Series<EventPayload>. */
val CausalGraph.payloads: Series<EventPayload> get() = this α { it.payload }

/** Project the temporal ordinals (for ancestry queries). Stays Series<EventOrdinal>. */
val CausalGraph.ordinals: Series<EventOrdinal> get() = this α { it.ordinal }

/**
 * Ancestry walk: find all events caused by (transitively) the given ordinal.
 * This is a lazy α projection — it filters the graph by walking the parent
 * chain. The result stays Series<EventNode>; materialization is the caller's
 * choice.
 *
 * K pedigree: this is `sublist where` in kdb+ — a predicate filter that
 * returns same-typed. Arrow: `filter(mask)`. PRELOAD says this should return
 * Series, not Iterator.
 */
fun CausalGraph.ancestryOf(ordinal: EventOrdinal): Series<EventNode> {
    // Collect ordinals in the ancestry chain, then project matching nodes.
    // This is O(n × depth) but depth is typically ≤5 (queued→dispatched→
    // delivered→settled, with occasional superseded forks).
    val chain = mutableSetOf(ordinal)
    var frontier = setOf(ordinal)
    while (frontier.isNotEmpty()) {
        val parents = (0 until size)
            .asSequence()
            .map { this[it].parentOrdinal }
            .filter { it in frontier }
            .toSet()
        chain.addAll(parents)
        frontier = parents
    }
    val matches = (0 until size).filter { this[it].ordinal in chain }
    return matches.size j { i -> this[matches[i]] }
}

/**
 * Latest event for a given workId — the causal frontier.
 * Returns null if the workId has no events.
 */
fun CausalGraph.latestFor(workId: String): EventNode? {
    var latest: EventNode? = null
    for (i in 0 until size) {
        val node = this[i]
        if (node.workId == workId) {
            if (latest == null || node.ordinal.b > latest.ordinal.b) {
                latest = node
            }
        }
    }
    return latest
}

/**
 * Lineage for a workId: all events in causal order (oldest first).
 * Stays Series<EventNode>.
 */
fun CausalGraph.lineageOf(workId: String): Series<EventNode> {
    val matches = (0 until size).filter { this[it].workId == workId }
    return matches.size j { i -> this[matches[i]] }
}

// ─── CausalGraphBuilder — the mutable append surface ─────────────────────

/**
 * The only mutable surface in the causal algebra. Producers (FlywheelDriver,
 * NecromancerCli) append events here; the resulting graph is read-only.
 *
 * Per PRELOAD.md: a `mutableListOf` that is built and never mutated should
 * be a Series. The builder's `.toGraph()` returns the immutable
 * Series<EventNode> projection — the mutable List was a transient.
 */
class CausalGraphBuilder {
    private val nodes = mutableListOf<EventNode>()
    private var ordinal: Long = 0L

    /** Append a causal event. Returns the minted EventNode. */
    fun append(
        workId: String,
        epochMs: Long,
        parentOrdinal: Long = 0L,
        parentEpochMs: Long = 0L,
        edgeKind: CausalEdgeKind,
        payload: EventPayload,
    ): EventNode {
        val node = eventNode(
            workId = workId,
            ordinal = ordinal++,
            epochMs = epochMs,
            parentOrdinal = parentOrdinal,
            parentEpochMs = parentEpochMs,
            edgeKind = edgeKind,
            payload = payload,
        )
        nodes.add(node)
        return node
    }

    /** The current size (ordinal count). */
    fun size(): Int = nodes.size

    /** Project the immutable causal graph — a Series<EventNode>. */
    fun toGraph(): CausalGraph = nodes.size j { i -> nodes[i] }
}

// ─── Convenience: empty graph (identity anchor for joins) ────────────────

/** The empty causal graph — zero events. Identity for graph concatenation. */
fun emptyCausalGraph(): CausalGraph = 0 j { _ -> error("empty causal graph") }
