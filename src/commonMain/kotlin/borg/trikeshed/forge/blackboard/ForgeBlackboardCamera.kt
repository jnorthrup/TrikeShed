package borg.trikeshed.forge.blackboard

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which blackboard projection is currently active.  The corner/title [d]epth
 * toggle cycles through these in order.  Stays in `commonMain` so the JVM
 * printer and the browser renderer agree on the same projection math.
 */
enum class ForgeBlackboardMode {
    FLAT_2D,        // plain pan + zoom, no tilt
    PARALLAX_25D,   // existing 2.5D tilt + per-section elevation
    WORLD_3D,       // true 3D: yaw, pitch, perspective, sections live on the xy plane
}

/**
 * Pose of the 3D blackboard camera.  Independent from [ForgeBlackboardCamera] so
 * we can interpolate cleanly between modes — FLAT_2D ignores yaw/pitch,
 * WORLD_3D ignores pan, PARALLAX_25D uses both for the elevation offset.
 */
data class ForgeBlackboard3D(
    val yawRadians: Double = -0.35,       // gentle off-axis orbit around world Y
    val pitchRadians: Double = 0.55,      // tilted so gallery reads as a plane, never top-down
    val distance: Double = 1200.0,       // world units from origin; >0
    val focalLength: Double = 900.0,     // perspective focal length in world units
    val minDistance: Double = 480.0,
    val maxDistance: Double = 4800.0,
) {
    init {
        require(distance in minDistance..maxDistance) {
            "distance $distance outside [$minDistance, $maxDistance]"
        }
        require(focalLength > 0.0) { "focal length must be positive" }
    }

    /** Project a world-space point (x, y, z) into 2D screen pixels at depth=0. */
    fun project(x: Double, y: Double, z: Double = 0.0): ProjectedPoint {
        val cosY = cos(yawRadians)
        val sinY = sin(yawRadians)
        val rotatedX = x * cosY + z * sinY
        val rotatedZ = -x * sinY + z * cosY
        val cosP = cos(pitchRadians)
        val sinP = sin(pitchRadians)
        val rotatedY = y * cosP - rotatedZ * sinP
        val depthZ = y * sinP + rotatedZ * cosP + distance
        val safeZ = if (depthZ <= 1.0) 1.0 else depthZ
        return ProjectedPoint(
            screenX = (rotatedX / safeZ) * focalLength,
            screenY = (rotatedY / safeZ) * focalLength,
        )
    }

    /**
     * Four corner screen projections for a section rectangle.  Corners are
     * returned in world-space order (TL, TR, BR, BL) so the renderer can draw
     * the quadrilateral without further bookkeeping.
     */
    fun projectSection(section: ForgeBlackboardSection3D): List<ProjectedPoint> {
        val hw = section.width / 2.0
        val hh = section.height / 2.0
        val z = section.elevation
        return listOf(
            project(section.centerX - hw, section.centerY - hh, z), // top-left
            project(section.centerX + hw, section.centerY - hh, z), // top-right
            project(section.centerX + hw, section.centerY + hh, z), // bottom-right
            project(section.centerX - hw, section.centerY + hh, z), // bottom-left
        )
    }

    fun zoom(targetDistance: Double): ForgeBlackboard3D =
        copy(distance = targetDistance.coerceIn(minDistance, maxDistance))

    fun orbit(deltaYaw: Double, deltaPitch: Double): ForgeBlackboard3D = copy(
        yawRadians = yawRadians + deltaYaw,
        pitchRadians = (pitchRadians + deltaPitch).coerceIn(-1.35, 1.35),
    )

    fun cycleMode(mode: ForgeBlackboardMode): ForgeBlackboardMode = when (mode) {
        ForgeBlackboardMode.FLAT_2D -> ForgeBlackboardMode.PARALLAX_25D
        ForgeBlackboardMode.PARALLAX_25D -> ForgeBlackboardMode.WORLD_3D
        ForgeBlackboardMode.WORLD_3D -> ForgeBlackboardMode.FLAT_2D
    }
}

/**
 * Position of a single section on the 3D blackboard plane.  The default layout
 * places the four sections in a 2x2 grid with a slight elevation per the
 * PARALLAX_25D camera so the depth toggle has something to interpolate.
 */
data class ForgeBlackboardSection3D(
    val sectionId: String,
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val elevation: Double,
)

/** Default 3D layout — gallery is the top-right quadrant. */
val forgeBlackboardDefault3DLayout: List<ForgeBlackboardSection3D> = listOf(
    ForgeBlackboardSection3D("page", -640.0, -380.0, 620.0, 360.0, 8.0),
    ForgeBlackboardSection3D("board", 640.0, -380.0, 620.0, 360.0, 16.0),
    ForgeBlackboardSection3D("gallery", -640.0, 380.0, 620.0, 360.0, 28.0),
    ForgeBlackboardSection3D("graph", 640.0, 380.0, 620.0, 360.0, 22.0),
)

