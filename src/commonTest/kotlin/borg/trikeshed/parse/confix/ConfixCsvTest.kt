package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConfixCsvTest {

    /** Debug: print actual output from CsvScan for a known input. */
    @Test
    fun debugOutput() {
        val csv = "1700000000000,1.2345,1.2350,1.2340,1.2348,100.5,1700000059999,200.0,50,30.0,25.0,0\n"
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(1, elems.size, "should have 1 element")
        val e = elems[0]
        println("open=${e.a.a} close=${e.a.b} commas.size=${e.b.size}")
        for (i in 0 until e.b.size) {
            val t = CsvScan.fieldText(e, src, i)
            println("  field[$i] = '$t'")
        }
    }

    /** Single data line with 12 numeric fields (Binance kline format). */
    @Test
    fun singleKlineRow() {
        val csv = "1700000000000,1.2345,1.2350,1.2340,1.2348,100.5,1700000059999,200.0,50,30.0,25.0,0\n"
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(1, elems.size)
        val e = elems[0]
        // 12 fields → 12 commas (field-start positions)
        assertEquals(12, e.b.size)
        // Field 0 = openTime
        assertEquals(1700000000000L, CsvScan.fieldLong(e, src, 0))
        // Field 1 = open
        assertEquals(1.2345, CsvScan.fieldDouble(e, src, 1))
        // Field 2 = high
        assertEquals(1.2350, CsvScan.fieldDouble(e, src, 2))
        // Field 3 = low
        assertEquals(1.2340, CsvScan.fieldDouble(e, src, 3))
        // Field 4 = close
        assertEquals(1.2348, CsvScan.fieldDouble(e, src, 4))
        // Field 10 = takerBuyQuoteVolume
        assertEquals(25.0, CsvScan.fieldDouble(e, src, 10))
        // Field 11 = Ignore
        assertEquals(0L, CsvScan.fieldLong(e, src, 11))
        // Out-of-range returns null
        assertNull(CsvScan.fieldLong(e, src, 12))
        assertNull(CsvScan.fieldLong(e, src, -1))
    }

    /** Header row (Open_time, Open, ...) is a string field — treated as STRING tag. */
    @Test
    fun headerRow() {
        val header = "Open_time,Open,High,Low,Close,Volume,Close_time,Quote_asset_volume,Number_of_trades,Taker_buy_base_asset_volume,Taker_buy_quote_asset_volume,Ignore\n"
        val src = header.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(1, elems.size)
        val e = elems[0]
        assertEquals(12, e.b.size)
        assertEquals("Open_time", CsvScan.fieldText(e, src, 0))
        assertEquals("Volume", CsvScan.fieldText(e, src, 5))
        assertEquals("Ignore", CsvScan.fieldText(e, src, 11))
    }

    /** Multiple lines including blank line (null element) and header line. */
    @Test
    fun multiLineWithBlankAndHeader() {
        val csv = """
            Open_time,Open,High,Low,Close,Volume,Close_time,Quote_asset_volume,Number_of_trades,Taker_buy_base_asset_volume,Taker_buy_quote_asset_volume,Ignore
            1700000000000,1.2345,1.2350,1.2340,1.2348,100.5,1700000059999,200.0,50,30.0,25.0,0

            1700000060000,1.2348,1.2360,1.2345,1.2355,80.0,1700000119999,160.0,40,20.0,15.0,0
        """.trimIndent()
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        // 3 non-blank lines + 1 blank (NULL-tagged) = 4 elements
        assertEquals(4, elems.size)
        // Element 0 = header (STRING fields)
        assertEquals("Open_time", CsvScan.fieldText(elems[0], src, 0))
        // Element 1 = first kline
        assertEquals(1700000000000L, CsvScan.fieldLong(elems[1], src, 0))
        // Element 2 = blank line — tag should be NULL
        assertEquals(Tag.NULL, Combinators.tagOf(elems[2], src))
        // Element 3 = second kline
        assertEquals(1700000060000L, CsvScan.fieldLong(elems[3], src, 0))
    }

    /** Fields with spaces are trimmed. */
    @Test
    fun fieldTrim() {
        val csv = "  1700000000000  ,  1.2345  ,  1.2350  \n"
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(1, elems.size)
        assertEquals(1700000000000L, CsvScan.fieldLong(elems[0], src, 0))
        assertEquals(1.2345, CsvScan.fieldDouble(elems[0], src, 1))
        assertEquals(1.2350, CsvScan.fieldDouble(elems[0], src, 2))
    }

    /** Last field without trailing comma. */
    @Test
    fun noTrailingComma() {
        val csv = "1700000000000,1.2345,1.2350\n"
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(1, elems.size)
        assertEquals(3, elems[0].b.size)
        assertEquals(1700000000000L, CsvScan.fieldLong(elems[0], src, 0))
        assertEquals(1.2350, CsvScan.fieldDouble(elems[0], src, 2))
    }

    /** CR+LF line endings. */
    @Test
    fun crlfLineEndings() {
        val csv = "1700000000000,1.2345\r\n1700000060000,1.2348\r\n"
        val src = csv.asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(2, elems.size)
        assertEquals(1700000000000L, CsvScan.fieldLong(elems[0], src, 0))
        assertEquals(1700000060000L, CsvScan.fieldLong(elems[1], src, 0))
    }

    /** Omitted: blank input returns empty series. */
    @Test
    fun emptyInput() {
        val src = "".asSeries()
        val elems = CsvScan.scan(src)
        assertEquals(0, elems.size)
    }
}
