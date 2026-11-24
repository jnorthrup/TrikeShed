package narchy.spacegraph

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * The ground camera's angles: what is under the middle of the view stays there through a turn and a tilt, a pointer
 * maps to the ground point it shows (so a drag and a zoom hold the point in hand) at every pitch and yaw, and the URL
 * form carries both angles. Expected screen points are the viewport's middle, 1280 / 2 and 800 / 2.
 */
class GroundCameraTest {
    val viewport = Viewport(1280, 800)

    @Test
    fun middleStaysPutThroughTurnAndTilt() {
        val camera = GroundCamera(12.5, -40.0, 3.0, pitch = 20.0, yaw = 10.0)
        val under = assertNotNull(camera.ground(640.0, 400.0, viewport))
        assertEquals(12.5, under.x, 1e-9); assertEquals(-40.0, under.y, 1e-9)
        for (moved in s_[camera.turn(75.0), camera.tilt(30.0), camera.turn(-200.0).tilt(-15.0), camera.tilt(55.0).turn(181.0)].view) {
            val p = assertNotNull(moved.project(under, viewport))
            assertEquals(640.0, p.x, 1e-6); assertEquals(400.0, p.y, 1e-6)
        }
        assertEquals(170.0, camera.turn(-200.0).yaw)
        assertEquals(GroundCamera.PITCH_MAX, camera.tilt(90.0).pitch)
        assertEquals(0.0, camera.tilt(-90.0).pitch)
    }

    @Test
    fun pointerMapsToTheGroundPointItShows() {
        val screen = s_[Vec3(100.0, 120.0), Vec3(640.0, 400.0), Vec3(1180.0, 700.0), Vec3(300.0, 650.0)]
        for (pitch in s_[0.0, 35.0, 70.0].view) for (yaw in s_[0.0, 120.0, 250.0].view) {
            val camera = GroundCamera(-334.7, 61.0, 290.0, pitch, yaw)
            for (p in screen.view) {
                val g = assertNotNull(camera.ground(p.x, p.y, viewport), "pitch $pitch yaw $yaw: $p shows the ground")
                assertEquals(0.0, g.z)
                val back = assertNotNull(camera.project(g, viewport))
                assertEquals(p.x, back.x, 1e-6, "pitch $pitch yaw $yaw x"); assertEquals(p.y, back.y, 1e-6, "pitch $pitch yaw $yaw y")
            }
            val point = Vec3(-334.2, 60.6)
            val shown = assertNotNull(camera.project(point, viewport))
            val under = assertNotNull(camera.ground(shown.x, shown.y, viewport))
            assertEquals(point.x, under.x, 1e-9); assertEquals(point.y, under.y, 1e-9)
            // a drag keeps the grabbed ground point under the pointer, and a zoom keeps the one under the wheel
            val grabbed = assertNotNull(camera.ground(200.0, 300.0, viewport))
            val dragged = assertNotNull(camera.drag(200.0, 300.0, 900.0, 500.0, viewport).ground(900.0, 500.0, viewport))
            assertEquals(grabbed.x, dragged.x, 1e-9); assertEquals(grabbed.y, dragged.y, 1e-9)
            val wheeled = assertNotNull(camera.ground(1000.0, 200.0, viewport))
            val zoomed = assertNotNull(camera.zoomAt(2.5, 1000.0, 200.0, viewport).ground(1000.0, 200.0, viewport))
            assertEquals(wheeled.x, zoomed.x, 1e-9); assertEquals(wheeled.y, zoomed.y, 1e-9)
        }
    }

    @Test
    fun urlCarriesBothAngles() {
        val view = GroundView(GroundCamera(-334.68, 61.004, 290.9, pitch = 40.5, yaw = 247.25), _l["Entity", "Physical", "Process"])
        val hash = view.encode()
        assertTrue("pitch=40.5" in hash, hash); assertTrue("yaw=247.25" in hash, hash); assertTrue("path=Entity%2FPhysical%2FProcess" in hash, hash)
        assertEquals(view, GroundView.decode(hash))
        assertEquals(GroundView(GroundCamera(1.0, 2.0, 3.0)), GroundView.decode("#x=1&y=2&z=3"))
        assertNull(GroundView.decode("#x=1&y=2&z=3&pitch=80"))
        assertNull(GroundView.decode("#x=1&y=2"))
    }
}