/**
 * Where a placeholder button sits on the blackboard chrome.  The corner/title
 * slots are stub-only — they describe the layout of the zoom/pan/reset widgets
 * that the renderer will eventually mount.
 */
enum class ForgeBlackboardCornerSlot {
    TOP_LEFT,      // back to whole-board view
    TOP_RIGHT,     // mini-map / depth toggle (2.5D effect intensity)
    BOTTOM_LEFT,   // "fit to viewport" button
    BOTTOM_RIGHT,  // "center on selected"
    TITLE_LEFT,    // page title chip
    TITLE_RIGHT,   // zoom-level pill
}

/**
 * Geometry + affordance for a single placeholder button.  Stays data-only so the
 * JVM printer and the browser renderer agree on the exact layout.  When the
 * renderer mounts a real button it copies the slot's [id] and [hotkey].
 */
data class ForgeBlackboardButton(
    val slot: ForgeBlackboardCornerSlot,
    val id: String,
    val label: String,
    val hotkey: String,
    val surface: String,
)

/**
 * Layout of the placeholder controls.  Listed in render order so the JS side
 * paints them deterministically.
 */
val forgeBlackboardCornerButtons: List<ForgeBlackboardButton> = listOf(
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.TOP_LEFT, "back-to-board", "Whole board", "1", "top-left"),
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.TOP_RIGHT, "depth-toggle", "2.5D depth", "d", "top-right"),
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.BOTTOM_LEFT, "fit-viewport", "Fit", "f", "bottom-left"),
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.BOTTOM_RIGHT, "center-selected", "Center", "c", "bottom-right"),
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.TITLE_LEFT, "page-title", "Title chip", "t", "title-left"),
    ForgeBlackboardButton(ForgeBlackboardCornerSlot.TITLE_RIGHT, "zoom-pill", "Zoom pill", "z", "title-right"),
)

/**
 * Description of the blackboard view itself.  This is a stub — no live
 * widget renders it yet — but it pins the surface name, the section count, the
 * default camera, the corner/title button set, the 3D mode, and the 3D section
 * layout so a renderer can lift the data straight into HTML/Compose without
 * negotiation.
 */
data class ForgeBlackboardView(
    val surface: String,
    val sections: List<String>,
    val defaultCamera: ForgeBlackboardCamera,
    val cornerButtons: List<ForgeBlackboardButton>,
    val defaultMode: ForgeBlackboardMode = ForgeBlackboardMode.PARALLAX_25D,
    val mode3D: ForgeBlackboard3D = ForgeBlackboard3D(),
    val layout3D: List<ForgeBlackboardSection3D> = forgeBlackboardDefault3DLayout,
) {
    init {
        require(surface.isNotBlank()) { "blackboard surface must be named" }
        require(sections.isNotEmpty()) { "blackboard must list at least one section" }
        require(layout3D.map { it.sectionId }.toSet() == sections.toSet()) {
            "3D layout sections ${layout3D.map { it.sectionId }} must match view sections $sections"
        }
    }

    companion object {
        /** The default blackboard — gallery is a section alongside page/board/graph.
         *  Boots in 3D mode so the depth-toggle corner button can cycle back to 2.5D / flat. */
        val DEFAULT: ForgeBlackboardView = ForgeBlackboardView(
            surface = "forge.blackboard",
            sections = listOf("page", "board", "gallery", "graph"),
            defaultCamera = ForgeBlackboardCamera(tilt = 0.18),
            cornerButtons = forgeBlackboardCornerButtons,
            defaultMode = ForgeBlackboardMode.WORLD_3D,
        )

        /** Find the 3D placement of a section, or null if it's not on the board. */
        fun sectionPlacement(view: ForgeBlackboardView, sectionId: String): ForgeBlackboardSection3D? =
            view.layout3D.firstOrNull { it.sectionId == sectionId }
    }
}

/**
 * Camera state for the zoomable forge blackboard.
 *
 * The blackboard holds every section (board, page, gallery, causal graph, …) and
 * the camera is the affine transform that projects section coordinates into the
 * viewport.  It carries momentum — pan/zoom velocities that decay each frame so a
 * flick glides before slowing to rest.  Lives entirely in commonMain so the JVM
 * printer and the browser blackboard agree on the same math.
 *
 * Coordinate convention:
 *  - world units are CSS pixels in the workspace canvas (origin top-left).
 *  - viewport units are CSS pixels in the device frame.
 *  - the projection is: world → translate(-camera.x, -camera.y) → scale(camera.zoom) → translate(tilt)
 *
 * 2.5D effect:
 *  - camera.tilt (radians around the X axis) bends each section toward the
 *    camera in screen space using `worldY * sin(tilt)` for vertical squish and
 *    `worldY * (1 - cos(tilt))` for elevation offset.
 *  - sections can opt in to additional parallax depth via [sectionElevation],
 *    expressed in world pixels of offset from the blackboard plane.
 */
