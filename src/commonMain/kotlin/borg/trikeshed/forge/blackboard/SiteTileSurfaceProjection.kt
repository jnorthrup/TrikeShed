package borg.trikeshed.forge.blackboard

data class SiteTile(val confixPath: String, val facet: String, val count: Long, val label: String)

class SiteTileSurfaceProjection(
    override val anchorSectionId: String
) : ForgeSurfaceProjection<SiteTile, SiteTile> {

    override val ttlMs: Long = FORGE_SURFACE_TTL_MS

    override val grid: ForgeSurfaceGrid = ForgeSurfaceGrid(
        columns = 4,
        cellWidth = 240.0,
        cellHeight = 120.0,
        gapX = 24.0,
        gapY = 24.0,
        elevation = 10.0,
    )

    override fun sectionIdOf(item: SiteTile, index: Int): String =
        "site-" + item.confixPath.forgeSectionToken()

    override fun payloadOf(item: SiteTile, index: Int): SiteTile = item
}
