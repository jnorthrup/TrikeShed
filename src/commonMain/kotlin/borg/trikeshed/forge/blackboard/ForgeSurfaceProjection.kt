package borg.trikeshed.forge.blackboard

import borg.trikeshed.lib.α
import borg.trikeshed.lib.`▶`
import borg.trikeshed.lib.toList
import kotlinx.datetime.Clock

/**
 * ## ForgeSurfaceProjection — one surface contract for every ring.
 *
 * Every ring (jules sessions, rete fires, modelmux telemetry, kanban, metrics …)
 * lands on the forge blackboard the same way:
 *
 *  1. a domain item is wrapped in a [ForgeSurfaceEnvelope] carrying its own
 *     `sectionId` + geometry (`centerX/centerY/width/height/elevation`) plus
 *     `updatedAt` / `ttlMs`;
 *  2. the envelopes are collected into a [ForgeDomainSurface];
 *  3. the [ForgeBlackboardView] is *rebuilt* by concatenating the new section
 *     ids and [ForgeBlackboardSection3D]s onto [ForgeBlackboardView.DEFAULT],
 *     which keeps the view's init constraint (`layout3D` ids == `sections`)
 *     satisfied by construction.
 *
 * This generalizes the pattern verified in
 * `borg.trikeshed.jules.ui.JulesBlackboardAdapter.projectFullSurface`.
 *
 * Projection is a *pure function* of `(domain, base view, now)` — no I/O, no
 * clock read unless the caller omits `now`, no mutation of the base view.
 */

// ── Geometry ────────────────────────────────────────────────────────────────

/**
 * Geometry every projected surface item carries so a renderer can place it
 * without a second round-trip.
 */
interface ForgeSurfaceGeometry {
    val sectionId: String
    val centerX: Double
    val centerY: Double
    val width: Double
    val height: Double
    val elevation: Double
}

/** Lift any surface item into the blackboard's 3D layout row. */
fun ForgeSurfaceGeometry.asSection3D(): ForgeBlackboardSection3D = ForgeBlackboardSection3D(
    sectionId = sectionId,
    centerX = centerX,
    centerY = centerY,
    width = width,
    height = height,
    elevation = elevation,
)

// ── Surface ─────────────────────────────────────────────────────────────────

/**
 * A TTL-stamped bag of geometry-bearing items — the `<DomainSurface>` half of a
 * projection result. Ring-specific surfaces (e.g. `JulesBlackboardSurface`)
 * implement this so downstream seeds/renderers can treat every ring alike.
 */
interface ForgeDomainSurface {
    /** Every item this surface puts on the board, in render order. */
    val items: List<ForgeSurfaceGeometry>

    /** Epoch millis when this projection was minted. */
    val updatedAt: Long

    /** Time-to-live for this projection, in milliseconds. */
    val ttlMs: Long
}

/** True when `now` has moved past `updatedAt + ttlMs`. */
fun ForgeDomainSurface.isStaleAt(now: Long): Boolean = now - updatedAt > ttlMs

/**
 * Payload + sectionId + geometry + updatedAt + ttlMs — the section-agnostic
 * envelope every ring wraps its domain item in.
 */
data class ForgeSurfaceEnvelope<out P>(
    val payload: P,
    override val sectionId: String,
    override val centerX: Double,
    override val centerY: Double,
    override val width: Double,
    override val height: Double,
    override val elevation: Double,
    val updatedAt: Long,
    val ttlMs: Long,
) : ForgeSurfaceGeometry

/** The generic surface produced by [ForgeSurfaceProjection.project]. */
data class ForgeSectionSurface<out P>(
    /** The DEFAULT-layout section these envelopes hang beneath ("board", "gallery", …). */
    val anchorSectionId: String,
    val envelopes: List<ForgeSurfaceEnvelope<P>>,
    override val updatedAt: Long,
    override val ttlMs: Long,
) : ForgeDomainSurface {
    override val items: List<ForgeSurfaceGeometry> get() = envelopes
}

// ── View rebuild ────────────────────────────────────────────────────────────

/**
 * Rebuild this view with [items] appended as sections.
 *
 * An item whose `sectionId` the view already carries keeps its existing
 * placement — the view is the authority on where a section sits, and [project]
 * mints its envelopes from that same placement so the two never disagree.
 * The result therefore always satisfies `ForgeBlackboardView`'s init constraint
 * and re-appending the same items is a no-op.
 */
fun ForgeBlackboardView.withSurface(items: List<ForgeSurfaceGeometry>): ForgeBlackboardView {
    val seen = sections.toMutableSet()
    val fresh = items.filter { seen.add(it.sectionId) }
    return if (fresh.isEmpty()) this
    else copy(
        sections = sections + (fresh α { it.sectionId }).`▶`,
        layout3D = layout3D + (fresh α { it.asSection3D() }).`▶`,
    )
}

// ── Layout ──────────────────────────────────────────────────────────────────

/**
 * Y below which every ring's tile strip starts.
 *
 * The DEFAULT 2x2 quadrant layout occupies `y ∈ [-560, 560]`. Strips begin below
 * that band so a long strip can never grow into a neighbouring quadrant; each
 * ring stays visually attached to its anchor through shared `centerX` instead.
 */
const val FORGE_SURFACE_STRIP_BASELINE_Y: Double = 700.0

/**
 * Grid that lays surface tiles out in a strip beneath their anchor section.
 *
 * A **full** row of [columns] cells is centered on the anchor's `centerX`, so a
 * given column always lands at the same X no matter how many tiles the strip
 * holds — tiles never slide sideways as the strip fills. A partly-filled last
 * row is therefore left-aligned within that fixed row, not re-centered.
 *
 * Rows grow downward (+Y) from `max(anchor bottom + gapY, baselineY)`.
 */
