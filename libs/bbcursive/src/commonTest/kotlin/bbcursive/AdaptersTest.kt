package bbcursive

import kotlin.test.Test
import kotlin.test.assertEquals
import bbcursive.adapters.Adapters
import borg.trikeshed.lib.*

class AdaptersTest {
    @Test
    fun testStrFromSeries() {
        val s = "hello".toSeries()
        val out = Adapters.str(s)
        assertEquals("hello", out)
    }
}