data class ForgeBlackboardCamera(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
    val tilt: Double = 0.0,         // radians; small (~0.0–0.35) for tasteful 2.5D
    val vx: Double = 0.0,           // world-units / second momentum on X
    val vy: Double = 0.0,           // world-units / second momentum on Y
    val vz: Double = 0.0,           // multiplicative zoom velocity per second
    val minZoom: Double = 0.45,
    val maxZoom: Double = 3.2,
) {
    init {
        require(zoom >= minZoom / 4 && zoom <= maxZoom * 4) { "Zoom out of sanity range: $zoom" }
    }

    /** Project a world point into the viewport with the current camera + tilt. */
    fun project(worldX: Double, worldY: Double, depth: Double = 0.0): ProjectedPoint {
        val lift = depth * (1.0 - cos(tilt))
        val scaledY = (worldY - lift) * cos(tilt)
        val parallaxY = depth * sin(tilt)
        return ProjectedPoint(
            screenX = (worldX - x) * zoom,
            screenY = ((scaledY - y) * zoom) + parallaxY * zoom,
        )
    }

    /**
     * Advance the camera by [dtSeconds] seconds, applying friction to velocity.
     * Returns the new camera.  Pure function — caller owns history.
     */
    fun tick(dtSeconds: Double, friction: Double = 0.86): ForgeBlackboardCamera {
        require(dtSeconds > 0.0) { "tick requires positive dt" }
        require(friction in 0.0..1.0) { "friction must be in [0,1]" }
        val dampedVx = vx * friction
        val dampedVy = vy * friction
        val dampedVz = vz * friction
        val nextZoom = (zoom * (1.0 + dampedVz * dtSeconds)).coerceIn(minZoom, maxZoom)
        return copy(
            x = x + dampedVx * dtSeconds,
            y = dampedVy * dtSeconds + y,
            zoom = nextZoom,
            vx = dampedVx,
            vy = dampedVy,
            vz = dampedVz,
        )
    }

    /** Add an instantaneous impulse to the camera velocity (e.g. pointer flick). */
    fun impulse(panVx: Double, panVy: Double, zoomV: Double = 0.0): ForgeBlackboardCamera =
        copy(vx = vx + panVx, vy = vy + panVy, vz = vz + zoomV)

    /** Zoom around a focal point in screen space — keeps that point anchored. */
    fun zoomAround(targetZoom: Double, focusWorldX: Double, focusWorldY: Double): ForgeBlackboardCamera {
        val clamped = targetZoom.coerceIn(minZoom, maxZoom)
        if (clamped == zoom) return this
        // Anchor the focal point: (focus - newCam) * newZoom == (focus - oldCam) * oldZoom
        // so newCam = focus - (focus - oldCam) * oldZoom / newZoom
        val ratio = zoom / clamped
        return copy(
            zoom = clamped,
            x = focusWorldX - (focusWorldX - x) * ratio,
            y = focusWorldY - (focusWorldY - y) * ratio,
            vz = (clamped / zoom - 1.0) * 12.0, // seed momentum so the zoom feels alive
        )
    }

    /** Pan by a delta expressed in screen pixels (positive = drag right/down). */
    fun panByScreen(screenDx: Double, screenDy: Double): ForgeBlackboardCamera =
        copy(x = x - screenDx / zoom, y = y - screenDy / zoom)

    /** A bounded wobble added on top of the projection for the 2.5D parallax surface. */
    fun parallaxOffset(depth: Double): Double = depth * sin(tilt) * zoom

    companion object {
        val IDENTITY = ForgeBlackboardCamera()

        /** Compute the magnitude of the camera velocity — used to stop animating. */
        fun speed(camera: ForgeBlackboardCamera): Double {
            val v = sqrt(camera.vx * camera.vx + camera.vy * camera.vy + camera.vz * camera.vz)
            return v
        }
    }
}

data class ProjectedPoint(val screenX: Double, val screenY: Double)

/**
 * Per-section offset used by the browser blackboard to fake depth.  Section rows
 * can declare their elevation in world pixels; the renderer translates the row by
 * that elevation along the camera tilt axis.  All sections share the same
 * coordinate system — elevation is a renderer hint, not a model field.
 */
data class ForgeSectionElevation(
    val sectionId: String,
    val elevation: Double,
)

/**
 * Compute the per-section elevation list for the gallery so the kitchen-sink
 * tiles float slightly off the blackboard plane and respond to the tilt.
 */
fun gallerySectionElevations(): List<ForgeSectionElevation> = listOf(
    ForgeSectionElevation(sectionId = "gallery", elevation = 28.0),
    ForgeSectionElevation(sectionId = "board", elevation = 12.0),
    ForgeSectionElevation(sectionId = "page", elevation = 6.0),
    ForgeSectionElevation(sectionId = "graph", elevation = 18.0),
)