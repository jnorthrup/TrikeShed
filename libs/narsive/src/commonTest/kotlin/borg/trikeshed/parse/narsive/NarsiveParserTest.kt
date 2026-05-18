package borg.trikeshed.parse.narsive

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertNotNull

class NarsiveParserTest {
    // 7a — task with budget+truth
    @Test
    fun `parses task with budget truth and relationship`() {
        val parsed = Narsive.parseTask("""$0.8;0.5$ (bird --> animal). %1.0;0.9%""".toSeries())
        assertNotNull(parsed)
    }

    // 7b — nested compound no recursion
    @Test
    fun `nested compound does not poison grammar`() {
        val parsed = Narsive.parseSentence("(&&,(bird --> animal),(animal --> mortal)).".toSeries())
        assertNotNull(parsed)
    }
}
