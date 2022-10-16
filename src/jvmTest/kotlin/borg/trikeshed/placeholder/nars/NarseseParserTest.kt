package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.parser.simple.CharSeries
import kotlin.test.*

class NarseseParserTest {

    private lateinit var cs: CharSeries

    @Test
    fun testWord()    {

        val input: CharSeries    =CharSeries("1234567890")

        val result = NarseseParser.word(input)

        assertEquals("1234567890", result?.asString())

    }
}
