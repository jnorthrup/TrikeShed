package borg.trikeshed.placeholder.nars

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

    @kotlin.test.Test
    fun testParse() {
        val right = "abcabcabc"
        val sright = "abc abc abc"
        val wrong = "abcabcab"
        val oparser = +"abc"
        val rparser = oparser[3]
        val sparser = skipWs_(oparser)[3]


    }

    fun skipWs_(rule:`^`):`^`={input->
        val bak=input.clone()
        while(input.hasRemaining && bak.mk.get.isWhitespace()) ;
        bak.res
        rule(bak)
    }

}

private operator fun `^`.get(i: Int) = repeat_(this, i)

fun repeat_(rule: `^`, count: Int): `^` = { input ->
    var bak = input.clone()
    var c = 0
    var r: CharSeries? =null
    while (c != count) {
        val result = rule(bak)
        if (result ==null) {
            if(count!=-1){
                r = null
            }else
                break

        }else r=result
        bak = input.clone()
        c++
    }
    r
}
