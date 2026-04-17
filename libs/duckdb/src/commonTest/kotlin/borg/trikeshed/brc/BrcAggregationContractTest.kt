package borg.trikeshed.brc

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-Kotlin BRC aggregation oracle — no product code dependencies.
 *
 * Parsing uses the CharSeries pattern: scan for ';', split station + temperature.
 * Mean rounding follows IEEE 754 round-half-toward-positive (roundHalfUp on scaled value).
 */
class BrcAggregationContractTest {

    // ── Oracle ────────────────────────────────────────────────────────

    private data class Acc(var min: Double, var max: Double, var sum: Double, var count: Long)

    private fun oracle(lines: List<String>): String {
        val map = LinkedHashMap<String, Acc>()
        for (line in lines) {
            if (line.isBlank()) continue
            val sep = line.indexOf(';')
            if (sep < 0) continue
            val station = line.substring(0, sep)
            val temp = line.substring(sep + 1).toDouble()
            val acc = map.getOrPut(station) { Acc(temp, temp, 0.0, 0) }
            if (temp < acc.min) acc.min = temp
            if (temp > acc.max) acc.max = temp
            acc.sum += temp
            acc.count++
        }
        val sb = StringBuilder("{")
        map.entries.sortedBy { it.key }.forEachIndexed { i, (name, acc) ->
            if (i > 0) sb.append(", ")
            sb.append(name).append('=')
            sb.append(fmt(acc.min)).append('/')
            sb.append(fmt(acc.sum / acc.count)).append('/')
            sb.append(fmt(acc.max))
        }
        sb.append('}')
        return sb.toString()
    }

    /** IEEE 754 round-half-toward-positive, one decimal place */
    private fun fmt(v: Double): String {
        val scaled = v * 10.0
        val rounded = floor(scaled + 0.5).toLong()
        val abs = if (rounded < 0) -rounded else rounded
        val sign = if (rounded < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }

    // ── Test Data ─────────────────────────────────────────────────────

    private val canonical = listOf(
        "Hamburg;12.0", "Bulawayo;8.9", "Palembang;38.8", "St. John's;15.2",
        "Cracow;12.6", "Bridgetown;26.9", "Istanbul;6.2", "Roseau;34.4",
        "Conakry;31.2", "Istanbul;23.0", "Istanbul;18.7", "Hamburg;-7.3",
        "Bulawayo;22.1", "Palembang;35.6", "St. John's;-2.1", "Cracow;-5.4",
        "Bridgetown;28.3", "Istanbul;15.4", "Roseau;30.1", "Conakry;29.8",
        "Istanbul;15.4"
    )

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    fun canonicalDataProducesExpectedOutput() {
        val result = oracle(canonical)
        assertTrue(result.startsWith("{"), "Must start with {")
        assertTrue(result.endsWith("}"), "Must end with }")
        // Verify specific known values for Istanbul: min=6.2, mean=(6.2+23.0+18.7+15.4+15.4)/5=15.74→15.7, max=23.0
        assertTrue(result.contains("Istanbul=6.2/"), "Istanbul min must be 6.2, got: $result")
        assertTrue(result.contains("/23.0"), "Istanbul max must be 23.0, got: $result")
        // Hamburg: min=-7.3, max=12.0, mean=(12.0-7.3)/2=2.35→2.4
        assertTrue(result.contains("Hamburg=-7.3/2.4/12.0"), "Hamburg triple wrong, got: $result")
    }

    @Test
    fun allNegativeTemperaturesAggregatedCorrectly() {
        val lines = listOf("Arctic;-99.9", "Antarctic;-50.0", "Arctic;-0.1", "Antarctic;-75.3", "Arctic;-42.7")
        val result = oracle(lines)
        // Antarctic: min=-75.3, max=-50.0, mean=(-50.0-75.3)/2=-62.65→-62.6 (round toward positive)
        assertTrue(result.contains("Antarctic=-75.3/-62.6/-50.0"), "Antarctic wrong, got: $result")
        // Arctic: min=-99.9, max=-0.1, mean=(-99.9-0.1-42.7)/3=-47.57→-47.6
        assertTrue(result.contains("Arctic=-99.9/"), "Arctic missing, got: $result")
        assertTrue(result.contains("/-0.1"), "Arctic max wrong, got: $result")
    }

    @Test
    fun singleRowProducesTrivialResult() {
        val result = oracle(listOf("Solo;42.0"))
        assertEquals("{Solo=42.0/42.0/42.0}", result)
    }

    @Test
    fun roundingFollowsRoundHalfTowardPositive() {
        // mean of 0.1 and 0.2 = 0.15 → scaled=1.5 → floor(1.5+0.5)=floor(2.0)=2 → "0.2"
        val r1 = oracle(listOf("RoundUp;0.1", "RoundUp;0.2"))
        assertTrue(r1.contains("RoundUp=0.1/0.2/0.2"), "0.15 must round to 0.2, got: $r1")

        // mean of -0.2 and -0.1 = -0.15 → IEEE 754: -0.15000000000000002 → scaled≈-1.5000000000000002
        // → floor(-1.5000000000000002+0.5)=floor(-1.0000000000000002)=-2 → "-0.2"
        val r2 = oracle(listOf("RoundNeg;-0.2", "RoundNeg;-0.1"))
        assertTrue(r2.contains("RoundNeg=-0.2/-0.2/-0.1"), "-0.15 (IEEE 754) must round to -0.2, got: $r2")
    }

    @Test
    fun outputIsSortedAlphabetically() {
        val lines = listOf("Zebra;1.0", "Alpha;2.0", "Mango;3.0", "Beta;4.0")
        val result = oracle(lines)
        val inner = result.removePrefix("{").removeSuffix("}")
        val names = inner.split(", ").map { it.substringBefore('=') }
        assertEquals(listOf("Alpha", "Beta", "Mango", "Zebra"), names, "Must be alphabetically sorted")
    }

    @Test
    fun outputFormatMatchesSpec() {
        val result = oracle(listOf("Test;7.5", "Test;2.5"))
        // format: {Name=min/mean/max} — each value is [-]d+.d
        val pattern = Regex("^\\{[^=]+=(-?\\d+\\.\\d)(/(-?\\d+\\.\\d)){2}(, [^=]+=(-?\\d+\\.\\d)(/(-?\\d+\\.\\d)){2})*}$")
        assertTrue(pattern.matches(result), "Output format wrong: $result")
        // mean of 7.5 and 2.5 = 5.0
        assertEquals("{Test=2.5/5.0/7.5}", result)
    }

    @Test
    fun unicodeStationNamesPreserved() {
        val lines = listOf("São Paulo;25.3", "日本橋;15.0", "São Paulo;22.7", "日本橋;32.1")
        val result = oracle(lines)
        assertTrue(result.contains("São Paulo="), "Unicode station São Paulo missing")
        assertTrue(result.contains("日本橋="), "Unicode station 日本橋 missing")
    }

    @Test
    fun manyStationsAllPresent() {
        val stations = (1..20).map { "Station_$it" }
        val lines = stations.flatMap { name -> listOf("$name;10.0", "$name;20.0") }
        val result = oracle(lines)
        for (s in stations) {
            assertTrue(result.contains("$s=10.0/15.0/20.0"), "Station $s missing or wrong: $result")
        }
    }

    @Test
    fun constantTemperatureMinMeanMaxEqual() {
        val lines = (0 until 50).map { "Constant;7.3" }
        val result = oracle(lines)
        assertTrue(result.contains("Constant=7.3/7.3/7.3"), "Constant temps must give min=mean=max: $result")
    }
}
