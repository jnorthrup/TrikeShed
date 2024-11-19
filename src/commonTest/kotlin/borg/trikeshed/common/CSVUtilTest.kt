package borg.trikeshed.common

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CSVUtilTest {
    @Test
    fun `test empty csv`() {
        val emptyBuffer = "".encodeToByteArray().toSeries()
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(emptyBuffer.toLongSeries(), 0, lineEvidence = deduce)
        assertEquals(0, csv.size)
    }

    @Test
    fun `test quoted fields`() {
        val input = """1,"hello,world",2""".encodeToByteArray().toSeries().toLongSeries()
        val deduce = mutableListOf<TypeEvidence>()
        val csv = CSVUtil.parseLine(input, 0, lineEvidence = deduce) α ::DelimitRange
        val fields = csv α { delimR: DelimitRange ->
            val chars = input.get(delimR.asIntRange) α { it: Byte -> it.toUByte().toInt().toChar() }
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
        val csv = CSVUtil.parseLine(input.toLongSeries(), 0, lineEvidence = deduce) α ::DelimitRange
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
        CSVUtil.parseLine(input.toLongSeries(), 0, lineEvidence = deduce)
        assertEquals(4, deduce.size)
        assertEquals(deduce[0].digits.toInt(), 1)
        assertEquals(deduce[1].periods.toInt(), 1)
        assertEquals(deduce[2].truefalse.toInt(), 1)
        assertEquals(deduce[3].alpha.toInt(), 5)
    }

    @Test
    fun `test parse with headers`() {
        val input = """
            name,age,active
            bob,32,true
            alice,28,false
        """.trimIndent().encodeToByteArray().toSeries()

        val cursor = CSVUtil.parseConformant(input.toLongSeries())
        assertEquals(3, cursor.meta.size)
        assertEquals("name", cursor.meta[0].name)
        assertEquals("age", cursor.meta[1].name)
        assertEquals("active", cursor.meta[2].name)

        val row1 = cursor.row(0)
        assertEquals("bob", row1[0] as? String)
        assertEquals(32, row1[1] as? Int)
        assertEquals(true, row1[2] as? Boolean)
    }
}
