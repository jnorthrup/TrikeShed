package borg.trikeshed.common

import FileBuffer
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*
import borg.trikeshed.parse.DelimitRange
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
        val csv = CSVUtil.parseLine(fileBuf, 0, lineEvidence = deduce) α ::DelimitRange
        val lp=(csv α {delimR :DelimitRange->String( fileBuf.get(delimR.asIntRange).toArray()) } ).`▶`.withIndex().toList()
        println(lp)

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

        parseSegments.head()
        debug { logDebug { "${Random.nextInt()}" } }


        //test row 16 against the contents
    // 1502943360000,4261.48000000,4261.48000000,4261.48000000,4261.48000000,0.00000000,1502943419999,0.00000000,0,0.00000000,0.00000000,7958.34896534
        val row16 = parseSegments.row(16)
        val row16List = row16.left  α {it as CharSeries} α CharSeries::asString
        assertEquals(12, row16List.size)
        assertEquals("1502943360000", row16List[0])
        assertEquals("4261.48000000", row16List[1])
        assertEquals("4261.48000000", row16List[2])
        assertEquals("4261.48000000", row16List[3])
        assertEquals("4261.48000000", row16List[4])
        assertEquals("0.00000000", row16List[5])
        assertEquals("1502943419999", row16List[6])
        assertEquals("0.00000000", row16List[7])
        assertEquals("0", row16List[8])
        assertEquals("0.00000000", row16List[9])
        assertEquals("0.00000000", row16List[10])
        assertEquals("7958.34896534", row16List[11])

        //test row 17 against the contents

    }

}
