package borg.trikeshed.brc

import borg.trikeshed.lib.CharSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IO Surface Audit gate test for BRC processing.
 *
 * Proves that pure Kotlin CharSeries/String operations can parse a BRC line
 * of the form "Station;temperature" without any JVM-only or java.* APIs.
 */
class BrcIoGateTest {

    @Test
    fun parseSingleLineExtractsStationAndTemperature() {
        val line = "Hamburg;12.0"
        val cs = CharSeries(line)

        // Seek to ';' — advances pos to the char after ';'
        val found = cs.seekTo(';')
        assertTrue(found, "Expected ';' delimiter in line")

        // Station is everything before ';': positions 0 until (pos-1)
        val stationEnd = cs.pos - 1
        val station = line.substring(0, stationEnd)

        // Temperature is everything after ';': from pos to end
        val tempStr = line.substring(cs.pos)
        val temperature = tempStr.toDouble()

        assertEquals("Hamburg", station)
        assertEquals(12.0, temperature)
    }
}
