package borg.trikeshed.lib.parser.simple

import borg.trikeshed.placeholder.nars.NarseseParser
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.*


class NarseseParserTest {

    private lateinit var cs: CharSeries

    @BeforeTest
    fun setup() {
        //access test resource "sample.nars" and read all lines into a list
        val lines = javaClass.getResource("/sample.nars").readText().lines()

        //create a CharSeries from the first line
        this. cs = CharSeries(lines[0])//  $.13 (Calorie-->CompositeUnitOfMeasure). %1.0;.45% {345: o}
    }

@Test
    fun test() {
        runBlocking {
            NarseseParser.task(cs);
            val currentCoroutineContext = currentCoroutineContext()
            println()

        }
    }
    }






