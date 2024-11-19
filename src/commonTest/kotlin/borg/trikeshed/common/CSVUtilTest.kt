package borg.trikeshed.common

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class CSVUtilTest {
    @Test
    fun `test empty csv`() {
        val emptyBuffer = "".encodeToByteArray().toSeries()
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(emptyBuffer, 0, lineEvidence = deduce)
        assertEquals(0, csv.size)
    }

    @Test 
    fun `test quoted fields`() {
        val input = """1,"hello,world",2""".encodeToByteArray().toSeries()
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(input, 0, lineEvidence = deduce) α ::DelimitRange
        val fields = csv α { delimR: DelimitRange ->
            val chars = input.get(delimR.asIntRange) α { it.toUByte().toInt().toChar() }
            chars.asString()
        }
        assertEquals(3, fields.size)
        assertEquals("1", fields[0]) 
        assertEquals("hello,world", fields[1])
        assertEquals("2", fields[2])
    }

    @Test
    fun `test malformed csv`() {
        val input = "1,2,".encodeToByteArray().toSeries() 
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(input, 0, lineEvidence = deduce) α ::DelimitRange
        val fields = csv α { delimR: DelimitRange ->
            val chars = input.get(delimR.asIntRange) α { it.toUByte().toInt().toChar() }
            chars.asString()
        }
        assertEquals(3, fields.size)
        assertEquals("1", fields[0])
        assertEquals("2", fields[1]) 
        assertEquals("", fields[2])
    }

    @Test
    fun `test type deduction`() {
        val input = "123,12.34,true,hello".encodeToByteArray().toSeries()
        val deduce = mutableListOf<TypeEvidence>()
        CSVUtil.parseLine(input, 0, lineEvidence = deduce)
        assertEquals(4, deduce.size)
        assertEquals(TypeEvidence.INTEGER, deduce[0])
        assertEquals(TypeEvidence.FLOAT, deduce[1]) 
        assertEquals(TypeEvidence.BOOLEAN, deduce[2])
        assertEquals(TypeEvidence.STRING, deduce[3])
    }

    @Test
    fun `test parse with headers`() {
        val input = """
            name,age,active
            bob,32,true
            alice,28,false
        """.trimIndent().encodeToByteArray().toSeries()
        
        val cursor = CSVUtil.parseConformant(input)
        assertEquals(3, cursor.meta.size)
        assertEquals("name", cursor.meta[0].name)
        assertEquals("age", cursor.meta[1].name) 
        assertEquals("active", cursor.meta[2].name)
        
        val row1 = cursor.row(0)
        assertEquals("bob", row1[0])
        assertEquals(32, row1[1]) 
        assertEquals(true, row1[2])
    }
//    val target: String = "src/commonTest/resources/hi.csv"
//
//    /** read in hi.csv and verify the contents */
//    @Test
//    fun testParseLineAndDeduce() {
//        val fileBuf = fileBuffer(target)
//
//        fileBuf.use {
//            val deduce = mutableListOf<TypeEvidence>()
//            val csv = CSVUtil.parseLine(fileBuf, 0, lineEvidence = deduce) α ::DelimitRange
//            val lp = (csv α { delimR: DelimitRange ->
//                val chars = fileBuf.get(delimR.asIntRange) α { it.toUByte().toInt().toChar() }
//                (chars).asString()
//            }).`▶`.withIndex().toList()
//            println(lp)
//        }
//    }
//
//    fun fileBuffer(target: String): FileBuffer {
//        logDebug { "Cwd:" + Files.cwd() }
//        val fname = Files.cwd() + '/' + target
//        return openFileBuffer(fname)
//    }
//
//    /** read in hi.csv and verify the contents */
//    @Test
//    fun `parse multiple lines`() {
//        //use classpath to find the file using the src/test/resources hi.csv in the root package of the classloader
//        fileBuffer(target).use { fileBuf ->
//            val fileDeduce: MutableList<TypeEvidence> = mutableListOf()
//            val parseSegments:Cursor = CSVUtil.parseSegments(fileBuf, fileEvidence = fileDeduce)
//
//            parseSegments.head()
//            debug { logDebug { "${Random.nextInt()}" } }
//
//            //test row 16 against the contents
//            // 1502943360000,4261.48000000,4261.48000000,4261.48000000,4261.48000000,0.00000000,1502943419999,0.00000000,0,0.00000000,0.00000000,7958.34896534
//            val row16 = parseSegments.row(16)
//            val row16List = row16.left //α { (it as? Series<Char>)?.asString()?:(it as String) }
//            assertEquals(12, row16List.size)
//            assertEquals("1502943360000", row16List[0])
//            assertEquals("4261.48000000", row16List[1])
//            assertEquals("4261.48000000", row16List[2])
//            assertEquals("4261.48000000", row16List[3])
//            assertEquals("4261.48000000", row16List[4])
//            assertEquals("0.00000000", row16List[5])
//            assertEquals("1502943419999", row16List[6])
//            assertEquals("0.00000000", row16List[7])
//            assertEquals("0", row16List[8])
//            assertEquals("0.00000000", row16List[9])
//            assertEquals("0.00000000", row16List[10])
//            assertEquals("7958.34896534", row16List[11])
//            //test row 17 against the contents
//        }
//    }
//
//    @Test
//    fun testConformant() {
//        val fileBuf = fileBuffer(target)
//        fileBuf.use {
//            val cursor = CSVUtil.parseConformant(fileBuf)
//            cursor.meta.forEach { println(it) }
//
//            val meta = cursor.meta
//            assertEquals(IOMemento.IoLong, meta[0].type)
//            assertEquals(IOMemento.IoFloat, meta[1].type)
//            assertEquals(IOMemento.IoFloat, meta[2].type)
//            assertEquals(IOMemento.IoFloat, meta[3].type)
//            assertEquals(IOMemento.IoFloat, meta[4].type)
//            assertEquals(IOMemento.IoFloat, meta[5].type)
//            assertEquals(IOMemento.IoLong, meta[6].type)
//            assertEquals(IOMemento.IoFloat, meta[7].type)
//            assertEquals(IOMemento.IoByte, meta[8].type)
//            assertEquals(IOMemento.IoFloat, meta[9].type)
//            assertEquals(IOMemento.IoFloat, meta[10].type)
//            assertEquals(IOMemento.IoFloat, meta[11].type)
//        }
//    }
//
//    @Test
//    fun testConformant2Isam() {
//        val fileBuf = fileBuffer(target)
//        fileBuf.use {
//            val cursor = CSVUtil.parseConformant(fileBuf)
//            val meta = cursor.meta.debug {
//                logDebug { "meta: ${it.toList()}" }
//            }
//            meta.forEach { println(it) }
//            IsamDataFile.write(cursor, "/tmp/hi.isam")
//            IsamDataFile("/tmp/hi.isam").use { isam ->
////                 isam.open()
//                isam.head()
//                logDebug {
//                    "meta: ${
//                        isam.meta.map { isam ->
//                            isam.name to isam.type
//                        }
//                    }"
//                }
//                //test row 16 against the contents
//                // 1502943360000,4261.48000000,4261.48000000,4261.48000000,4261.48000000,0.00000000,1502943419999,0.00000000,0,0.00000000,0.00000000,7958.34896534
//                val row16 = isam.row(16)
//                val row16List = row16.left
//                assertEquals(12, row16List.size)
//                assertEquals(1502943360000, row16List[0])
//
//                assertEquals(1502943419999, row16List[6])
//                assertEquals(
//                    0.toByte(),
//                    row16List[8]
//                )//this test arrives at a Byte duck type, and needs a cast or fails.
//                assertEquals(4261.48f, row16List[1])
//                assertEquals(4261.48f, row16List[2])
//                assertEquals(4261.48f, row16List[3])
//                assertEquals(4261.48f, row16List[4])
//                assertEquals(7958.349f, row16List[11])
//                assertEquals(0f, row16List[5])
//                assertEquals(0f, row16List[7])
//                assertEquals(0f, row16List[9])
//                assertEquals(0f, row16List[10])
//                isam.close()
//            }
//        }
//    }
//}
