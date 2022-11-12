package borg.trikeshed.common

import FileBuffer
import borg.trikeshed.lib.logDebug
import kotlin.test.*

class CSVUtilTest {

    /** read in hi.csv and verify the contents */
    @Test
    fun testParseLineAndDeduce() {
        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloadr
        val systemResource = ClassLoader.getSystemResource("hi.csv")
        val filename = systemResource.file.trim()
        val fileBuf = FileBuffer.open(filename)
        val deduce = emptyList<TypeDeduction>()
        val csv = CSVUtil.parseLine(fileBuf, 0, deduce = deduce)
        logDebug { "csv: $csv" }
    }
}
