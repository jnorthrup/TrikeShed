package borg.trikeshed.isam


import borg.trikeshed.cursor.*
import borg.trikeshed.io.*
import borg.trikeshed.io.FileBuffer
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.CSVUtil
import borg.trikeshed.parse.TypeEvidence
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import borg.trikeshed.io.FileBuffer as fileBuffer

class CSVUtilTest {
    val target: String = "src/commonTest/resources/hi.csv"



    @Test
    fun testCursorAccess() {
        fileBuffer(target).use { fileBuf ->
            val cursor = CSVUtil.indexCsv(fileBuf)

            // Test accessing specific values
            if (cursor.a > 16) {
                cursor.row(16).let { row ->
                    assertEquals(1502943360000L, row[0].a) // Open_time
                    assertEquals(4261.48, row[1].a as Double, 0.001) // Open
                    assertEquals(0.0, row[5].a as Double, 0.001) // Volume
                    assertEquals(0, row[8].a) // Number_of_trades
                }
            } else {
                fail("CSV file does not have enough rows")
            }
        }
    }

    @Test
    fun testColumnMetadata() {
        fileBuffer(target).use { fileBuf ->
            val cursor = CSVUtil.indexCsv(fileBuf)
            val meta = cursor.meta

            // Log the detected types
            meta.forEachIndexed { index, columnMeta ->
                println("Column $index: ${columnMeta.type}")
            }


            /***
             * CSV Cursors will be IoCharSeries, type evidence will be inferred on isam creation
             */

        }
    }

     fun fileBuffer(target: String): FileBuffer =
         open (target, 0, -1, true)




    @Test
    fun testConformant() {
        fun testConformant() {
            val fileBuf = fileBuffer(target)
            fileBuf.use {
                val cursor = CSVUtil.indexCsv(fileBuf)
                cursor.meta.forEach { println(it) }

                val meta = cursor.meta
                assertEquals(IoLong, meta[0].type)
                assertEquals(IoFloat, meta[1].type)
                assertEquals(IoFloat, meta[2].type)
                assertEquals(IoFloat, meta[3].type)
                assertEquals(IoFloat, meta[4].type)
                assertEquals(IoFloat, meta[5].type)
                assertEquals(IoLong, meta[6].type)
                assertEquals(IoFloat, meta[7].type)
                assertEquals(IoByte, meta[8].type)
                assertEquals(IoFloat, meta[9].type)
                assertEquals(IoFloat, meta[10].type)
                assertEquals(IoFloat, meta[11].type)
            }
        }
    }
}
