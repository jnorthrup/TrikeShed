package borg.trikeshed.animation

import kotlin.test.Test
import kotlin.test.assertEquals

class AnimationSpecTest {

    @Test
    fun testAnimationSpec() {
        val spec = animationSpec {
            duration = 1000L
            ease = Easing.Linear
            interpolator = { fraction -> fraction * 2.0 }
        }

        assertEquals(1000L, spec.duration)
        assertEquals(0.5, spec.ease(0.5))
        assertEquals(1.0, spec.interpolate(0.5))
    }
}
