package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * What the Confix columns of a JSON text say against what the text says: an integer keeps every bit it was written with, a number
 * past 64 bits is a Double and never a wrapped or clamped Long, a mixed column shows an integer with every digit, and a `\u` takes
 * four hex digits and nothing else (not a sign).
 */
class ConfixJsonNumbersTest {
    private fun column(json: String, c: Int): List<Any?> {
        val cursor = confixDoc(json).index.facet(ConfixIndexK.Columns)
        return (0 until cursor.size).map { cursor[it].values[c] }
    }

    @Test fun integersKeepEveryBit() {
        assertEquals(
            listOf(9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE),
            column("""[{"id":9007199254740993},{"id":9223372036854775807},{"id":-9223372036854775808}]""", 0),
        )
    }

    @Test fun anIntegerPastSixtyFourBitsMakesTheColumnDouble() {
        assertEquals(listOf(1.0, 9.223372036854775808E18), column("""[{"id":1},{"id":9223372036854775808}]""", 0))
        assertEquals(listOf(-9.223372036854775809E18, 2.0), column("""[{"id":-9223372036854775809},{"id":2}]""", 0))
        // far enough out that a clamp to Long.MAX_VALUE is not the same Double as the number
        assertEquals(listOf(1.0, 12345678901234567890.0), column("""[{"id":1},{"id":12345678901234567890}]""", 0))
        assertEquals(listOf(-12345678901234567890.0, 3.0), column("""[{"id":-12345678901234567890},{"id":3}]""", 0))
    }

    @Test fun aMixedColumnShowsAnIntegerWithEveryDigitAndAFractionAsBefore() {
        assertEquals(listOf("12345678901234567890", "x"), column("""[{"v":12345678901234567890},{"v":"x"}]""", 0))
        assertEquals(listOf("-12345678901234567890", "x"), column("""[{"v":-12345678901234567890},{"v":"x"}]""", 0))
        assertEquals(listOf("9007199254740993", "x"), column("""[{"v":9007199254740993},{"v":"x"}]""", 0))
        assertEquals(listOf("-9223372036854775808", "x"), column("""[{"v":-9223372036854775808},{"v":"x"}]""", 0))
        // a fraction or an exponent is what it was: the Double's text
        assertEquals(listOf("1.5", "x"), column("""[{"v":1.5},{"v":"x"}]""", 0))
        assertEquals(listOf("1.0E10", "x"), column("""[{"v":1e10},{"v":"x"}]""", 0))
    }

    @Test fun aDoubleColumnKeepsItsFractionsAndExponents() {
        assertEquals(listOf(1.0, 2.5, 1000.0), column("""[{"v":1},{"v":2.5},{"v":1e3}]""", 0))
    }

    // mlnix's reader read past the end of a `\u` cut short by the end of the string; this one must keep what is there and not fail
    @Test fun aUnicodeEscapeCutShortByTheEndOfTheStringIsKeptAsWritten() {
        assertEquals("\\u", confixDoc("""{"s":"\u"}""").value("s"))
        assertEquals("\\u0", confixDoc("""{"s":"\u0"}""").value("s"))
        assertEquals("\\u004", confixDoc("""{"s":"\u004"}""").value("s"))
        assertEquals("a\\u00", confixDoc("""{"s":"a\u00"}""").value("s"))
        assertEquals("A", confixDoc("""{"s":"\u0041"}""").value("s")) // the shortest that is whole
    }

    @Test fun aSurrogatePairIsOneCodePointOfTwoUnits() {
        assertEquals("\uD83D\uDE00", confixDoc("""{"s":"\ud83d\ude00"}""").value("s"))
    }

    private fun hex4(v: Int, upper: Boolean): String {
        val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        return buildString { for (shift in intArrayOf(12, 8, 4, 0)) append(digits[(v shr shift) and 15]) }
    }

    // all 65,536 escapes, the digits in both cases: this is where `a`, `f`, `A`, `F`, `0` and `9` are told from their neighbours
    @Test fun everyUnicodeEscapeDecodesToItsCodeUnit() {
        for (unit in 0..0xFFFF) for (upper in booleanArrayOf(false, true)) {
            val written = "\\u" + hex4(unit, upper)
            assertEquals(unit.toChar().toString(), confixDoc("{\"s\":\"$written\"}").value("s"), written)
        }
    }

    // each of the four places, each printable byte there: a hex digit is its value, anything else leaves the escape as it was written
    @Test fun aByteThatIsNotAHexDigitLeavesTheEscapeAsWritten() {
        for (place in 0 until 4) for (code in 0x20..0x7E) {
            val x = code.toChar()
            if (x == '"' || x == '\\') continue // those end the string or begin another escape
            val written = buildString { append("\\u"); for (k in 0 until 4) append(if (k == place) x else '0') }
            val isHex = x in '0'..'9' || x in 'a'..'f' || x in 'A'..'F'
            val want = if (isHex) (x.digitToInt(16) shl (4 * (3 - place))).toChar().toString() else written
            assertEquals(want, confixDoc("{\"s\":\"$written\"}").value("s"), "byte $code at place $place")
        }
    }

    // the ends of the 64-bit range, and one past them in each sign, the digits summed downward so that Long.MIN_VALUE fits and nothing wraps
    @Test fun aLongColumnReadsEveryIntegerThatFitsAndNoOther() {
        val fit = listOf("0", "-0", "7", "-7", "007", "922337203685477580", "9223372036854775800", "9223372036854775806", "9223372036854775807",
            "-9223372036854775800", "-9223372036854775807", "-9223372036854775808", "1000000000000000000", "-1000000000000000000", "0000000000000000000009")
        for (n in fit) assertEquals(n.toLong(), column("[{\"v\":$n}]", 0)[0], n)
        val past = listOf("9223372036854775808", "9223372036854775809", "9223372036854775810", "9223372036854775817", "-9223372036854775809",
            "-9223372036854775810", "-9223372036854775818", "92233720368547758000", "18446744073709551616", "-18446744073709551616", "99999999999999999999")
        for (n in past) assertEquals(n.toDouble(), column("[{\"v\":$n}]", 0)[0], n)
    }

    @Test fun aUnicodeEscapeTakesFourHexDigitsAndNothingElse() {
        assertEquals("A", confixDoc("""{"s":"\u0041"}""").value("s"))
        assertEquals("\u00e9", confixDoc("""{"s":"\u00E9"}""").value("s"))
        // a sign is not a hex digit: toIntOrNull(16) reads "+041" as 0x41 and "-041" as a negative number, so these were 'A' and a stray char
        assertEquals("\\u+041", confixDoc("""{"s":"\u+041"}""").value("s"))
        assertEquals("\\u-041", confixDoc("""{"s":"\u-041"}""").value("s"))
    }
}
