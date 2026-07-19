package borg.trikeshed.forge.blackboard

import kotlin.math.abs

/**
 * Mutable interaction state for the blackboard.  Drives every corner/title
 * button: the JVM shell and the browser persistence script both consume the
 * same [ForgeBlackboardInteraction] so the depth-toggle, fit, center, and zoom
 * controls behave identically on every target.
 *
 * Pure functions only — the view layer owns the mutable copy and calls
 * [tick] / [cycleMode] / [impulse] on pointer events.
 */
data class ForgeBlackboardInteraction(
    val mode: ForgeBlackboardMode = ForgeBlackboardView.DEFAULT.defaultMode,
    val camera2d: ForgeBlackboardCamera = ForgeBlackboardView.DEFAULT.defaultCamera,
    val camera3d: ForgeBlackboard3D = ForgeBlackboardView.DEFAULT.mode3D,
    val selectedSectionId: String? = "gallery",
    val animating: Boolean = false,
) {
    /** Cycle the projection mode when the depth-toggle corner button fires. */
    fun cycleMode(): ForgeBlackboardInteraction {
        val next = when (mode) {
            ForgeBlackboardMode.FLAT_2D -> ForgeBlackboardMode.PARALLAX_25D
            ForgeBlackboardMode.PARALLAX_25D -> ForgeBlackboardMode.WORLD_3D
            ForgeBlackboardMode.WORLD_3D -> ForgeBlackboardMode.FLAT_2D
        }
        return copy(mode = next, animating = true)
    }

    /** Whole-board view — resets to defaults. */
    fun resetToBoard(): ForgeBlackboardInteraction = copy(
        camera2d = ForgeBlackboardView.DEFAULT.defaultCamera,
        camera3d = ForgeBlackboardView.DEFAULT.mode3D,
        selectedSectionId = null,
        animating = true,
    )

    /** Fit the camera to a section's 3D bounds. */
    fun focusSection(sectionId: String): ForgeBlackboardInteraction {
        val placement = ForgeBlackboardView.DEFAULT.layout3D.firstOrNull { it.sectionId == sectionId }
            ?: return this
        val span = maxOf(placement.width, placement.height)
        val targetDistance = (span * 2.2).coerceIn(camera3d.minDistance, camera3d.maxDistance)
        return copy(
            selectedSectionId = sectionId,
            camera3d = camera3d.copy(distance = targetDistance),
            animating = true,
        )
    }

    /** Center on the currently selected section (corner button [c]). */
    fun centerSelected(): ForgeBlackboardInteraction {
        val id = selectedSectionId ?: return this
        return focusSection(id)
    }

    /** Advance one animation frame — damps velocity and clears the animating flag at rest. */
    fun tick(dtSeconds: Double): ForgeBlackboardInteraction {
        if (!animating) return this
        val next2d = camera2d.tick(dtSeconds)
        val speed = ForgeBlackboardCamera.speed(camera2d)
        val resting = abs(speed) < 0.5 && abs(next2d.zoom - camera2d.zoom) < 0.001
        return copy(camera2d = next2d, animating = !resting)
    }

    /** Add an impulse to the 2D camera velocity (e.g. dragging a card or background). */
    fun impulse(panVx: Double, panVy: Double, zoomV: Double = 0.0): ForgeBlackboardInteraction {
        return copy(
            camera2d = camera2d.impulse(panVx, panVy, zoomV),
            animating = true
        )
    }

    /**
     * Re-center the camera to a target position, e.g. clicking a card
     * to expand it to a document.
     */
    fun centerAt(worldX: Double, worldY: Double): ForgeBlackboardInteraction {
        val dx = worldX - camera2d.x
        val dy = worldY - camera2d.y
        // Seed velocity towards the target so it animates there
        return copy(
            camera2d = camera2d.copy(
                vx = dx * 5.0,
                vy = dy * 5.0
            ),
            animating = true
        )
    }

    /**
     * Zoom out the camera, treating the graph as a constellation.
     */
    fun zoomOut(): ForgeBlackboardInteraction {
        return copy(
            camera2d = camera2d.copy(
                vz = -5.0 // Seed negative zoom momentum
            ),
            animating = true
        )
    }

    /**
     * Human-readable label for the depth-toggle corner button so the renderer
     * doesn't need to know the enum order.
     */
    fun modeLabel(): String = when (mode) {
        ForgeBlackboardMode.FLAT_2D -> "Flat 2D"
        ForgeBlackboardMode.PARALLAX_25D -> "2.5D depth"
        ForgeBlackboardMode.WORLD_3D -> "3D world"
    }
}
