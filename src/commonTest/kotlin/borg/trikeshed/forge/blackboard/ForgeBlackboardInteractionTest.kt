package borg.trikeshed.forge.blackboard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeBlackboardInteractionTest {

    @Test
    fun cycleModeProgressesFlatToParallaxToWorldToFlat() {
        val initial = ForgeBlackboardInteraction(mode = ForgeBlackboardMode.FLAT_2D)
        val parallax = initial.cycleMode()
        assertEquals(ForgeBlackboardMode.PARALLAX_25D, parallax.mode)
        val world = parallax.cycleMode()
        assertEquals(ForgeBlackboardMode.WORLD_3D, world.mode)
        val flat = world.cycleMode()
        assertEquals(ForgeBlackboardMode.FLAT_2D, flat.mode)
    }

    @Test
    fun cycleModeSetsAnimatingFlag() {
        val next = ForgeBlackboardInteraction().cycleMode()
        assertTrue(next.animating, "cycling mode must flag animation")
    }

    @Test
    fun resetToBoardClearsSelectionAndResetsCameras() {
        val interaction = ForgeBlackboardInteraction(
            selectedSectionId = "board",
            camera3d = ForgeBlackboard3D(distance = 800.0),
        )
        val reset = interaction.resetToBoard()
        assertNull(reset.selectedSectionId)
        assertEquals(ForgeBlackboardView.DEFAULT.mode3D.distance, reset.camera3d.distance)
        assertTrue(reset.animating)
    }

    @Test
    fun focusSectionZoomsIntoSectionBounds() {
        val interaction = ForgeBlackboardInteraction()
        val focused = interaction.focusSection("gallery")
        val placement = ForgeBlackboardView.DEFAULT.layout3D.first { it.sectionId == "gallery" }
        val expectedDistance = (maxOf(placement.width, placement.height) * 2.2)
            .coerceIn(interaction.camera3d.minDistance, interaction.camera3d.maxDistance)
        assertEquals(expectedDistance, focused.camera3d.distance)
        assertEquals("gallery", focused.selectedSectionId)
    }

    @Test
    fun centerSelectedUsesCurrentSelection() {
        val interaction = ForgeBlackboardInteraction(selectedSectionId = "graph")
        val centered = interaction.centerSelected()
        val placement = ForgeBlackboardView.DEFAULT.layout3D.first { it.sectionId == "graph" }
        val expectedDistance = (maxOf(placement.width, placement.height) * 2.2)
            .coerceIn(interaction.camera3d.minDistance, interaction.camera3d.maxDistance)
        assertEquals(expectedDistance, centered.camera3d.distance)
    }

    @Test
    fun centerSelectedNoopsWithoutSelection() {
        val interaction = ForgeBlackboardInteraction(selectedSectionId = null)
        val centered = interaction.centerSelected()
        assertEquals(interaction, centered)
    }

    @Test
    fun tickDampsVelocityAndEventuallyRests() {
        val impulse = ForgeBlackboardInteraction(
            animating = true,
            camera2d = ForgeBlackboardCamera(vx = 100.0, vy = 50.0),
        )
        val ticked = impulse.tick(1.0 / 60.0)
        assertNotEquals(impulse.camera2d.vx, ticked.camera2d.vx, "velocity must change")
    }

    @Test
    fun impulseUpdatesVelocity() {
        val interaction = ForgeBlackboardInteraction()
        val impulse = interaction.impulse(10.0, -5.0, 0.5)
        assertEquals(10.0, impulse.camera2d.vx)
        assertEquals(-5.0, impulse.camera2d.vy)
        assertEquals(0.5, impulse.camera2d.vz)
        assertTrue(impulse.animating)
    }

    @Test
    fun centerAtSeedsVelocityTowardsTarget() {
        val interaction = ForgeBlackboardInteraction(camera2d = ForgeBlackboardCamera(x = 0.0, y = 0.0))
        val centered = interaction.centerAt(100.0, -50.0)
        assertTrue(centered.camera2d.vx > 0.0, "vx should be positive towards target")
        assertTrue(centered.camera2d.vy < 0.0, "vy should be negative towards target")
        assertTrue(centered.animating)
    }

    @Test
    fun zoomOutSeedsNegativeZoomVelocity() {
        val interaction = ForgeBlackboardInteraction()
        val zoomed = interaction.zoomOut()
        assertTrue(zoomed.camera2d.vz < 0.0, "vz should be negative to zoom out")
        assertTrue(zoomed.animating)
    }

    @Test
    fun modeLabelMatchesCurrentMode() {
        assertEquals("Flat 2D", ForgeBlackboardInteraction(mode = ForgeBlackboardMode.FLAT_2D).modeLabel())
        assertEquals("2.5D depth", ForgeBlackboardInteraction(mode = ForgeBlackboardMode.PARALLAX_25D).modeLabel())
        assertEquals("3D world", ForgeBlackboardInteraction(mode = ForgeBlackboardMode.WORLD_3D).modeLabel())
    }
}
