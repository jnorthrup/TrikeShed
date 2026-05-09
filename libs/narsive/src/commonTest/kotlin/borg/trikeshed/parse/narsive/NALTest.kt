package borg.trikeshed.parse.narsive

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertNotNull

class NALTest {
    // 6a — NAL1 inheritance
    @Test
    fun `parses inheritance relationship`() {
        val source = "(bird --> animal).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)
    }

    // 6b — NAL3 implication
    @Test
    fun `parses implication`() {
        val source = "((bird --> animal) ==> (robin --> animal)).".toSeries()
        val parsed = Narsive.parseSentence(source)
        assertNotNull(parsed)
    }
}
