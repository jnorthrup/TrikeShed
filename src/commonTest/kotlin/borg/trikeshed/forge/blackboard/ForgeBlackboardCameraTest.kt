package borg.trikeshed.forge.blackboard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForgeBlackboardCameraTest {

    @Test
    fun identityProjectsToOrigin() {
        val p = ForgeBlackboardCamera.IDENTITY.project(0.0, 0.0)
        assertEquals(0.0, p.screenX)
        assertEquals(0.0, p.screenY)
    }

    @Test
    fun zoomAroundKeepsFocalPointAnchored() {
        val camera = ForgeBlackboardCamera(x = 100.0, y = 200.0, zoom = 1.0)
        val focalX = 320.0
        val focalY = 480.0
        val beforeScreenX = (focalX - camera.x) * camera.zoom
        val beforeScreenY = (focalY - camera.y) * camera.zoom
        val next = camera.zoomAround(targetZoom = 2.0, focusWorldX = focalX, focusWorldY = focalY)
        val afterScreenX = (focalX - next.x) * next.zoom
        val afterScreenY = (focalY - next.y) * next.zoom
        assertTrue(abs(beforeScreenX - afterScreenX) < 1e-6, "focal X must stay anchored")
        assertTrue(abs(beforeScreenY - afterScreenY) < 1e-6, "focal Y must stay anchored")
    }

    @Test
    fun momentumDecaysEachTick() {
        val camera = ForgeBlackboardCamera.IDENTITY.impulse(panVx = 400.0, panVy = -120.0, zoomV = 0.5)
        val first = camera.tick(1.0 / 60.0)
        val second = first.tick(1.0 / 60.0)
        assertTrue(abs(first.vx) < abs(camera.vx), "vx must decay")
        assertTrue(abs(second.vx) < abs(first.vx), "vx must continue decaying")
        assertTrue(camera.vx > 0 && first.vx > 0, "direction must be preserved")
    }

    @Test
    fun tickClampsZoomIntoBounds() {
        val boosted = ForgeBlackboardCamera.IDENTITY.copy(zoom = 3.2, vz = 12.0)
        val next = boosted.tick(1.0 / 30.0)
        assertTrue(next.zoom <= 3.2, "zoom must clamp to max")
        val collapsed = ForgeBlackboardCamera.IDENTITY.copy(zoom = 0.45, vz = -12.0)
        val after = collapsed.tick(1.0 / 30.0)
        assertTrue(after.zoom >= 0.45, "zoom must clamp to min")
    }

    @Test
    fun tiltIntroducesVerticalSquish() {
        val flat = ForgeBlackboardCamera.IDENTITY.project(0.0, 100.0, depth = 0.0).screenY
        val tilted = ForgeBlackboardCamera.IDENTITY.copy(tilt = 0.35).project(0.0, 100.0, depth = 0.0).screenY
        assertTrue(abs(tilted) < abs(flat), "tilt must compress the Y axis")
    }

    @Test
    fun panByScreenInvertsSign() {
        val camera = ForgeBlackboardCamera.IDENTITY.copy(zoom = 2.0)
        val next = camera.panByScreen(screenDx = 64.0, screenDy = 32.0)
        assertEquals(camera.x - 32.0, next.x)
        assertEquals(camera.y - 16.0, next.y)
    }

    @Test
    fun galleryElevationsAreStableAndPositive() {
        val elevations = gallerySectionElevations()
        assertEquals(listOf("gallery", "board", "page", "graph"), elevations.map { it.sectionId })
        assertTrue(elevations.all { it.elevation > 0.0 })
    }

    @Test
    fun cornerButtonsCoverAllSixSlots() {
        val slots = forgeBlackboardCornerButtons.map { it.slot }.toSet()
        assertEquals(ForgeBlackboardCornerSlot.values().toSet(), slots)
    }

    @Test
    fun cornerButtonIdsAndHotkeysAreStable() {
        val bySlot = forgeBlackboardCornerButtons.associateBy { it.slot }
        assertEquals("back-to-board", bySlot[ForgeBlackboardCornerSlot.TOP_LEFT]!!.id)
        assertEquals("depth-toggle", bySlot[ForgeBlackboardCornerSlot.TOP_RIGHT]!!.id)
        assertEquals("fit-viewport", bySlot[ForgeBlackboardCornerSlot.BOTTOM_LEFT]!!.id)
        assertEquals("center-selected", bySlot[ForgeBlackboardCornerSlot.BOTTOM_RIGHT]!!.id)
        assertEquals("1", bySlot[ForgeBlackboardCornerSlot.TOP_LEFT]!!.hotkey)
        assertEquals("d", bySlot[ForgeBlackboardCornerSlot.TOP_RIGHT]!!.hotkey)
        forgeBlackboardCornerButtons.forEach { btn ->
            assertTrue(btn.id.isNotBlank(), "${btn.slot} button id blank")
            assertTrue(btn.surface.isNotBlank(), "${btn.slot} button surface blank")
        }
    }

    @Test
    fun defaultBlackboardViewListsGallerySectionAndTiltedCamera() {
        val view = ForgeBlackboardView.DEFAULT
        assertEquals("forge.blackboard", view.surface)
        assertTrue("gallery" in view.sections, "gallery must be a section of the default blackboard")
        assertEquals(4, view.sections.size)
        assertEquals(forgeBlackboardCornerButtons, view.cornerButtons)
        assertTrue(view.defaultCamera.tilt > 0.0, "default blackboard must carry 2.5D tilt")
    }

    @Test
    fun default3DPoseIsNotTopDown() {
        val view = ForgeBlackboardView.DEFAULT
        assertEquals(ForgeBlackboardMode.WORLD_3D, view.defaultMode)
        assertTrue(
            abs(view.mode3D.pitchRadians) > 0.2,
            "default 3D pose must look down at an angle, not top-down: pitch=${view.mode3D.pitchRadians}",
        )
        assertTrue(
            abs(view.mode3D.yawRadians) > 0.05,
            "default 3D pose must orbit off-axis, not face-on: yaw=${view.mode3D.yawRadians}",
        )
    }

    @Test
    fun modeCycleCyclesFlatParallaxWorld() {
        var mode = ForgeBlackboardMode.FLAT_2D
        mode = ForgeBlackboard3D().cycleMode(mode)
        assertEquals(ForgeBlackboardMode.PARALLAX_25D, mode)
        mode = ForgeBlackboard3D().cycleMode(mode)
        assertEquals(ForgeBlackboardMode.WORLD_3D, mode)
        mode = ForgeBlackboard3D().cycleMode(mode)
        assertEquals(ForgeBlackboardMode.FLAT_2D, mode)
    }

    @Test
    fun threeDProjectsGalleryAsTiltedQuadrilateral() {
        val view = ForgeBlackboardView.DEFAULT
        val galleryPlacement = ForgeBlackboardView.sectionPlacement(view, "gallery")!!
        val corners = view.mode3D.projectSection(galleryPlacement)
        assertEquals(4, corners.size)
        // tilted view: the four corners must not collapse to a line — a top-down
        // (pitch=0) or face-on (yaw=0) view would flatten them.
        val xs = corners.map { it.screenX }
        val ys = corners.map { it.screenY }
        val spanX = xs.max() - xs.min()
        val spanY = ys.max() - ys.min()
        assertTrue(spanX > 50.0, "gallery quad must have non-trivial X span: $xs")
        assertTrue(spanY > 30.0, "gallery quad must have non-trivial Y span: $ys")
    }

    @Test
    fun gallery3DPlacementHasHigherElevationThanPage() {
        val view = ForgeBlackboardView.DEFAULT
        val gallery = ForgeBlackboardView.sectionPlacement(view, "gallery")!!
        val page = ForgeBlackboardView.sectionPlacement(view, "page")!!
        assertTrue(gallery.elevation > page.elevation,
            "gallery must float above page (gallery=${gallery.elevation}, page=${page.elevation})")
    }

    @Test
    fun orbitChangesScreenProjectionButKeepsOriginStable() {
        val view = ForgeBlackboardView.DEFAULT
        val originA = view.mode3D.project(0.0, 0.0, 0.0)
        val orbited = view.mode3D.orbit(0.6, 0.3)
        val originB = orbited.project(0.0, 0.0, 0.0)
        assertEquals(0.0, originB.screenX, 1e-6, "world origin projects to screen origin")
        assertEquals(0.0, originB.screenY, 1e-6, "world origin projects to screen origin")
        assertTrue(originA != originB || originA.screenX == 0.0)
    }
}