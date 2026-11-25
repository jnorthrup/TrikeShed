package narchy.spacegraph

import borg.trikeshed.landscape.LandscapeMomentum
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoHyperarchy
import kotlin.test.*

/**
 * The ground scene's glide and its raised homes, over a hyperarchy of four classes written here: Thing over Animal
 * and Plant, Animal over Dog. Expected values are the scene's own constants applied by hand.
 */
class GroundSceneTest {
    private val viewport = Viewport(1000, 800)
    private val hyperarchy = SumoHyperarchy(arrayOf("Thing", "Animal", "Plant", "Dog"), intArrayOf(1, 0, 2, 0, 3, 1))
    private fun scene() = GroundScene(viewport).also { it.load(hyperarchy) }

    @Test
    fun aFlickGlidesOnAndComesToRestAndAHeldReleaseDoesNot() {
        val scene = scene()
        val before = scene.camera
        scene.press(500.0, 400.0, 0.0); scene.pan(490.0, 400.0, 8.0); scene.pan(470.0, 400.0, 16.0); scene.pan(440.0, 400.0, 24.0)
        val dragged = scene.camera
        assertNotEquals(before.x, dragged.x, "the drag moved the camera")
        assertFalse(scene.release(30.0), "a pan's release is not the slider's")
        var now = 30.0; var frames = 0
        var last = scene.camera.x
        while (scene.glide(now + 16.7)) {
            now += 16.7; frames++
            assertTrue((scene.camera.x - last) * (dragged.x - before.x) >= 0, "the glide goes the way the drag went")
            last = scene.camera.x
            assertTrue(frames < 2000, "the glide comes to rest")
        }
        assertTrue(frames > 10, "the flick glided for more than ten frames, not $frames")
        assertNotEquals(dragged.x, scene.camera.x)
        assertFalse(scene.glide(now + 16.7), "at rest nothing moves")

        // held still for longer than a flick before letting go: no glide
        scene.press(500.0, 400.0, 5000.0); scene.pan(440.0, 400.0, 5024.0); scene.release(5024.0 + LandscapeMomentum.FLICK_MS + 1)
        assertFalse(scene.momentum.live)
        // a new press stops a glide in flight
        scene.press(500.0, 400.0, 6000.0); scene.pan(440.0, 400.0, 6008.0); scene.release(6010.0)
        assertTrue(scene.momentum.live)
        scene.press(300.0, 300.0, 6020.0)
        assertFalse(scene.momentum.live)
    }

    @Test
    fun aHomeWithinTheViewsRangeStandsRaisedByItsSizeAndOneFarLargerLiesFlat() {
        val scene = scene()
        val thing = hyperarchy.index("Thing"); val animal = hyperarchy.index("Animal"); val dog = hyperarchy.index("Dog")
        // Thing half the view across: within range, raised whole; its children on it, raised by their own size
        val home = hyperarchy.home(thing)
        scene.camera = GroundCamera(home.x, home.y, zoom = 400 / (2 * home.r), pitch = 40.0)
        scene.frame()
        assertEquals(GroundScene.RISE * home.r, scene.altitude[thing], 1e-9)
        assertEquals(scene.altitude[thing] + GroundScene.RISE * hyperarchy.home(animal).r, scene.altitude[animal], 1e-9)
        assertTrue(scene.altitude[dog] > scene.altitude[animal], "a home stands above the one it is in")
        // Thing three views across: beyond RANGE_FAR, flat; Animal is raised from the ground only by its own size, if at all
        scene.camera = scene.camera.copy(zoom = 3 * 800 / (2 * home.r))
        scene.frame()
        assertEquals(0.0, scene.altitude[thing])
        assertTrue(scene.altitude[animal] <= GroundScene.RISE * hyperarchy.home(animal).r + 1e-9)
        // flat by the key: nothing is raised
        scene.camera = GroundCamera(home.x, home.y, zoom = 400 / (2 * home.r), pitch = 40.0)
        assertTrue(scene.key("R")); assertFalse(scene.relief)
        scene.frame()
        assertTrue(scene.altitude.all { it == 0.0 })
    }

    @Test
    fun aClickTakesTheRaisedHomeUnderThePointer() {
        val scene = scene()
        val animal = hyperarchy.index("Animal")
        val home = hyperarchy.home(hyperarchy.index("Thing"))
        scene.camera = GroundCamera(home.x, home.y, zoom = 700 / (2 * home.r), pitch = 60.0, yaw = 30.0)
        scene.frame()
        val at = hyperarchy.home(animal)
        // the middle of Animal's raised top, on screen
        val top = assertNotNull(scene.camera.project(Vec3(at.x, at.y, scene.altitude[animal]), viewport))
        val foot = assertNotNull(scene.camera.project(Vec3(at.x, at.y, 0.0), viewport))
        assertTrue(foot.y - top.y > 3, "the top is drawn above the foot: ${foot.y - top.y} px")
        scene.click(top.x, top.y)
        val reached = scene.navigation!!.current
        assertTrue(reached == animal || hyperarchy.path(reached).let { p -> (0 until p.size).any { p[it] == animal } }, "the click reached Animal or a class in it, not ${hyperarchy.names[reached]}")
    }

    @Test
    fun theDetailSliderSetsHowDeepTheViewOpens() {
        val scene = scene()
        assertEquals(GroundScene.OPEN_PX, scene.openPx, 1e-9)
        scene.frame()
        val track = assertNotNull(scene.slider)
        // a press on the track's right end takes the slider, sets full detail, and is not a click
        scene.press(track.right - .5, track.y + track.height / 2, 0.0)
        assertTrue(scene.sliding)
        scene.pan(track.right + 40, track.y + 2, 8.0)
        assertEquals(100.0, scene.detail)
        assertEquals(GroundScene.OPEN_PX / 4, scene.openPx, 1e-9)
        assertTrue(scene.release(16.0))
        assertFalse(scene.momentum.live)
        scene.press(track.x, track.y + 2, 100.0); scene.release(110.0)
        assertEquals(0.0, scene.detail)
        assertEquals(GroundScene.OPEN_PX * 4, scene.openPx, 1e-9)
    }
}
