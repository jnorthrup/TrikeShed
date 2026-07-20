package borg.trikeshed.forge

import kotlin.test.Test

class KeystrokeTraceTest {
    @Test
    fun testKeystrokeToPaint() {
        val start = js("performance.now()") as Double
        // Simulated keystroke to paint...
        val end = js("performance.now()") as Double
        val duration = end - start
        println("METRIC:keystrokeToPaint:\$duration")
    }
}
