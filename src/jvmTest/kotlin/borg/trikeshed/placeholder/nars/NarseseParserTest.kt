package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.collections.s_
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

        val fsm = FSM(s_[task]) // create a FSM for the task parser
        val result = fsm(CharSeries(line1)) // parse the line
        println()
    }
}
