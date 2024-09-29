package borg.trikeshed.common
import borg.trikeshed.parse.csv.CSVUtil.parseSegments
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.row
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.IoString
import borg.trikeshed.lib.*
import borg.trikeshed.parse.csv.CSVUtil
import borg.trikeshed.parse.csv.DelimitRange
import borg.trikeshed.parse.csv.simpelCsvCursor
import org.junit.Assert
import org.junit.Assert.assertThrows
import kotlin.test.*

class CSVUtilTest {

    @Test
    fun testParseLine() {
        val testCases = listOf(
            "a,b,c" to listOf(0..0, 2..2, 4..4),
            "\"a,b\",c,d" to listOf(1..3, 6..6, 8..8),
            "a,'b,c',d" to listOf(0..0, 3..5, 8..8),
            "a,,c" to listOf(0..0, 2 downTo 1, 3..3),
            "a,\"b\"\"\",c" to listOf(0..0, 3..5, 8..8)
        )

        for ((input, expected) in testCases) {
            val bytes = input.encodeToByteArray()
            val file : LongSeries<Byte> = bytes.size.toLong() j  {bytes[it.toInt()]}
            val result = CSVUtil.parseLine(file, 0, bytes.size.toLong())
            val ranges = result.map { DelimitRange(it) }

            assertEquals(expected.size, ranges.size, "Incorrect number of fields for input: $input")
            ranges.forEachIndexed { index, range ->
                assertEquals(expected[index], range.asIntRange, "Mismatch in range for field $index in input: $input")
            }
        }
    }

    @Test
    fun testParseConformant() {
        val csvData = """
            Name,Age,Height
            Alice,30,5.5
            Bob,25,6.0
            Charlie,35,5.8
        """.trimIndent()

        val file = csvData.encodeToByteArray().toSeries().toLongSeries()
        val meta =  (3) j { index:Int ->
            when (index) {
                0 -> RecordMeta("Name", IoString)
                1 -> RecordMeta("Age", IOMemento.IoInt)
                2 -> RecordMeta("Height", IOMemento.IoDouble)
                else -> throw IndexOutOfBoundsException()
            }
        }

        val result:Cursor = CSVUtil.parseConformant(file, meta) as Cursor

        assertEquals(3, result.size, "Incorrect number of rows")
        assertEquals(3, result.row(0).size, "Incorrect number of columns")

        // Check first row
        assertEquals("Alice", (result.row(0)[0] as Join<*, *>).a)
        assertEquals(30, (result.row(0)[1] as Join<*, *>).a)
        assertEquals(5.5, (result.row(0)[2] as Join<*, *>).a)

        // Check last row
        assertEquals("Charlie", (result.row(2)[0] as Join<*, *>).a)
        assertEquals(35, (result.row(2)[1] as Join<*, *>).a)
        assertEquals(5.8, (result.row(2)[2] as Join<*, *>).a)
    }

    @Test
    fun testParseSegments() {
        val csvData = """
            Name,Age,City
            John Doe,30,"New York, NY"
            Jane Smith,25,London
            "Smith, Bob",40,Paris
        """.trimIndent()

        val file = csvData.encodeToByteArray().toSeries().toLongSeries()
        val result :Cursor= CSVUtil.parseSegments(file)

        assertEquals(3, result.size, "Incorrect number of rows")
        assertEquals(3, result.row(0).size, "Incorrect number of columns")

        // Check headers
        assertEquals("Name", (result.row(0)[0].b as RecordMeta).name)
        assertEquals("Age", (result.row(0)[1].b as RecordMeta).name)
        assertEquals("City", (result.row(0)[2].b as RecordMeta).name)

        // Check data
        assertEquals("John Doe", (result.row(0)[0].a as CharSeries).asString())
        assertEquals("30", (result.row(0)[1].a as CharSeries).asString())
        assertEquals("New York, NY", (result.row(0)[2].a as CharSeries).asString())

        assertEquals("Smith, Bob", (result.row(2)[0].a as CharSeries).asString())
        assertEquals("40", (result.row(2)[1].a as CharSeries).asString())
        assertEquals("Paris", (result.row(2)[2].a as CharSeries).asString())
    }

    @Test
    fun testSimpelCsvCursor() {
        val csvData = listOf(
            "Name,Age,City",
            "John Doe,30,New York",
            "Jane Smith,25,London",
            "Bob Johnson,40,Paris"
        )

        val result = simpelCsvCursor(csvData)

        assertEquals(3, result.size, "Incorrect number of rows")
        assertEquals(3, result.row(0).size, "Incorrect number of columns")

        // Check first row
        assertEquals("John Doe", (result.row(0)[0] as Join<*, *>).a)
        assertEquals("30", (result.row(0)[1] as Join<*, *>).a)
        assertEquals("New York", (result.row(0)[2] as Join<*, *>).a)

        // Check last row
        assertEquals("Bob Johnson", (result.row(2)[0] as Join<*, *>).a)
        assertEquals("40", (result.row(2)[1] as Join<*, *>).a)
        assertEquals("Paris", (result.row(2)[2] as Join<*, *>).a)

        // Check metadata
        val meta = result.row(0)[0].b as RecordMeta
        assertEquals("Name", meta.name)
        assertEquals(IOMemento.IoString, meta.type)
    }

    @Test
    fun testEdgeCases() {
        // Empty file
        val emptyFile = "".encodeToByteArray().toSeries().toLongSeries()
        Assert.assertThrows(Exception::class.java) { parseSegments(emptyFile) }

        // File with only headers
        val headersOnly = "A,B,C".encodeToByteArray().toSeries().toLongSeries()
        val headersResult = parseSegments(headersOnly)
        assertEquals(0, headersResult.size, "Should have no data rows")

        // Mismatched columns
        val mismatchedColumns = """
                A,B,C
                1,2,3
                4,5
                6,7,8,9
            """.trimIndent().encodeToByteArray().toSeries().toLongSeries()
        assertThrows(Exception::class.java) { parseSegments(mismatchedColumns) }

        // Escaped quotes and commas
        val escapedData = """
                "A","B","C"
                "1,1","2""2","3"
                "4","5,5","6"
            """.trimIndent().encodeToByteArray().toSeries().toLongSeries()
        val escapedResult = parseSegments(escapedData)
        assertEquals(2, escapedResult.size, "Incorrect number of rows")
        assertEquals("1,1", (escapedResult.row(0)[0].a as CharSeries).asString())
        assertEquals("2\"2", (escapedResult.row(0)[1].a as CharSeries).asString())
        assertEquals("5,5", (escapedResult.row(1)[1].a as CharSeries).asString())
    }
}



