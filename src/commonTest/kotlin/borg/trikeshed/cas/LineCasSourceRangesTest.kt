package borg.trikeshed.cas

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

class LineCasSourceRangesTest {
    private fun texts(text: String): List<String> =
        LineCasSourceRanges.of(text).let { r -> (0 until r.size).map { i -> text.substring(r[i].text.start, r[i].text.end) } }

    @Test
    fun normalizedOrdinalsResolveThroughRawBlankLines() {
        // CRLF, lone CR, LF, tabs, spaces and blank/space-only lines all in one document.
        val text = "  alpha  \n\n\t\nbeta\r\n   \r\ngamma\rdelta"
        val spine = LineCas.spine(text)
        val ranges = LineCasSourceRanges.of(text)

        assertEquals(listOf("alpha", "beta", "gamma", "delta"), texts(text))
        assertEquals(spine.size, ranges.size)
        for (i in 0 until spine.size) {
            val r = ranges[i]
            assertEquals(spine[i].contentCid, LineCas.contentOf(text.substring(r.text.start, r.text.end)), "ordinal $i")
        }
        // Raw line numbering follows lineSequence(): 0 "  alpha  ", 1 "", 2 "\t", 3 "beta", 4 "   ", 5 "gamma", 6 "delta".
        assertEquals(listOf(0, 3, 5, 6), (0 until ranges.size).map { ranges[it].index })
        assertEquals(text.lineSequence().toList()[3], text.substring(ranges[1].line.start, ranges[1].line.end))
    }

    @Test
    fun theWholeLineKeepsItsIndentation() {
        val text = "fun f() {\n    return 1\n}\n"
        val ranges = LineCasSourceRanges.of(text)
        assertEquals("    return 1", text.substring(ranges[1].line.start, ranges[1].line.end))
        assertEquals("return 1", text.substring(ranges[1].text.start, ranges[1].text.end))
    }

    @Test
    fun finalLineWithoutTerminatorAndTrailingBlanksAreHandled() {
        assertEquals(listOf("a", "b"), texts("a\n\nb"))
        assertEquals(listOf("a", "b"), texts("a\nb\n\n   \n"))
        assertEquals(listOf("a"), texts("\n\n  a"))
    }

    @Test
    fun emptyAndWhitespaceOnlyTextHaveNoRanges() {
        assertEquals(0, LineCasSourceRanges.of("").size)
        assertEquals(0, LineCasSourceRanges.of(" \t\r\n\r\n \n").size)
    }

    @Test
    fun offsetsAreUtf16UnitsOfTheOriginal() {
        // A supplementary-plane character is two UTF-16 units; the range must index the original string.
        val text = "\uD83D\uDE00 x\n\n  caf\u00e9\n"
        assertEquals(listOf("\uD83D\uDE00 x", "caf\u00e9"), texts(text))
        val ranges = LineCasSourceRanges.of(text)
        assertEquals(0, ranges[0].text.start)
        assertEquals(4, ranges[0].text.end)
    }

    @Test
    fun rawLinesCoverEveryLineAndMatchLineSequence() {
        for (text in listOf("", "a", "a\n", "\n", "a\n\nb", "a\r\nb\rc\n\n", "  x  \n\t\ny")) {
            val raw = LineCasSourceRanges.rawLines(text)
            val expected = text.lineSequence().toList()
            assertEquals(expected.size, raw.size, "line count for ${text.replace("\n", "\\n").replace("\r", "\\r")}")
            assertEquals(expected, (0 until raw.size).map { text.substring(raw[it].start, raw[it].end) })
        }
        val text = "one\r\n\r\nthree\nfour"
        val raw = LineCasSourceRanges.rawLines(text)
        assertEquals("one\r\n\r\nthree", text.substring(raw[0].start, raw[2].end), "a span keeps its original terminators")
    }

    @Test
    fun spineAndRangesAgreeOnGeneratedDocuments() {
        val pieces = listOf("a", "bb", " ", "\t", "\n", "\r", "\r\n", "\u00e9", "\uD83D\uDE00", "x y", "  ")
        var seed = 12345L
        fun next(bound: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % bound).toInt()
        }
        repeat(400) { round ->
            val text = buildString { repeat(next(40)) { append(pieces[next(pieces.size)]) } }
            val spine = LineCas.spine(text)
            val ranges = LineCasSourceRanges.of(text)
            assertEquals(spine.size, ranges.size, "round $round: ${text.length} chars")
            for (i in 0 until spine.size) {
                val t = ranges[i].text
                assertEquals(spine[i].contentCid, LineCas.contentOf(text.substring(t.start, t.end)), "round $round ordinal $i")
            }
        }
    }
}
