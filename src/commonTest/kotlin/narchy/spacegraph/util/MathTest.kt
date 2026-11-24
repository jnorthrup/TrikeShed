package narchy.spacegraph.util

import kotlin.test.Test
import kotlin.test.assertEquals

// Upstream src/utils/math.ts has no test file. Expected values below are worked by hand from its source:
// seed 1 gives 1 * 1103515245 + 12345 = 1103527590, which is below 2^31, so both variants keep it unchanged.
class MathTest {
    @Test fun clampBoundsValue() {
        assertEquals(1.0, clamp(5.0, 0.0, 1.0))
        assertEquals(0.0, clamp01(-2.0))
    }

    @Test fun lerpInterpolates() {
        assertEquals(5.0, lerp(0.0, 10.0, 0.5))
    }

    @Test fun seededRandomFirstOutputFromSeedOne() {
        assertEquals(1103527590.0 / 4294967296.0, seededRandom(1.0)())
    }

    @Test fun seededRandom31FirstOutputFromSeedOne() {
        assertEquals(1103527590.0 / 2147483647.0, seededRandom31(1.0)())
    }
}
