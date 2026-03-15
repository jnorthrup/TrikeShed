package borg.trikeshed.brc

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.parse.csv.CsvBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for CSV / Line IO operations used in BRC processing.
 *
 * All tests use pure Kotlin (commonTest) — no java.* imports.
 * Semicolon scanning uses CharSeries (commonMain).
 * CsvBitmap.encode() is exercised on a UByteArray from inline data.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class BrcCsvContractTest {

    // -------------------------------------------------------------------------
    // Helper: scan a string for all positions of a given delimiter char
    // Returns list of 0-based indices where the delimiter appears.
    // -------------------------------------------------------------------------
    private fun semicolonPositions(input: String): List<Int> {
        val positions = mutableListOf<Int>()
        val cs = CharSeries(input)
        while (cs.hasRemaining) {
            val startPos = cs.pos
            val found = cs.seekTo(';')
            if (found) positions.add(cs.pos - 1) // seekTo advances past ';'
            else break
        }
        return positions
    }

    // -------------------------------------------------------------------------
    // 1. Semicolon scan finds delimiter positions
    // -------------------------------------------------------------------------
    @Test
    fun csvSemicolonScanFindsDelimiterPositions() {
        val input = "Hamburg;12.0\nBerlin;-3.5\n"
        val positions = semicolonPositions(input)
        // "Hamburg" is 7 chars → ';' at index 7
        // "Berlin" starts after '\n' at index 13, is 6 chars → ';' at index 13+6 = 19
        assertEquals(2, positions.size, "Expected 2 semicolons in two-line BRC data")
        assertEquals(7, positions[0], "First ';' must be at index 7 (after 'Hamburg')")
        assertEquals(19, positions[1], "Second ';' must be at index 19 (after 'Berlin')")
    }

    // -------------------------------------------------------------------------
    // 2. Single-line parse: station + temperature
    // -------------------------------------------------------------------------
    @Test
    fun csvLineParseProducesStationTemperaturePair() {
        val line = "São Paulo;25.3"
        val cs = CharSeries(line)
        val found = cs.seekTo(';')
        assertTrue(found, "';' delimiter must be found in BRC line")

        val delimPos = cs.pos - 1          // position of ';' in the original string
        val station = line.substring(0, delimPos)
        val tempStr = line.substring(cs.pos)
        val temperature = tempStr.toDouble()

        assertEquals("São Paulo", station, "Station name must survive substring extraction")
        assertEquals(25.3, temperature, "Temperature must parse to 25.3")
    }

    // -------------------------------------------------------------------------
    // 3. Multiple lines: all 5 station names extractable
    // -------------------------------------------------------------------------
    @Test
    fun csvMultipleLinesParsedCorrectly() {
        val lines = listOf(
            "Hamburg;12.0",
            "Berlin;-3.5",
            "Paris;18.2",
            "Tokyo;22.9",
            "Sydney;30.1",
        )
        val expectedStations = listOf("Hamburg", "Berlin", "Paris", "Tokyo", "Sydney")
        val expectedTemps = listOf(12.0, -3.5, 18.2, 22.9, 30.1)

        val parsedStations = mutableListOf<String>()
        val parsedTemps = mutableListOf<Double>()

        for (line in lines) {
            val cs = CharSeries(line)
            val found = cs.seekTo(';')
            assertTrue(found, "';' must be present in line: $line")
            val delimPos = cs.pos - 1
            parsedStations.add(line.substring(0, delimPos))
            parsedTemps.add(line.substring(cs.pos).toDouble())
        }

        assertEquals(5, parsedStations.size, "Must parse exactly 5 station names")
        assertEquals(expectedStations, parsedStations, "All station names must match")
        for (i in expectedTemps.indices) {
            assertEquals(expectedTemps[i], parsedTemps[i], "Temperature at index $i must match")
        }
    }

    // -------------------------------------------------------------------------
    // 4. Negative temperature parsed correctly
    // -------------------------------------------------------------------------
    @Test
    fun csvNegativeTemperatureParsed() {
        val line = "Arctic;-42.7"
        val cs = CharSeries(line)
        val found = cs.seekTo(';')
        assertTrue(found, "';' must be found in Arctic line")

        val delimPos = cs.pos - 1
        val station = line.substring(0, delimPos)
        val temperature = line.substring(cs.pos).toDouble()

        assertEquals("Arctic", station)
        assertEquals(-42.7, temperature, "Negative temperature must parse correctly")
    }

    // -------------------------------------------------------------------------
    // 5. Unicode station name preserved through CharSeries round-trip
    // -------------------------------------------------------------------------
    @Test
    fun csvUnicodeStationNamePreserved() {
        val line = "日本橋;15.0"
        val cs = CharSeries(line)
        val found = cs.seekTo(';')
        assertTrue(found, "';' must be found in unicode station line")

        val delimPos = cs.pos - 1
        val station = line.substring(0, delimPos)
        val temperature = line.substring(cs.pos).toDouble()

        assertEquals("日本橋", station, "Unicode station name must be preserved")
        assertEquals(15.0, temperature)
    }

    // -------------------------------------------------------------------------
    // 6. CsvBitmap.encode on inline UByteArray: comma (0x2c) is marked ValueDelim
    //    BRC uses ';' (0x3b) — verify it is NOT flagged as ValueDelim by CsvBitmap
    //    (CsvBitmap tracks comma-CSV format; BRC scanning is done via CharSeries)
    // -------------------------------------------------------------------------
    @Test
    fun csvBitmapEncodeMarksScopeCloseForNewline() {
        // '\n' = 0x0a → ScopeClose (ordinal 2)
        // ',' = 0x2c → ValueDelim (ordinal 3)
        // ';' = 0x3b → Unchanged (ordinal 0)
        // 'A' = 0x41 → Unchanged (ordinal 0)
        val input = ubyteArrayOf(0x41u, 0x2cu, 0x0au, 0x3bu)  // A , \n ;
        val bitmap = CsvBitmap.encode(input)

        // encode packs two 4-bit nibbles per output byte
        // input[0]='A' → Unchanged(0), input[1]=',' → ValueDelim(3)  → output[0]
        // input[2]='\n' → ScopeClose(2), input[3]=';' → Unchanged(0)  → output[1]
        //
        // For nibble packing:
        //   even index i: high nibble = (csvStateEvent or (lexerEvent shl 2)) shl 4
        //   odd  index i: low  nibble = (csvStateEvent or (lexerEvent shl 2))
        //
        // input[1] = ',' → CsvStateEvent.ValueDelim.ordinal = 3
        //   high nibble of output[0] contributes 0 (index 0 is 'A' = Unchanged=0)
        //   low nibble of output[0] holds ',' result: ordinal 3, lexerEvent 0 → 3
        val outputByte0 = bitmap[0].toInt()
        val highNibble0 = (outputByte0 ushr 4) and 0xF
        val lowNibble0 = outputByte0 and 0xF

        // 'A' at even index → csvState=0, lexer=0 → packed nibble = 0
        assertEquals(0, highNibble0, "A(0x41) must produce Unchanged=0 in high nibble")
        // ',' at odd index → csvState=ValueDelim(3), lexer=0 → packed nibble = 3
        assertEquals(
            CsvBitmap.CsvStateEvent.ValueDelim.ordinal,
            lowNibble0,
            "Comma must be marked as ValueDelim in CsvBitmap output"
        )

        val outputByte1 = bitmap[1].toInt()
        val highNibble1 = (outputByte1 ushr 4) and 0xF
        val lowNibble1 = outputByte1 and 0xF

        // '\n' at even index → csvState=ScopeClose(2), lexer=0 → high nibble after shift = 2 shl 4 = 32 → but packed as 4-bit: (2 shl 4) → 0x20; high nibble = 2
        assertEquals(
            CsvBitmap.CsvStateEvent.ScopeClose.ordinal,
            highNibble1,
            "Newline must be marked as ScopeClose in CsvBitmap output"
        )
        // ';' → Unchanged(0) → low nibble = 0
        assertEquals(0, lowNibble1, "Semicolon must be Unchanged in CsvBitmap (not a comma-CSV delimiter)")
    }
}
