package narchy.spacegraph.motion

// Upstream test/motion.test.ts at 125b369: the motion director, spring curves, and camera verbs cases, expected values
// copied from upstream.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class MotionTest {
    private val steps = listOf(
        MotionStep("a", MotionVerb.arrive, MotionTarget.camera, from = 0.0, to = 1.0, duration = 1.0),
        MotionStep("b", MotionVerb.emphasize, MotionTarget.emphasis, from = 1.0, to = 0.0, duration = 1.0),
    )

    private class AdapterLog {
        val calls = mutableListOf<String>()
        val adapter = object : CameraAdapter {
            override fun fit(ids: List<String>?, duration: Double) { calls += "fit:${ids?.joinToString(",") ?: ""}:$duration" }
            override fun focus(id: String, duration: Double) { calls += "focus:$id:$duration" }
            override fun flyTo(position: Triple<Double, Double, Double>, target: Triple<Double, Double, Double>, duration: Double) {
                calls += "flyTo:${position.toList().joinToString(",")}:${target.toList().joinToString(",")}"
            }
            override fun orbit(amount: Double, duration: Double) { calls += "orbit:$amount" }
            override fun zoom(amount: Double, duration: Double) { calls += "zoom:$amount" }
            override fun reset() { calls += "reset" }
        }
    }

    @Test
    fun declaresEveryNamedVerbAndNoLibraryDefault() {
        assertEquals(
            listOf("arrive", "emphasize", "trace", "flyTo", "sweep", "follow", "decay", "compact"),
            MotionVerb.entries.map { it.name },
        )
    }

    @Test
    fun playsStaggeredStepsWithEasedValuesAndStableIds() {
        val seen = mutableListOf<MotionFrame>()
        val playback = playMotion(steps, { seen += it }, MotionDirectorOptions(ease = "linear", stagger = 0.5))
        assertEquals(1.5, playback.duration, 1e-9)
        assertEquals(1, playback.advance(0.25).size)
        assertEquals(2, playback.advance(0.5).size)
        assertEquals("a", seen[0].step.id)
        assertEquals(0.25, seen[0].value, 1e-9)
        assertEquals(listOf("a", "b"), playback.active)
        playback.finish()
        assertEquals(0.0, seen.last().value, 1e-9)
        assertTrue(playback.done)
    }

    @Test
    fun emitsOnlyFinalFramesUnderReducedMotion() {
        val seen = mutableListOf<MotionFrame>()
        val playback = playMotion(steps, { seen += it }, MotionDirectorOptions(reducedMotion = true))
        assertTrue(playback.done)
        assertEquals(listOf(1.0, 0.0), seen.map { it.value })
        assertEquals(0, playback.advance(1.0).size)
    }

    @Test
    fun routesFramesOnlyToVerbsAHostRegistered() {
        val applied = mutableListOf<String>()
        val director = MotionDirector().register(MotionVerb.arrive) { applied += it.step.id }
        val playback = director.play(steps, MotionDirectorOptions(reducedMotion = true))
        assertEquals(2, playback.frames.size)
        assertEquals(listOf("a"), applied)
        assertEquals(listOf(MotionVerb.arrive), director.verbs())
    }

    @Test
    fun providesAnalyticSpringCurvesWithStableEndpoints() {
        val spring = springEasing(SPRING_PRESETS.snappy)
        assertEquals(0.0, spring(0.0), 1e-9)
        assertEquals(1.0, spring(1.0), 1e-9)
    }

    @Test
    fun sequencesNeutralDefaultsAgainstTheHostAdapter() {
        val log = AdapterLog()
        val registry = CameraVerbRegistry()
        registry.run(CameraCommand(CameraVerb.fit, ids = listOf("n1"), duration = 2.0), log.adapter)
        registry.run(CameraCommand(CameraVerb.focus, ids = listOf("n2")), log.adapter)
        registry.run(CameraCommand(CameraVerb.zoom, amount = 1.5), log.adapter)
        registry.run(CameraCommand(CameraVerb.reset), log.adapter)
        assertEquals(listOf("fit:n1:2.0", "focus:n2:1.0", "zoom:1.5", "reset"), log.calls)
        assertTrue(CAMERA_VERBS.contains(CameraVerb.orbit))
    }

    @Test
    fun letsAHostOverrideAVerbWithoutEditingTheLibrary() {
        val log = AdapterLog()
        val registry = CameraVerbRegistry().register(CameraVerb.orbit) { command, target ->
            target.orbit((command.amount ?: 0.0) * 2, 0.0)
        }
        registry.run(CameraCommand(CameraVerb.orbit, amount = 0.25), log.adapter)
        assertEquals(listOf("orbit:0.5"), log.calls)
    }

    @Test
    fun exposesOrthographicOrbitAndContentRelativeZoomAsPolicyNotDefault() {
        assertFalse(orbitEnabled(true))
        assertTrue(orbitEnabled(true, DEFAULT_CAMERA_POLICY.copy(orbitInOrthographic = true)))
        assertTrue(orbitEnabled(false))
        assertTrue(abs(contentZoomBounds(10.0).min - 10.0 / 3.0) < 1e-9)
        assertEquals(25.0, contentZoomBounds(10.0).max, 1e-9)
        assertEquals(
            ZoomBounds(0.01, 100.0),
            contentZoomBounds(10.0, DEFAULT_CAMERA_POLICY.copy(contentRelativeZoom = false)),
        )
    }
}
