package borg.trikeshed.common

import FileBuffer
import borg.trikeshed.lib.debug
import borg.trikeshed.lib.logDebug
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.α
import kotlin.random.Random
import kotlin.test.*

class CSVUtilTest {

    /** read in hi.csv and verify the contents */
    @Test
    fun testParseLineAndDeduce() {
        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloader
        val systemResource = ClassLoader.getSystemResource("hi.csv")
        val filename = systemResource.file.trim()
        val fileBuf = FileBuffer.open(filename)
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(fileBuf, 0, deduce = deduce)
        val lp = (csv α { it.pair }).toList()
        println(lp)
        logDebug { "csv: $csv" }
        fileBuf.close()
    }

    /** read in hi.csv and verify the contents */
    @Test
    fun testParseMutlipleLines() {
        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloader
        val systemResource = ClassLoader.getSystemResource("hi.csv")
        val filename = systemResource.file.trim()
        val fileBuf = FileBuffer.open(filename)
        val fileDeduce = mutableListOf<TypeEvidence>()

        val parseSegments = CSVUtil.parseSegments(fileBuf, fileDeduce = fileDeduce)

        debug { logDebug { "${Random.nextInt()}" } }


    }


}
