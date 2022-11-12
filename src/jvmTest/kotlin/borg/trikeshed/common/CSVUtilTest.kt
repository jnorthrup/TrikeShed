package borg.trikeshed.common

import borg.trikeshed.isam.ColMeta
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.meta
import borg.trikeshed.lib.size
import kotlin.test.*

class CSVUtilTest {

    /** read in hi.csv and verify the contents */
    @Test
    fun testHiCsv() {
        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloadr
        val systemResource = ClassLoader.getSystemResource("hi.csv")

        val csv = CSVUtil.readCsv(systemResource.file)

        val size = csv.size
        assertEquals(18, size)

        val meta: Series<ColMeta> = csv.meta
        val x = meta.size
    }
}