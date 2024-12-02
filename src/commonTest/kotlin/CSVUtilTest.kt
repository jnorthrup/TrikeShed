package borg.trikeshed.isam

import borg.trikeshed.common.*
import borg.trikeshed.cursor.*
import borg.trikeshed.io.FileBuffer
import borg.trikeshed.io.Files
import borg.trikeshed.io.openFileBuffer
import borg.trikeshed.io.use
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.CSVUtil
import borg.trikeshed.parse.TypeEvidence
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CSVUtilTest {
    val target: String = "src/commonTest/resources/hi.csv"

    @Test
    fun testTwinEvidence() {
        fileBuffer(target).use { fileBuf ->
            val evidence: Series<Twin<TypeEvidence>> = Join.emptySeriesOf<Twin<TypeEvidence>>()
            val cursor = CSVUtil.indexCsv(fileBuf)    ////.parseSegments(fileBuf, fileEvidence = evidence)

            // Verify evidence collection
            assertEquals(12, evidence.size) // 12 columns from hi.csv

            // Test timestamp column evidence (first column)
            evidence[0].let { twin ->

                assertEquals(twin.a.digits,twin.b.digits)
                assertEquals(IOMemento.IoLong, cursor.meta[0].type)
            }

            // Test price column evidence (second column)
            evidence[1].let { twin ->
                assertEquals(twin.a.digits , twin.b.digits)
                assertEquals(IOMemento.IoDouble, cursor.meta[1].type)
            }

            // Test volume column evidence
            evidence[5].let { twin ->


                assertEquals(twin.a.periods , twin.b.periods)
                assertEquals(IOMemento.IoDouble, cursor.meta[5].type)
            }

            // Test trades column evidence
            evidence[8].let { twin ->

                assertEquals(twin.a.digits , twin.b.digits)
                assertEquals(IOMemento.IoInt, cursor.meta[8].type)
            }
        }
    }

    @Test
    fun testCursorAccess() {
        fileBuffer(target).use { fileBuf ->
            val cursor = CSVUtil.indexCsv(fileBuf)

            // Test accessing specific values
            cursor.row(16).let { row ->
                assertEquals(1502943360000L, row[0].a) // Open_time
                assertEquals(4261.48, row[1].a as Double, 0.001) // Open
                assertEquals(0.0, row[5].a as Double, 0.001) // Volume
                assertEquals(0, row[8].a) // Number_of_trades
            }
        }
    }

    @Test
    fun testColumnMetadata() {
        fileBuffer(target).use { fileBuf ->
            val cursor = CSVUtil.indexCsv(fileBuf)
            val meta = cursor.meta

            // Verify column types
            assertEquals(IOMemento.IoLong, meta[0].type) // Open_time
            assertEquals(IOMemento.IoDouble, meta[1].type) // Open
            assertEquals(IOMemento.IoDouble, meta[2].type) // High
            assertEquals(IOMemento.IoDouble, meta[3].type) // Low
            assertEquals(IOMemento.IoDouble, meta[4].type) // Close
            assertEquals(IOMemento.IoDouble, meta[5].type) // Volume
            assertEquals(IOMemento.IoLong, meta[6].type) // Close_time
            assertEquals(IOMemento.IoDouble, meta[7].type) // Quote_asset_volume
            assertEquals(IOMemento.IoInt, meta[8].type) // Number_of_trades
            assertEquals(IOMemento.IoDouble, meta[9].type) // Taker_buy_base_asset_volume
            assertEquals(IOMemento.IoDouble, meta[10].type) // Taker_buy_quote_asset_volume
            assertEquals(IOMemento.IoDouble, meta[11].type) // Ignore
        }
    }

    fun fileBuffer(target: String): FileBuffer {
        logDebug { "Cwd:" + Files.cwd() }
        val fname = Files.cwd() + '/' + target
        return openFileBuffer(fname)
    }

    /** read in hi.csv and verify the contents */
    @Test
    fun testParseMutlipleLines() {
        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloader
        fileBuffer(target).use { fileBuf ->
            val fileDeduce: MutableList<TypeEvidence> = mutableListOf()
            val parseSegments: Cursor = CSVUtil.indexCsv(fileBuf)

            parseSegments.head()
            debug { logDebug { "${Random.nextInt()}" } }

            //test row 16 against the contents
            // 1502943360000,4261.48000000,4261.48000000,4261.48000000,4261.48000000,0.00000000,1502943419999,0.00000000,0,0.00000000,0.00000000,7958.34896534
            val row16 = parseSegments.row(16)
            val row16List = row16.left α { it as CharSeries } α CharSeries::asString
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

    @Test
    fun testConformant() {
        val fileBuf = fileBuffer(target)
        fileBuf.use {
            val cursor = CSVUtil.indexCsv(fileBuf)
            cursor.meta.forEach { println(it) }

            val meta = cursor.meta
            assertEquals(IOMemento.IoLong, meta[0].type)
            assertEquals(IOMemento.IoFloat, meta[1].type)
            assertEquals(IOMemento.IoFloat, meta[2].type)
            assertEquals(IOMemento.IoFloat, meta[3].type)
            assertEquals(IOMemento.IoFloat, meta[4].type)
            assertEquals(IOMemento.IoFloat, meta[5].type)
            assertEquals(IOMemento.IoLong, meta[6].type)
            assertEquals(IOMemento.IoFloat, meta[7].type)
            assertEquals(IOMemento.IoByte, meta[8].type)
            assertEquals(IOMemento.IoFloat, meta[9].type)
            assertEquals(IOMemento.IoFloat, meta[10].type)
            assertEquals(IOMemento.IoFloat, meta[11].type)
        }
    }
}
