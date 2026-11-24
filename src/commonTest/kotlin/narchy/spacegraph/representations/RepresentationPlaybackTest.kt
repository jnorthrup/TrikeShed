package narchy.spacegraph.representations

// Upstream test/representation-playback.test.ts at 125b369, 5 cases, expected values copied from upstream.
// Deviation: upstream's `player.active` is `activeIds` here; the Kotlin player exposes ids, not swap objects.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val CHANGE = RepresentationTween(id = "n1", from = "dot", to = "widget", duration = 0.5)

private data class Frame(val id: String, val value: Double)

class RepresentationPlaybackTest {
    // 'emits an eased value at each step and keeps the stable id'
    @Test
    fun emitsAnEasedValueAtEachStepAndKeepsTheStableId() {
        val frames = mutableListOf<Frame>()
        val player = playRepresentationChanges(
            changes = listOf(CHANGE),
            interpolate = { tween: RepresentationTween, value: Double -> frames.add(Frame(tween.id, value)) },
        )

        assertEquals(listOf("n1"), player.activeIds)
        player.advance(0.25)
        assertEquals(Frame("n1", 0.875), frames.last())
        player.advance(0.25)
        assertEquals(Frame("n1", 1.0), frames.last())
        assertTrue(player.done)
        assertEquals(emptyList(), player.activeIds)
        assertTrue(frames.all { it.id == "n1" })
    }

    // 'plays every swap with its own duration and drops finished ones'
    @Test
    fun playsEverySwapWithItsOwnDurationAndDropsFinishedOnes() {
        val seen = mutableListOf<String>()
        val player = playRepresentationChanges(
            changes = listOf(CHANGE, RepresentationTween(id = "n2", from = "card", to = "detail", duration = 1.0)),
            interpolate = { tween: RepresentationTween, value: Double -> if (value >= 1.0) seen.add(tween.id) },
        )
        player.advance(0.5)
        assertEquals(listOf("n1"), seen)
        assertEquals(listOf("n2"), player.activeIds)
        player.finish()
        assertEquals(listOf("n1", "n2"), seen)
        assertTrue(player.done)
    }

    // 'jumps straight to the final frame under reduced motion'
    @Test
    fun jumpsStraightToTheFinalFrameUnderReducedMotion() {
        val values = mutableListOf<Double>()
        val player = playRepresentationChanges(
            listOf(CHANGE),
            { _, value -> values.add(value) },
            RepresentationPlaybackOptions(reducedMotion = true),
        )
        assertEquals(listOf(1.0), values)
        assertTrue(player.done)
        player.advance(10.0)
        assertEquals(listOf(1.0), values)
    }

    // 'treats a zero duration as an instant swap'
    @Test
    fun treatsAZeroDurationAsAnInstantSwap() {
        val values = mutableListOf<Double>()
        val player = playRepresentationChanges(
            listOf(CHANGE.copy(duration = 0.0)),
            { _, value -> values.add(value) },
        )
        assertEquals(emptyList(), values)
        assertTrue(player.done)
    }

    // 'exposes the gsap-equivalent easing family'
    @Test
    fun exposesTheGsapEquivalentEasingFamily() {
        assertEquals(0.5, REPRESENTATION_EASINGS.getValue("linear")(0.5))
        assertEquals(0.75, REPRESENTATION_EASINGS.getValue("power1.out")(0.5))
        assertEquals(1.0, REPRESENTATION_EASINGS.getValue("power2.out")(1.0))
        assertEquals(0.5, REPRESENTATION_EASINGS.getValue("power2.inOut")(0.5))
    }
}
