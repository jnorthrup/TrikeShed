@file:Suppress("unused")

package borg.trikeshed.og1.state

import borg.trikeshed.og1.fanout.Payloads
import borg.trikeshed.og1.fanout.poolInit
import borg.trikeshed.og1.shape.ShapeCursor
import borg.trikeshed.og1.types.*

/* ═════════════════════════════════════════════════════════════════════
 *  CrmsState — FSM-driven CRMS algebraic unification.
 *
 *  Phases = eigenspace transitions:
 *    BRAINSTORM → GAP → KMEANS → QUORUM → DELIVER → MONITOR
 *
 *  Each phase resolves a spectral decomposition of the project state.
 *  Wireproto payloads live in Payloads pool — decoded on demand.
 * ═════════════════════════════════════════════════════════════════════ */

enum class CrmsPhase {
    BRAINSTORM,   // eigenvectors: branch/scope similarity matrix
    GAP,          // eigenvectors: gap/typedef correlations
    KMEANS,       // eigenvector: cluster assignment
    QUORUM,       // eigenvalue: dominant → selects winner
    DELIVER,      // eigenvalue: 1 (converged) — parallel execution
    MONITOR,      // eigenvalue: 0 (absorbing) — drift detection
}

/* ── VoterFacet — one k-means-seated voter ─────────────────────────── */

data class VoterFacet(
    val id: String,
    val cluster: Int,
    val weight: Double = 1.0,
    val vote: Map<String, Double> = emptyMap(),
)

/* ── QuorumState ───────────────────────────────────────────────────── */

data class QuorumState(
    val facets: List<VoterFacet> = emptyList(),
    val winner: String = "",
    val confidence: Double = 0.0,
    val threshold: Double = 0.6,
) {
    val isQuorate: Boolean get() = confidence >= threshold
}

/* ── CrmsState — the outer FSM ─────────────────────────────────────── */

data class CrmsState(
    val phase: CrmsPhase = CrmsPhase.BRAINSTORM,
    val plan: FanoutPlan = FanoutPlan(""),
    val brainstorm: BrainstormState = BrainstormState(),
    val rounds: List<DeliveryRound> = emptyList(),
    val quorum: QuorumState = QuorumState(),
    val debtItems: List<Map<String, Any?>> = emptyList(),
    val gaps: List<Map<String, Any?>> = emptyList(),
) {
    /* ── Phase transitions (pure) ──────────────────────────────────── */

    fun toGap(gaps: List<Map<String, Any?>>): CrmsState =
        copy(phase = CrmsPhase.GAP, gaps = gaps)

    fun toKmeans(facets: List<VoterFacet>): CrmsState =
        copy(phase = CrmsPhase.KMEANS, quorum = quorum.copy(facets = facets))

    fun toQuorum(winner: String, confidence: Double): CrmsState =
        copy(phase = CrmsPhase.QUORUM, quorum = quorum.copy(winner = winner, confidence = confidence))

    fun toDeliver(plan: FanoutPlan): CrmsState =
        copy(phase = CrmsPhase.DELIVER, plan = plan)

    fun toMonitor(): CrmsState =
        copy(phase = CrmsPhase.MONITOR)

    fun toBrainstorm(): CrmsState =
        copy(phase = CrmsPhase.BRAINSTORM, brainstorm = BrainstormState())

    /* ── Round management ──────────────────────────────────────────── */

    fun withRound(round: DeliveryRound): CrmsState =
        copy(rounds = rounds + round)

    fun withDebt(items: List<Map<String, Any?>>): CrmsState =
        copy(debtItems = items)

    /* ── Payload resolution ────────────────────────────────────────── */

    /** Resolve the wireproto payload for the current phase. */
    fun resolvePayload(): String {
        poolInit // ensure pool
        return when (phase) {
            CrmsPhase.BRAINSTORM -> Payloads[Payloads.BRAINSTORM]
            CrmsPhase.GAP        -> Payloads[Payloads.GAP_ANALYSIS]
            CrmsPhase.KMEANS     -> Payloads[Payloads.KMEANS_INIT]
            CrmsPhase.QUORUM     -> Payloads[Payloads.QUORUM]
            CrmsPhase.DELIVER    -> Payloads[Payloads.WORKER_GOAL]
            CrmsPhase.MONITOR    -> Payloads[Payloads.LEAN]
        }
    }
}

/* ── FSM adjacency ─────────────────────────────────────────────────── */

val CRMS_TRANSITIONS: Map<CrmsPhase, Set<CrmsPhase>> = mapOf(
    CrmsPhase.BRAINSTORM to setOf(CrmsPhase.GAP),
    CrmsPhase.GAP        to setOf(CrmsPhase.KMEANS),
    CrmsPhase.KMEANS     to setOf(CrmsPhase.QUORUM),
    CrmsPhase.QUORUM     to setOf(CrmsPhase.DELIVER, CrmsPhase.BRAINSTORM),
    CrmsPhase.DELIVER    to setOf(CrmsPhase.DELIVER, CrmsPhase.MONITOR, CrmsPhase.QUORUM),
    CrmsPhase.MONITOR    to setOf(CrmsPhase.BRAINSTORM, CrmsPhase.MONITOR),
)

/** Is the transition from → to valid? */
fun CrmsPhase.canTransitionTo(to: CrmsPhase): Boolean =
    CRMS_TRANSITIONS[this]?.contains(to) == true
