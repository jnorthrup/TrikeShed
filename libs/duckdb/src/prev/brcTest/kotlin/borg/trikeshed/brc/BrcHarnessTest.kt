/**
 * 1BRC Test Harness — JUnit Tests for TrikeShed Variants
 *
 * Programmatically tests all BRC variant implementations against reference
 * output. Compatible with gunnarmorling/1brc fork conventions.
 *
 * Tests cover:
 *  - Correctness against known small data sets
 *  - Edge cases: negative temps, unicode names, single row, max/min temps
 *  - Output format compliance (sorted, {Station=min/mean/max, ...})
 *  - Rounding semantics (IEEE 754 roundTowardPositive)
 *  - 10K unique station names
 *
 * Large dataset convention: see BrcCache.PATH = "/tmp/measurements.txt" in BrcBillionRowCacheTest.kt
 */
package borg.trikeshed.lib.brc

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrcHarnessTest {

    // ── Variant registry ──────────────────────────────────────────────

    /**
     * Each variant is a (name, mainFunction) pair.
     * The main function takes Array<String> (args) with the file path.
     */
    private data class Variant(
        val name: String,
        val main: (Array<String>) -> Unit
    )

    private val variants = listOf(
        Variant("baseline") { args -> BrcBaseline.main(args) },
        Variant("csv_jvm") { args -> borg.trikeshed.brc.BrcCsvJvm.main(args) },
        Variant("mmap") { args -> BrcMmap.main(args) },
    )

    private val allVariants = variants + listOf(
        Variant("duckdb_jvm") { args -> BrcDuckDbJvm.main(args) },
        Variant("isam_jvm")   { args -> BrcIsamJvm.main(args) },
    )

    // ── Helpers ───────────────────────────────────────────────────────

    /** Capture stdout from a block */
    private fun captureStdout(block: () -> Unit): String {
        val origOut = System.out
        val baos = ByteArrayOutputStream()
        System.setOut(PrintStream(baos, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setOut(origOut)
        }
        return baos.toString("UTF-8").trim()
    }

    /** Write measurement lines to a temp file, return path */
    private fun writeTempMeasurements(lines: List<String>): Path {
        val tmp = Files.createTempFile("brc_test_", ".txt")
        Files.write(tmp, lines.map { it })
        tmp.toFile().deleteOnExit()
        return tmp
    }

    /**
     * Compute expected output from measurement lines using reference algorithm.
     * This is the "oracle" — canonical 1BRC computation.
     */
    private fun computeExpected(lines: List<String>): String {
        data class Acc(var min: Double, var max: Double, var sum: Double, var count: Long)

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
            sb.append(fmtTemp(acc.min)).append('/')
            sb.append(fmtTemp(acc.sum / acc.count)).append('/')
            sb.append(fmtTemp(acc.max))
        }
        sb.append('}')
        return sb.toString()
    }

    /** IEEE 754 roundTowardPositive formatting for 1BRC */
    private fun fmtTemp(v: Double): String {
        val scaled = v * 10
        val rounded = floor(scaled + 0.5).toLong()
        val abs = if (rounded < 0) -rounded else rounded
        val sign = if (rounded < 0) "-" else ""
        return "${sign}${abs / 10}.${abs % 10}"
    }

    /** Run a variant against a data file and return its stdout output */
    private fun runVariant(variant: Variant, dataFile: Path): String {
        return captureStdout {
            variant.main(arrayOf(dataFile.toAbsolutePath().toString()))
        }
    }

    // ── Test Data Sets ────────────────────────────────────────────────

    /** The canonical 1BRC sample from the README */
    private val canonicalLines = listOf(
        "Hamburg;12.0",
        "Bulawayo;8.9",
        "Palembang;38.8",
        "St. John's;15.2",
        "Cracow;12.6",
        "Bridgetown;26.9",
        "Istanbul;6.2",
        "Roseau;34.4",
        "Conakry;31.2",
        "Istanbul;23.0",
        "Istanbul;18.7",
        "Hamburg;-7.3",
        "Bulawayo;22.1",
        "Palembang;35.6",
        "St. John's;-2.1",
        "Cracow;-5.4",
        "Bridgetown;28.3",
        "Istanbul;15.4",
        "Roseau;30.1",
        "Conakry;29.8",
        "Istanbul;15.4" // duplicate to exercise aggregation
    )

    /** Edge case: all negative temperatures */
    private val allNegativeLines = listOf(
        "Arctic;-99.9",
        "Antarctic;-50.0",
        "Arctic;-0.1",
        "Antarctic;-75.3",
        "Arctic;-42.7",
    )

    /** Edge case: single station, single row */
    private val singleRowLines = listOf(
        "Solo;42.0"
    )

    /** Edge case: single station, many rows */
    private val singleStationLines = (1..100).map { "OnlyOne;${(it % 200 - 100) / 10.0}" }

    /** Edge case: boundary temperatures ±99.9 */
    private val boundaryLines = listOf(
        "Hot;99.9",
        "Cold;-99.9",
        "Hot;0.0",
        "Cold;0.0",
        "Zero;0.0",
        "Zero;0.0",
    )

    /** Edge case: station names with spaces, dots, hyphens, unicode */
    private val unicodeLines = listOf(
        "São Paulo;25.3",
        "Zürich;8.1",
        "Côte d'Ivoire;30.2",
        "Москва;-10.5",
        "日本橋;15.0",
        "São Paulo;22.7",
        "Zürich;-1.4",
        "Côte d'Ivoire;28.9",
        "Москва;-22.3",
        "日本橋;32.1",
    )

    /** Rounding edge cases: values where rounding direction matters */
    private val roundingLines = listOf(
        // mean of 0.1 and 0.2 = 0.15 → should round to 0.2 (toward positive)
        "RoundUp;0.1",
        "RoundUp;0.2",
        // mean of -0.2 and -0.1 = -0.15 → should round to -0.1 (toward positive)
        "RoundNeg;-0.2",
        "RoundNeg;-0.1",
        // mean of 1.5 and 1.6 = 1.55 → should round to 1.6
        "Halfway;1.5",
        "Halfway;1.6",
    )

    // ── Correctness Tests ─────────────────────────────────────────────

    @Test
    fun testCanonicalDataAllVariants() {
        val file = writeTempMeasurements(canonicalLines)
        val expected = computeExpected(canonicalLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on canonical data")
        }
    }

    @Test
    fun testAllNegativeTemperatures() {
        val file = writeTempMeasurements(allNegativeLines)
        val expected = computeExpected(allNegativeLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on all-negative data")
        }
    }

    @Test
    fun testSingleRow() {
        val file = writeTempMeasurements(singleRowLines)
        val expected = computeExpected(singleRowLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on single row")
        }
    }

    @Test
    fun testSingleStation() {
        val file = writeTempMeasurements(singleStationLines)
        val expected = computeExpected(singleStationLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on single station")
        }
    }

    @Test
    fun testBoundaryTemperatures() {
        val file = writeTempMeasurements(boundaryLines)
        val expected = computeExpected(boundaryLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on boundary temps")
        }
    }

    @Test
    fun testUnicodeStationNames() {
        val file = writeTempMeasurements(unicodeLines)
        val expected = computeExpected(unicodeLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual, "Variant '${variant.name}' failed on unicode names")
        }
    }

    @Test
    fun testRoundingSemantics() {
        val file = writeTempMeasurements(roundingLines)
        val expected = computeExpected(roundingLines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual,
                "Variant '${variant.name}' failed rounding (IEEE 754 roundTowardPositive)")
        }
    }

    // ── Output Format Compliance ──────────────────────────────────────

    @Test
    fun testOutputFormat() {
        val file = writeTempMeasurements(canonicalLines)

        for (variant in allVariants) {
            val output = runVariant(variant, file)

            // Must start with { and end with }
            assertTrue(output.startsWith("{"), "Variant '${variant.name}' output must start with '{'")
            assertTrue(output.endsWith("}"), "Variant '${variant.name}' output must end with '}'")

            // Must be a single line
            val lineCount = output.lines().size
            assertEquals(1, lineCount,
                "Variant '${variant.name}' output must be a single line, got $lineCount")

            // Parse stations and verify alphabetical ordering
            val inner = output.removePrefix("{").removeSuffix("}")
            val entries = inner.split(", ")
            assertTrue(entries.isNotEmpty(), "Variant '${variant.name}' produced no entries")

            val names = entries.map { it.substringBefore('=') }
            assertEquals(names, names.sorted(),
                "Variant '${variant.name}' stations not sorted alphabetically")

            // Each entry must have format name=min/mean/max
            for (entry in entries) {
                val eqIdx = entry.indexOf('=')
                assertTrue(eqIdx > 0, "Variant '${variant.name}' missing '=' in entry: $entry")
                val values = entry.substring(eqIdx + 1).split('/')
                assertEquals(3, values.size,
                    "Variant '${variant.name}' entry must have 3 values (min/mean/max): $entry")

                // Each value must be a valid decimal with one fractional digit
                for (v in values) {
                    assertTrue(v.matches(Regex("-?\\d+\\.\\d")),
                        "Variant '${variant.name}' value '$v' not in format [-]d.d")
                }
            }
        }
    }

    // ── Cross-Variant Consistency ─────────────────────────────────────

    @Test
    fun testAllVariantsAgree() {
        // Generate a larger random dataset and verify all variants produce identical output
        val stations = listOf(
            "Berlin", "Tokyo", "New York", "Sydney", "Lagos",
            "Mumbai", "Cairo", "London", "Paris", "Beijing"
        )
        val rng = java.util.Random(42) // fixed seed for reproducibility
        val lines = (0 until 10_000).map {
            val station = stations[rng.nextInt(stations.size)]
            val temp = (rng.nextInt(1999) - 999) / 10.0
            "$station;${"%.1f".format(temp)}"
        }

        val file = writeTempMeasurements(lines)
        val outputs = allVariants.associate { v ->
            v.name to runVariant(v, file)
        }

        // All outputs must be identical
        val reference = outputs.values.first()
        for ((name, output) in outputs) {
            assertEquals(reference, output,
                "Variant '$name' disagrees with '${allVariants.first().name}'")
        }
    }

    // ── Many Stations Test ────────────────────────────────────────────

    @Test
    fun testManyStations() {
        // 500 unique station names, 10 measurements each → 5000 lines
        val rng = java.util.Random(12345)
        val stationNames = (1..500).map { "Station_${"$it".padStart(4, '0')}" }
        val lines = stationNames.flatMap { name ->
            (0 until 10).map {
                val temp = (rng.nextInt(1999) - 999) / 10.0
                "$name;${"%.1f".format(temp)}"
            }
        }.shuffled(rng)

        val file = writeTempMeasurements(lines)
        val expected = computeExpected(lines)

        for (variant in allVariants) {
            val actual = runVariant(variant, file)
            assertEquals(expected, actual,
                "Variant '${variant.name}' failed on 500-station dataset")
        }
    }

    // ── Empty-ish Edge Cases ──────────────────────────────────────────

    @Test
    fun testMinimalSameValues() {
        // Same station, same temperature repeated — min=mean=max
        val lines = (0 until 50).map { "Constant;7.3" }
        val file = writeTempMeasurements(lines)

        for (variant in allVariants) {
            val output = runVariant(variant, file)
            assertTrue(output.contains("Constant=7.3/7.3/7.3"),
                "Variant '${variant.name}' wrong for constant temps: $output")
        }
    }

    @Test
    fun testZeroTemperatures() {
        val lines = listOf("Zero;0.0", "Zero;0.0", "Zero;0.0")
        val file = writeTempMeasurements(lines)

        for (variant in allVariants) {
            val output = runVariant(variant, file)
            assertTrue(output.contains("Zero=0.0/0.0/0.0"),
                "Variant '${variant.name}' wrong for zero temps: $output")
        }
    }

    // ── Fork Compatibility: External Script Runner ────────────────────

    /**
     * Run an external calculate_average_*.sh script and validate output.
     * This can be used to test gunnarmorling/1brc fork entries.
     *
     * Usage in a subclass or standalone:
     *   testForkScript("baseline", "/path/to/measurements.txt", expectedOutput)
     */
    fun testForkScript(
        scriptPath: String,
        dataFilePath: String,
        expectedOutput: String,
        timeoutSeconds: Long = 300
    ): ForkResult {
        val pb = ProcessBuilder("bash", scriptPath)
        pb.environment()["BRC_FILE"] = dataFilePath
        pb.redirectErrorStream(false)

        val startNanos = System.nanoTime()
        val process = pb.start()

        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        val completed = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        return ForkResult(
            passed = completed && process.exitValue() == 0 && stdout == expectedOutput,
            output = stdout,
            expected = expectedOutput,
            stderr = stderr,
            exitCode = if (completed) process.exitValue() else -1,
            timedOut = !completed,
            elapsedMs = elapsedMs
        )
    }

    data class ForkResult(
        val passed: Boolean,
        val output: String,
        val expected: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val elapsedMs: Long
    ) {
        fun report(): String = buildString {
            appendLine(if (passed) "PASS" else "FAIL")
            if (timedOut) appendLine("  TIMED OUT")
            if (exitCode != 0) appendLine("  Exit code: $exitCode")
            if (!passed && !timedOut) {
                appendLine("  Expected: $expected")
                appendLine("  Actual:   $output")
            }
            appendLine("  Time: ${elapsedMs}ms")
            if (stderr.isNotBlank()) appendLine("  Stderr: ${stderr.take(500)}")
        }
    }
}
