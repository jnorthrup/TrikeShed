package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.collections._l
import borg.trikeshed.lib.collections.s_
import borg.trikeshed.lib.j
import borg.trikeshed.lib.parser.simple.CharSeries
import kotlin.io.path.Path
import kotlin.io.path.readLines


import kotlin.test.*

class NarseseParserTest {
    // read src/jvmTest/resources/sample.nars into lines
    val lines: List<String> = Path("src/jvmTest/resources/sample.nars").readLines()

    @Test
    fun testParser() {

        //TEST LINE 1
        val line1 = lines[0]
        val line1Expected = "  \$.13 (Calorie-->CompositeUnitOfMeasure). %1.0;.45% {345: o}".trim()

        val fsm = RecursiveContextParserImpl( ) // create a FSM for the task parser
        val result = fsm(task,CharSeries(line1Expected)) // parse the line
        println()
    }

    val abc=  (+'a' + (+_l['a', 'b', 'c'])[1..2])["abc"]
    val abclp= (abc["first"] + (((+',')["comma"] + abc["trailling"])[0..Integer.MAX_VALUE]))["outer"]

    @Test
    fun testSpaceParser() {

        //TEST LINE 1
        val line1 = lines[0]
        val line1Expected = "ab,ac,abc,aaa,aba".trim()


        val fsm = RecursiveContextParserImpl( ) // create a FSM for the task parser
        val result = fsm(abclp,CharSeries(line1Expected)) // parse the line
        println()
    }
}
