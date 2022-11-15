package borg.trikeshed.common

import FileBuffer
import borg.trikeshed.lib.*
import java.net.URL
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
        val csv = CSVUtil.parseLine(fileBuf, 0, lineEvidence = deduce)
        val lp = (csv α { it.pair }).toList()
        println(lp)
        logDebug { "csv: $csv" }
        fileBuf.close()
    }

    /** read in hi.csv and verify the contents */
    @Test
    fun testParseMutlipleLines() {

        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloader
        val systemResource: URL = ClassLoader.getSystemResource("hi.csv")
        val filename: String = systemResource.file.trim()
        val fileBuf: FileBuffer = FileBuffer.open(filename)
        val fileDeduce: MutableList<TypeEvidence> = mutableListOf()

        val parseSegments:  Cursor = CSVUtil.parseSegments(fileBuf, fileEvidence = fileDeduce)

//        parseSegments.head
        debug { logDebug { "${Random.nextInt()}" } }
    }


}