data class ForgeSurfaceGrid(
    val columns: Int = 3,
    val cellWidth: Double = 280.0,
    val cellHeight: Double = 160.0,
    val gapX: Double = 40.0,
    val gapY: Double = 40.0,
    val elevation: Double = 18.0,
    val baselineY: Double = FORGE_SURFACE_STRIP_BASELINE_Y,
) {
    init {
        require(columns > 0) { "grid needs at least one column, got $columns" }
        require(cellWidth > 0.0 && cellHeight > 0.0) { "grid cells must be positive: ${cellWidth}x$cellHeight" }
    }

    /** Y of the strip's first row of cell *tops*, clear of the default quadrants. */
    fun originYOf(anchor: ForgeBlackboardSection3D): Double =
        maxOf(anchor.centerY + anchor.height / 2.0 + gapY, baselineY)

    /** X of the tile in slot [slot] relative to [anchor]. */
    fun centerXOf(anchor: ForgeBlackboardSection3D, slot: Int): Double {
        val pitch = cellWidth + gapX
        return anchor.centerX - (columns - 1) * pitch / 2.0 + (slot % columns) * pitch
    }

    /** Y of the tile in slot [slot] relative to [anchor]. */
    fun centerYOf(anchor: ForgeBlackboardSection3D, slot: Int): Double =
        originYOf(anchor) + cellHeight / 2.0 + (slot / columns) * (cellHeight + gapY)
}

// ── Contract ────────────────────────────────────────────────────────────────

/**
 * Section-agnostic projection of a ring's domain items onto the forge blackboard.
 *
 * Implementations supply only the ring-specific bits — which section to anchor
 * under, how to name a tile, and what payload to carry. [project] does the rest
 * and is identical for every ring.
 *
 * @param D the ring's domain item type
 * @param P the payload carried in each [ForgeSurfaceEnvelope]
 */
interface ForgeSurfaceProjection<in D, out P> {

    /** Section of [ForgeBlackboardView.DEFAULT] these tiles hang beneath. */
    val anchorSectionId: String

    /** TTL stamped onto every envelope and the surface. */
    val ttlMs: Long

    /** Tile layout beneath [anchorSectionId]. */
    val grid: ForgeSurfaceGrid

    /**
     * Section id for one domain item.
     *
     * Must be derived from the item's *content*, not from [index] — [project]
     * treats a repeated id as the same tile and gives it the placement it
     * already has, which is only correct when the id is stable across calls.
     * [index] is the item's position in the projected list, offered for rings
     * whose identity genuinely is ordinal.
     */
    fun sectionIdOf(item: D, index: Int): String

    /** Render-relevant payload lifted out of one domain item. */
    fun payloadOf(item: D, index: Int): P
}

/** Default TTL for forge surface projections: 5 minutes. */
const val FORGE_SURFACE_TTL_MS: Long = 300_000L

/**
 * Project [domain] onto [base], returning
 * `(rebuilt view, domain surface, index by sectionId)`.
 *
 * Pure: pass [now] to get a fully deterministic result.
 *
 * Two rules keep the view and the surface in agreement, which is what makes
 * re-projecting onto an already-projected view idempotent:
 *
 *  - **one tile per section id** — a repeated id inside [domain] is projected
 *    once, first occurrence wins;
 *  - **placement is sticky** — an id [base] already carries keeps the geometry
 *    [base] gave it, and only genuinely new tiles consume a fresh grid slot.
 *
 * @throws IllegalArgumentException when [ForgeSurfaceProjection.anchorSectionId]
 *   is not a section of [base].
 */
fun <D, P> ForgeSurfaceProjection<D, P>.project(
    domain: List<D>,
    base: ForgeBlackboardView = ForgeBlackboardView.DEFAULT,
    now: Long = Clock.System.now().toEpochMilliseconds(),
): Triple<ForgeBlackboardView, ForgeSectionSurface<P>, Map<String, ForgeSurfaceEnvelope<P>>> {
    val anchor = requireNotNull(ForgeBlackboardView.sectionPlacement(base, anchorSectionId)) {
        "anchor section '$anchorSectionId' is not on view '${base.surface}' ${base.sections}"
    }

    val byId = LinkedHashMap<String, ForgeSurfaceEnvelope<P>>(domain.size)
    var slot = 0
    domain.forEachIndexed { i: Int, item: D ->
        val sectionId = sectionIdOf(item, i)
        if (sectionId in byId) return@forEachIndexed
        val held = ForgeBlackboardView.sectionPlacement(base, sectionId)
        byId[sectionId] = ForgeSurfaceEnvelope(
            payload = payloadOf(item, i),
            sectionId = sectionId,
            centerX = held?.centerX ?: grid.centerXOf(anchor, slot),
            centerY = held?.centerY ?: grid.centerYOf(anchor, slot),
            width = held?.width ?: grid.cellWidth,
            height = held?.height ?: grid.cellHeight,
            elevation = held?.elevation ?: (anchor.elevation + grid.elevation),
            updatedAt = now,
            ttlMs = ttlMs,
        )
        if (held == null) slot++
    }

    val envelopes: List<ForgeSurfaceEnvelope<P>> = byId.values.toList()

    val surface = ForgeSectionSurface(
        anchorSectionId = anchorSectionId,
        envelopes = envelopes,
        updatedAt = now,
        ttlMs = ttlMs,
    )

    return Triple(base.withSurface(envelopes), surface, byId)
}
