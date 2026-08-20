package borg.trikeshed.forge.blackboard

import borg.trikeshed.lib.α
import kotlinx.datetime.Clock
import modelmux.ModelSelectionEvent
import modelmux.QuotaSnapshot

/**
 * Payload union for the modelmux ring's `gallery` tiles.
 *
 * Both variants stay value-shaped and carry the original telemetry record so a
 * renderer never has to re-query modelmux.
 */
sealed interface ModelMuxTelemetry {

    /** Provider quota window snapshot. */
    data class Quota(val snapshot: QuotaSnapshot) : ModelMuxTelemetry

    /** A settled provider/model choice. */
    data class Selection(val event: ModelSelectionEvent.ModelSelected) : ModelMuxTelemetry
}

/** Lift a [QuotaSnapshot] into the gallery payload union. */
fun QuotaSnapshot.asTelemetry(): ModelMuxTelemetry = ModelMuxTelemetry.Quota(this)

/** Lift a [ModelSelectionEvent.ModelSelected] into the gallery payload union. */
fun ModelSelectionEvent.ModelSelected.asTelemetry(): ModelMuxTelemetry = ModelMuxTelemetry.Selection(this)

/**
 * ModelMux ring → `gallery` section.
 *
 * Quota snapshots and model-selection events share one strip of tiles beneath
 * the gallery quadrant, in the order the caller hands them over.
 */
object ModelMuxSurfaceProjection : ForgeSurfaceProjection<ModelMuxTelemetry, ModelMuxTelemetry> {

    override val anchorSectionId: String = "gallery"

    override val ttlMs: Long = FORGE_SURFACE_TTL_MS

    override val grid: ForgeSurfaceGrid = ForgeSurfaceGrid(
        columns = 3,
        cellWidth = 260.0,
        cellHeight = 140.0,
        gapX = 28.0,
        gapY = 28.0,
        elevation = 14.0,
    )

    /**
     * Content-derived, so a tile keeps its place across projections: one tile per
     * provider quota window, one per selection request. A fresher snapshot of the
     * same window updates that window's tile rather than minting a new one.
     */
    override fun sectionIdOf(item: ModelMuxTelemetry, index: Int): String = when (item) {
        is ModelMuxTelemetry.Quota ->
            "gallery-quota-${item.snapshot.provider.forgeSectionToken()}-${item.snapshot.windowStart}"

        is ModelMuxTelemetry.Selection ->
            "gallery-model-${item.event.provider.forgeSectionToken()}-${item.event.requestId.forgeSectionToken()}"
    }

    override fun payloadOf(item: ModelMuxTelemetry, index: Int): ModelMuxTelemetry = item
}

/**
 * Project modelmux telemetry onto the `gallery` section in one pass:
 * quota snapshots first, then selection events.
 *
 * Pure — pass [now] for a deterministic result.
 */
fun projectModelMuxTelemetry(
    quotas: List<QuotaSnapshot>,
    selections: List<ModelSelectionEvent.ModelSelected>,
    base: ForgeBlackboardView = ForgeBlackboardView.DEFAULT,
    now: Long = Clock.System.now().toEpochMilliseconds(),
): Triple<ForgeBlackboardView, ForgeSectionSurface<ModelMuxTelemetry>, Map<String, ForgeSurfaceEnvelope<ModelMuxTelemetry>>> =
    ModelMuxSurfaceProjection.project(
        domain = (quotas α { it.asTelemetry() }) + (selections α { it.asTelemetry() }),
        base = base,
        now = now,
    )
