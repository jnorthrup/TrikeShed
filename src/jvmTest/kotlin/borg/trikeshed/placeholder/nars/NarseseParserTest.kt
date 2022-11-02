package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.debug
import borg.trikeshed.lib.first
import borg.trikeshed.lib.logDebug
import borg.trikeshed.lib.parser.simple.CharSeries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import kotlin.io.path.readLines


class NarseseParserTest {
    // read src/jvmTest/resources/sample.nars into lines
    private val lines: List<String> = Paths.get("src/jvmTest/resources/sample.nars").readLines()

    object abc : Rule by "abc".λ

    @kotlin.test.Test
    fun testParse() {
        val right = "abcabcabc"
        val sright = "abc abc abc"
        val wrong = "abcabcab"
        runBlocking {
            abc(CharSeries(right))?.let { res: ParseResult ->
                println(res).debug {
                    "oparser: $it"
                }
            }
        }
    }

    @kotlin.test.Test
    fun testParse2() {
        val parseContext1 = ParseContext()

        runBlocking {
            launch(parseContext1) {
                val res = abc(CharSeries("abcabcabc"))
                println(res).debug {
                    "oparser: $it"
                }

                val right = "abcabcabc"
                val sright = "abc abc abc"
                val wrong = "abcabcab"

                val rule = abc[3]
                rule.invoke(CharSeries(right))?.let { res: ParseResult ->
                    logDebug { "oparser: ${res.pair.second.pair}" }
                    res.b.first
                }
                logDebug {
                    "parseContext1: ${parseContext1.stack}"
                }
            }
        }
    }
}
