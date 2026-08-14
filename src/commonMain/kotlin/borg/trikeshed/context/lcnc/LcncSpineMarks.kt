@file:Suppress("NonAsciiCharacters", "ObjectPropertyName")

package borg.trikeshed.context.lcnc

import borg.trikeshed.causal.CausalEdgeKind
import borg.trikeshed.dag.FacetTransitionType
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α

/**
 * Zero-cost spine markers for the user-signals → lcnc → forge semantics
 * chain (SPINE-MARK). Per the PRELOAD zero-cost taxonomy mandate — same
 * discipline as `memory/ontology/FacetClassification.kt` — each surface
 * capability named by the chain gets an [@JvmInline value class] over a
 * packed [Byte] ordinal, never a heap [String] as in-process identity.
 * Gloss strings exist only at the [borg.trikeshed.userspace.reactor.KanbanEvent]
 * serialization boundary.
 *
 * Three marker families, one per thick capability:
 *  - [FacetMark]     (blackboard)  — aligned to [FacetTransitionType]
 *  - [CausalMark]    (causality)   — aligned to [CausalEdgeKind]
 *  - [PointcutMark]  (pointcut)    — aligned to the FieldSynapse template
 *                                   indices (cursor/FieldSynapse.kt TPL_*)
 *
 * A marked reduction result is `SpineMark j value` — one Join composition,
 * zero wrapper allocation, no data class per event. A run of dispatches is
 * a [Series] of marked results; marker columns project lazily via [α].
 */

// ── FacetMark (blackboard capability) ─────────────────────────────

/**
 * Zero-cost LCNC facet tag — [Byte] ordinal aligned 1:1 with
 * [FacetTransitionType] (BlackboardDagFabric.kt:130). Keeps the
 * blackboard's facet-transition vocabulary and the signal spine's
 * reduction vocabulary on one identity axis.
 */
@JvmInline
value class FacetMark(val raw: Byte) {
    companion object {
        val Logic = FacetMark(0)
        val Computation = FacetMark(1)
        val Notification = FacetMark(2)
        val Coupling = FacetMark(3)
        val LayoutHint = FacetMark(4)
        val DagCoordinate = FacetMark(5)
        val WtkHint = FacetMark(6)

        /** Resolve from the blackboard enum by ordinal position. */
        fun from(type: FacetTransitionType): FacetMark = when (type) {
            FacetTransitionType.LOGIC -> Logic
            FacetTransitionType.COMPUTATION -> Computation
            FacetTransitionType.NOTIFICATION -> Notification
            FacetTransitionType.COUPLING -> Coupling
            FacetTransitionType.LAYOUT_HINT -> LayoutHint
            FacetTransitionType.DAG_COORDINATE -> DagCoordinate
            FacetTransitionType.WTK_HINT -> WtkHint
        }

        /**
         * Resolve the LCNC mode of a reduction by its registry category
         * (the `Capability.category` keys of [LcncFanoutElement]'s registry):
         * process = pure fold (LOGIC), cas = store vectorization (COMPUTATION),
         * wireproto = fanout (NOTIFICATION). Unknown categories are pure
         * transforms until proven otherwise.
         */
        fun fromCategory(category: String): FacetMark = when (category) {
            "process" -> Logic
            "cas" -> Computation
            "wireproto" -> Notification
            "trajectory" -> Logic
            else -> Logic
        }
    }
}

// ── CausalMark (causality capability) ──────────────────────────────

/**
 * Zero-cost causal edge tag — [Byte] ordinal aligned 1:1 with
 * [CausalEdgeKind] (CausalKernel.kt:53). A dispatch that executes a
 * reduction is the `Dispatched` edge; the whole flywheel vocabulary
 * stays addressable from the spine without heap objects.
 */
@JvmInline
value class CausalMark(val raw: Byte) {
    companion object {
        val Inducted = CausalMark(0)
        val Dispatched = CausalMark(1)
        val Delivered = CausalMark(2)
        val Settled = CausalMark(3)
        val Answered = CausalMark(4)
        val Superseded = CausalMark(5)
        val Retired = CausalMark(6)

        /** Resolve from the sealed causal vocabulary by variant. */
        fun from(kind: CausalEdgeKind): CausalMark = when (kind) {
            CausalEdgeKind.Inducted -> Inducted
            CausalEdgeKind.Dispatched -> Dispatched
            CausalEdgeKind.Delivered -> Delivered
            CausalEdgeKind.Settled -> Settled
            CausalEdgeKind.Answered -> Answered
            CausalEdgeKind.Superseded -> Superseded
            CausalEdgeKind.Retired -> Retired
        }
    }
}

// ── PointcutMark (pointcutting capability) ────────────────────────

/**
 * Zero-cost pointcut phase tag — [Byte] ordinal aligned 1:1 with the
 * FieldSynapse template indices (cursor/FieldSynapse.kt TPL_BEFORE_GET..
 * TPL_AFTER_SET): the four observe hooks around a logical get/set. A
 * spine emission observes the reduced value AFTER execution = AFTER_GET.
 */
@JvmInline
value class PointcutMark(val raw: Byte) {
    companion object {
        val BeforeGet = PointcutMark(0)
        val AfterGet = PointcutMark(1)
        val BeforeSet = PointcutMark(2)
        val AfterSet = PointcutMark(3)

        /** Resolve from a FieldSynapse template index (TPL_*). */
        fun fromTemplate(templateIdx: Int): PointcutMark = when (templateIdx) {
            borg.trikeshed.cursor.FieldSynapse.TPL_BEFORE_GET -> BeforeGet
            borg.trikeshed.cursor.FieldSynapse.TPL_AFTER_GET -> AfterGet
            borg.trikeshed.cursor.FieldSynapse.TPL_BEFORE_SET -> BeforeSet
            borg.trikeshed.cursor.FieldSynapse.TPL_AFTER_SET -> AfterSet
            else -> AfterGet
        }
    }
}

// ── Spine mark composition (zero wrapper allocation) ──────────────

/**
 * A packed three-marker spine classification:
 * `facetMark j causalMark j pointcutMark`. All three are [Byte]-backed
 * value classes — the entire classification is 3 bytes on the stack.
 */
typealias SpineMark = Join<Join<FacetMark, CausalMark>, PointcutMark>

/**
 * A marked reduction result: [SpineMark] `j` value.
 * One Join composition — zero wrapper allocation, no `data class`.
 */
typealias MarkedResult<R> = Join<SpineMark, R>

/**
 * Mark a reduced value with its spine classification.
 * Returns `mark j value`.
 */
fun <R> marked(
    value: R,
    facet: FacetMark,
    causal: CausalMark,
    pointcut: PointcutMark,
): MarkedResult<R> = (facet j causal j pointcut) j value

// ── Series projections (board/cursor discipline) ──────────────────

/** Project the facet-marker column from a Series of marked results, lazily via [α]. */
fun <R> Series<MarkedResult<R>>.facetMarkColumn(): Series<FacetMark> =
    this α { it.a.a.a }

/** Project the causal-marker column from a Series of marked results, lazily via [α]. */
fun <R> Series<MarkedResult<R>>.causalMarkColumn(): Series<CausalMark> =
    this α { it.a.a.b }

/** Project the pointcut-marker column from a Series of marked results, lazily via [α]. */
fun <R> Series<MarkedResult<R>>.pointcutMarkColumn(): Series<PointcutMark> =
    this α { it.a.b }

/** Project the value column from a Series of marked results, lazily via [α]. */
fun <R> Series<MarkedResult<R>>.valueColumn(): Series<R> =
    this α { it.b }
