package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.debug
import borg.trikeshed.lib.parser.simple.CharSeries
import java.nio.file.Paths
import kotlin.io.path.readLines


class NarseseParserTest {
    // read src/jvmTest/resources/sample.nars into lines
    private val lines: List<String> = Paths.get("src/jvmTest/resources/sample.nars").readLines()

//    @kotlin.test.Test
//    fun testParse() {
//        // parse each line
//        lines.forEach { line ->
//            // parse the line
//            val parsed = NarseseParser.parse(CharSeries(line))
//            // print the parsed line
//            println(parsed)
//        }
//    }

    object abc: Rule by "abc".λ

    @kotlin.test.Test
    fun testParse() {
        val right = "abcabcabc"
        val sright = "abc abc abc"
        val wrong = "abcabcab"

        abc(CharSeries(right))?.let { res: ParseResult ->
            println( res).debug {
                "oparser: $it" }
             }
        }

    }
