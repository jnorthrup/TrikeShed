package borg.trikeshed.miniduck.columnar

import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.miniduck.at
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.couch.kline.TimeSpan
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlin.math.max
import kotlin.math.min
import kotlin.test.*

/**
 * RED tests for the full Binance archive conversion cycle:
 *
 *   monthly.zip → sorted DocRowVec MiniCursor
 *              → IsamVolume (fixed-width, keyed on openTime)
 *              → IsamCursor (seek / range)
 *              → IoMux / pancake transforms
 *
 * These tests document the MISSING algebra at each stage.
 * Each `assertFails` pins what needs to be built.
 *
 * Donor: dreamer-kmm exchange/SimulationReplay, couch kline algebra,
 *        miniduck IsamCursor/IsamVolume stubs.
 */

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 0 — synthetic CSV corpus for testing
   ═══════════════════════════════════════════════════════════════════════ */

/** Three unsorted daily CSV fragments covering 3 consecutive days. */
private fun unsortedCsvFragment(dayOffset: Int, symbol: String = "BTCUSDT"): String {
    val baseTime = 1709251200000L + dayOffset * 86400_000L // 2024-03-01 + N days
    return buildString {
        appendLine("Open_time,Open,High,Low,Close,Volume,Close_time,Quote_asset_volume,Number_of_trades,Taker_buy_base_asset_volume,Taker_buy_quote_asset_volume,Ignore")
        // rows deliberately out of order within each day
        appendLine("${baseTime + 3_600_000},69000.0,69100.0,68900.0,69050.0,125.4,${baseTime + 3_661_000},8660000.0,1420,62.7,4330.0,0")
        appendLine("${baseTime + 0L},68800.0,68950.0,68750.0,68880.0,118.2,${baseTime + 61_000},8130000.0,1350,59.1,4070.0,0")
        appendLine("${baseTime + 7_200_000},69100.0,69200.0,69050.0,69180.0,132.0,${baseTime + 7_261_000},9120000.0,1580,66.0,4560.0,0")
        appendLine("${baseTime + 1_800_000},68900.0,69000.0,68850.0,68920.0,110.5,${baseTime + 1_861_000},7620000.0,1280,55.2,3810.0,0")
        appendLine("${baseTime + 5_400_000},69050.0,69150.0,68980.0,69080.0,128.9,${baseTime + 5_461_000},8900000.0,1510,64.4,4440.0,0")
        appendLine("${baseTime + 10_800_000},69200.0,69300.0,69150.0,69280.0,140.0,${baseTime + 10_861_000},9680000.0,1620,70.0,4840.0,0")
    }
}

/** ExtendedKline schema with openTime as the primary seek key. */
private val isamSchema = listOf(
    ColumnSchema("openTime", ColumnType.Long, indexPluginName = "ZranIndex"),
    ColumnSchema("open", ColumnType.Double),
    ColumnSchema("high", ColumnType.Double),
    ColumnSchema("low", ColumnType.Double),
    ColumnSchema("close", ColumnType.Double),
    ColumnSchema("volume", ColumnType.Double),
    ColumnSchema("closeTime", ColumnType.Long),
    ColumnSchema("quoteAssetVolume", ColumnType.Double),
    ColumnSchema("trades", ColumnType.Int),
    ColumnSchema("takerBuyBaseVolume", ColumnType.Double),
    ColumnSchema("takerBuyQuoteVolume", ColumnType.Double),
    ColumnSchema("symbol", ColumnType.String),
    ColumnSchema("timespan", ColumnType.String),
)

/* ═══════════════════════════════════════════════════════════════════════
   STUBS — symbols from other modules / not yet implemented
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * Stub for IsamVolume generation — see libs/miniduck/src/commonMain/.../IsamVolume.kt
 * The real signature accepts a schema and output dir; here we pin the missing overloads.
 */
fun generateIsam(
    cursor: MiniCursor,
    schema: List<ColumnSchema>,
    tempDir: String,
): IsamVolume = throw NotImplementedError("generateIsam — IsamVolume generation not yet implemented")

/** IsamVolume query interface. */
interface IsamVolume {
    fun path(): String
    fun blockCount(): Int
    fun blockMeta(blockId: Int): BlockMeta
    fun blockData(blockId: Int): ByteArray
    fun meta(): Map<String, Any?>
    fun appendBlock(): Nothing = throw IllegalStateException("Cannot append to sealed IsamVolume")
}

/** Block metadata within an IsamVolume. */
data class BlockMeta(val rowCount: Int, val firstOpenTime: Long, val lastOpenTime: Long)

/** IoMux: row assembler that feeds assembleRow semantics over MiniCursor. */
object IoMux {
    fun assembleFrom(cursor: MiniCursor): List<DocRowVec> =
        throw NotImplementedError("IoMux.assembleFrom not yet implemented")
    fun assembleRows(cursor: MiniCursor): List<DocRowVec> =
        throw NotImplementedError("IoMux.assembleRows not yet implemented")
}

/** Pancake horizon window + OHLCV flatten over MiniCursor. */
object Pancake {
    fun horizon(cursor: MiniCursor, windowSize: Int): MiniCursor =
        throw NotImplementedError("Pancake.horizon not yet implemented")
    fun ohlcv(cursor: MiniCursor): MiniCursor =
        throw NotImplementedError("Pancake.ohlcv not yet implemented")
}

/** CCEK SupervisorJob states. */
object SupervisorJob {
    enum class State { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }
}

/**
 * Local KlineCsvParser stub for miniduck tests.
 * Parses synthetic CSV strings into List<Kline> — mirrors dreamer-kmm's KlineCsvParser.
 */
object KlineCsvParser {
    private const val HEADER_MARKER = "Open_time"

    fun parseCsv(csv: String, symbol: String, timespan: TimeSpan): List<Kline> =
        csv.lines()
            .mapNotNull { parseLine(it, symbol, timespan) }
            .sortedBy { it.openTime }
            .distinctBy { it.openTime }

    fun parseLine(line: String, symbol: String, timespan: TimeSpan): Kline? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(HEADER_MARKER)) return null
        val fields = trimmed.split(",")
        if (fields.size < 11) return null
        return try {
            Kline(
                symbol = symbol,
                timespan = timespan,
                openTime = fields[0].trim().toLong(),
                open = fields[1].trim().toDouble(),
                high = fields[2].trim().toDouble(),
                low = fields[3].trim().toDouble(),
                close = fields[4].trim().toDouble(),
                volume = fields[5].trim().toDouble(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 1 — zip entry lines → sorted MiniCursor of DocRowVec
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: BzSortedCursor — given N CSV strings (simulating zip entry bytes),
 * parse and globally sort by openTime into a MiniCursor of ExtendedKline
 * DocRowVec rows, then expose via IoMux assembleRow semantics.
 *
 * The key invariants:
 *   - rows are globally sorted by openTime across all input fragments
 *   - duplicate openTime rows are deduplicated
 *   - the cursor is lazy: rows are projected on access (Series semantics)
 *   - assembleRow on the cursor produces identical output to toDocRowVec()
 */
class BzSortedCursor_redTest {
    /** Parse CSV lines to ExtendedKline DocRowVec rows, unsorted. */
    @Test fun `parseCsvLines produces DocRowVec rows`() {
        val csv = unsortedCsvFragment(0)
        val rows = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
        assertEquals(6, rows.size)
        val row = rows.first().toDocRowVec()
        assertTrue(row.keys.contains("openTime"))
        assertTrue(row.keys.contains("volume"))
    }

    /** Merge-sort two CSV strings globally by openTime. */
    @Test fun `BzSortedCursor mergesort two fragments by openTime`() {
        val day0 = unsortedCsvFragment(0)
        val day1 = unsortedCsvFragment(1)
        // BzSortedCursor.from(listOf(day0, day1)) → MiniCursor sorted globally
        assertFailsWith<NotImplementedError> {
            BzSortedCursor.from(listOf(day0, day1), "BTCUSDT", TimeSpan.Minutes1)
        }
    }

    /** Merge-sort N CSV strings (simulating monthly.zip → N daily entries). */
    @Test fun `BzSortedCursor mergesort N fragments`() {
        val fragments = (0..2).map { unsortedCsvFragment(it) }
        // monthly.zip with 3 daily entries → 18 rows total, globally sorted
        assertFailsWith<NotImplementedError> {
            BzSortedCursor.from(fragments, "BTCUSDT", TimeSpan.Minutes1)
        }
    }

    /** Duplicate openTime rows are deduplicated. */
    @Test fun `BzSortedCursor deduplicates duplicate openTime`() {
        val csv0 = unsortedCsvFragment(0)
        val csv1 = unsortedCsvFragment(0) // same day — same rows
        assertFailsWith<NotImplementedError> {
            val cursor = BzSortedCursor.from(listOf(csv0, csv1), "BTCUSDT", TimeSpan.Minutes1)
            assertEquals(6, cursor.size) // not 12
        }
    }

    /** Global sort order is ascending openTime. */
    @Test fun `BzSortedCursor order is ascending openTime`() {
        val day0 = unsortedCsvFragment(0)
        val day1 = unsortedCsvFragment(1)
        assertFailsWith<NotImplementedError> {
            val cursor = BzSortedCursor.from(listOf(day0, day1), "BTCUSDT", TimeSpan.Minutes1)
            // first openTime < second openTime < ... < last openTime
            var prev = -1L
            for (i in 0 until cursor.size) {
                val row = cursor.at(i) as DocRowVec
                val ot = row["openTime"] as Long
                assertTrue(ot > prev, "openTime not ascending at index $i")
                prev = ot
            }
        }
    }

    /** Cursor is lazy — projection happens at access time. */
    @Test fun `BzSortedCursor is lazy Series projection`() {
        val day0 = unsortedCsvFragment(0)
        assertFailsWith<NotImplementedError> {
            val cursor = BzSortedCursor.from(listOf(day0), "BTCUSDT", TimeSpan.Minutes1)
            // cursor.size j { idx -> ... } — no computation until .at() called
            val series: MiniCursor = cursor.size j { i -> cursor.at(i) as DocRowVec }
            assertEquals(6, series.size)
        }
    }

    /** IoMux assembleRow semantics on BzSortedCursor. */
    @Test fun `BzSortedCursor assembleRow produces ExtendedKline fields`() {
        val csv = unsortedCsvFragment(0)
        assertFailsWith<NotImplementedError> {
            val cursor = BzSortedCursor.from(listOf(csv), "BTCUSDT", TimeSpan.Minutes1)
            val row = cursor.at(0) as DocRowVec
            // assembleRow targets: openTime, open, high, low, close, volume, closeTime,
            // quoteAssetVolume, trades, takerBuyBaseVolume, takerBuyQuoteVolume
            assertTrue(row.keys.containsAll(listOf(
                "openTime", "open", "high", "low", "close", "volume",
                "closeTime", "quoteAssetVolume", "trades",
                "takerBuyBaseVolume", "takerBuyQuoteVolume"
            )))
        }
    }
}

/**
 * BzSortedCursor: sorted MiniCursor from CSV entry strings.
 * Missing: the whole class and its merge-sort algebra.
 */
class BzSortedCursor private constructor(private val cursor: MiniCursor) {
    val size: Int get() = cursor.size
    fun at(idx: Int): MiniRowVec = cursor.at(idx)

    companion object {
        fun from(csvStrings: List<String>, symbol: String, timespan: TimeSpan): MiniCursor {
            throw NotImplementedError("BzSortedCursor.from — merge-sort CSV fragments globally by openTime")
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 2 — sorted MiniCursor → IsamVolume with block index
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: IsamVolume generation from sorted MiniCursor.
 *
 * Fixed-width row format: 128 bytes per row so byte_offset = idx * 128.
 * Block size: 4096 rows (~2.8 days of 1m klines).
 * Primary index: openTime → block + within-block position.
 *
 * Key invariants:
 *   - rows are written in openTime order (input must be sorted)
 *   - each block is independently compressed
 *   - index maps openTime → block_id + within-block record index
 *   - IsamVolume is sealed after generation (immutable)
 */
class BzIsamVolume_redTest {

    /** IsamVolume.generateIsam accepts sorted MiniCursor. */
    @Test fun `IsamVolume-generateIsam from sorted MiniCursor`() {
        val csv = unsortedCsvFragment(0)
        val sortedCursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows ->
                rows.size j { i -> rows[i].toDocRowVec() }
            }
        assertFailsWith<NotImplementedError> {
            generateIsam(sortedCursor, isamSchema, tempDir = "/tmp/bztest")
        }
    }

    /** IsamVolume schema enforces openTime as Long. */
    @Test fun `IsamVolume-schema requires openTime as Long`() {
        val badSchema = listOf(ColumnSchema("openTime", ColumnType.String))
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        assertFailsWith<NotImplementedError> {
            generateIsam(cursor, badSchema, tempDir = "/tmp/bztest")
        }
    }

    /** Block index is written with the volume. */
    @Test fun `IsamVolume-writes ZranIndex block index`() {
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        assertFailsWith<NotImplementedError> {
            val vol = generateIsam(cursor, isamSchema, tempDir = "/tmp/bztest")
            // volume.meta must contain block index entries
            val hasIndex = vol.meta().containsKey("zran_index")
            assertTrue(hasIndex)
        }
    }

    /** Fixed-width row format: 128 bytes. */
    @Test fun `IsamVolume-fixed width 128 bytes per row`() {
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        assertFailsWith<NotImplementedError> {
            val vol = generateIsam(cursor, isamSchema, tempDir = "/tmp/bztest")
            // block 0 data file size / row_count == 128
            val block0Size = vol.blockData(0).size
            val rowCount = vol.blockMeta(0).rowCount
            assertEquals(0, block0Size % 128, "block data not fixed-width")
            assertEquals(128 * rowCount, block0Size)
        }
    }

    /** 4096 rows per block boundary. */
    @Test fun `IsamVolume-blocks 4096 rows each`() {
        // 18 rows < 4096 → single block
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        assertFailsWith<NotImplementedError> {
            val vol = generateIsam(cursor, isamSchema, tempDir = "/tmp/bztest")
            assertEquals(1, vol.blockCount())
        }
    }

    /** Sealed volume rejects further writes. */
    @Test fun `IsamVolume-sealed rejects append`() {
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        assertFailsWith<NotImplementedError> {
            val vol = generateIsam(cursor, isamSchema, tempDir = "/tmp/bztest")
            val block = vol.appendBlock()
            assertTrue(false, "appendBlock should fail on sealed volume")
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 3 — IsamVolume → IsamCursor with seek / range
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: IsamCursor seek/range over IsamVolume.
 *
 * Key invariants:
 *   - seek(openTime) positions cursor at first row with openTime >= target
 *   - range(start, end) returns cursor over [start, end) openTime window
 *   - cursor is a MiniCursor — compatible with IoMux / pancake
 *   - ZranIndex plugin enables random-access decompression of target block
 */
class BzIsamCursor_redTest {

    private fun tempVolume(): IsamVolume {
        val csv = unsortedCsvFragment(0)
        val cursor = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
            .let { rows -> rows.size j { i -> rows[i].toDocRowVec() } }
        return generateIsam(cursor, isamSchema, tempDir = "/tmp/bztest")
    }

    /** IsamCursor.open on volume directory. */
    @Test fun `IsamCursor-open reads IsamVolume`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val dir = vol.path()
            val cursor = IsamCursor.open(dir)
            assertEquals(6, cursor.size)
        }
    }

    /** seek positions at first row with openTime >= target. */
    @Test fun `IsamCursor-seek positions correctly`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            // seek to day0 openTime + 1h (should land on row index 1)
            val target = 1709251200000L + 3_600_000L
            cursor.seek(target)
            val row = cursor.current() as DocRowVec
            val openTime = row["openTime"] as Long
            assertTrue(openTime >= target, "seek should land at first row >= target")
        }
    }

    /** seek before first row positions at row 0. */
    @Test fun `IsamCursor-seek before first returns row 0`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            val target = 0L
            cursor.seek(target)
            assertEquals(0, cursor.position())
        }
    }

    /** range returns cursor over [start, end). */
    @Test fun `IsamCursor-range returns bounded cursor`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            val start = 1709251200000L
            val end = 1709251200000L + 3_600_000L
            val window = cursor.range(start, end)
            // first row openTime >= start, last row openTime < end
            assertTrue(window.size >= 1)
        }
    }

    /** next() advances within the cursor. */
    @Test fun `IsamCursor-next advances`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            cursor.seek(0)
            val before = cursor.position()
            cursor.next()
            assertEquals(before + 1, cursor.position())
        }
    }

    /** next() returns false at end of cursor. */
    @Test fun `IsamCursor-next returns false at end`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            cursor.seek(0)
            // advance past last row
            while (cursor.next()) { /* spin */ }
            val hasMore = cursor.next()
            assertFalse(hasMore)
        }
    }

    /** current() returns the DocRowVec at current position. */
    @Test fun `IsamCursor-current returns DocRowVec`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            cursor.seek(0)
            val row = cursor.current() as DocRowVec
            assertTrue(row.keys.contains("openTime"))
            assertTrue(row.keys.contains("volume"))
        }
    }

    /** ZranIndex enables block-level random access. */
    @Test fun `IsamCursor-ZranIndex enables seek into compressed block`() {
        assertFailsWith<NotImplementedError> {
            val vol = tempVolume()
            val cursor = IsamCursor.open(vol.path())
            // the index plugin is ZranIndex — verify it is loaded
            val plugin = cursor.indexPlugin()
            assertTrue(plugin is ZranIndex)
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 4 — IsamCursor → IoMux → pancake transforms
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: IoMux and pancake transforms on IsamCursor.
 *
 * The IsamCursor is a MiniCursor, so it plugs directly into the existing
 * MiniCursor transform algebra (groupBy, where, project, etc.).
 *
 * IoMux: the multiplexer that feeds assembleRow semantics.
 * Pancake: horizon window + OHLCV flatten (already in dreamer-kmm Pancake.kt).
 */
class BzIoMuxPancake_redTest {

    private fun tempCursor(): MiniCursor {
        val csv = unsortedCsvFragment(0)
        val sorted = KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1)
            .sortedBy { it.openTime }
        return sorted.size j { i -> sorted[i].toDocRowVec() }
    }

    /** IsamCursor feeds into IoMux assembleRow. */
    @Test fun `IsamCursor feeds IoMux assembleRow`() {
        assertFailsWith<NotImplementedError> {
            val vol = generateIsam(tempCursor(), isamSchema, tempDir = "/tmp/bztest")
            val cursor = IsamCursor.open(vol.path())
            val assembled = IoMux.assembleFrom(cursor)
            assertTrue(assembled.isNotEmpty())
        }
    }

    /** Pancake horizon window on IsamCursor. */
    @Test fun `IsamCursor pancake horizon window`() {
        assertFailsWith<NotImplementedError> {
            val cursor = IsamCursor.open("/tmp/bztest")
            // Pancake.horizon(windowSize = 14) over 1m klines → ~14m window
            val windowed = Pancake.horizon(cursor, 14)
            assertTrue(windowed.size > 0)
        }
    }

    /** Pancake OHLCV flatten on IsamCursor. */
    @Test fun `IsamCursor pancake OHLCV flatten`() {
        assertFailsWith<NotImplementedError> {
            val cursor = IsamCursor.open("/tmp/bztest")
            // Pancake.ohlcv(cursor) → cursor of OHLCV aggregates per window
            val ohlcv = Pancake.ohlcv(cursor)
            assertTrue(ohlcv.size > 0)
        }
    }

    /** groupBy on IsamCursor (downstream of range seek). */
    @Test fun `IsamCursor range then groupBy`() {
        assertFailsWith<NotImplementedError> {
            val cursor = IsamCursor.open("/tmp/bztest")
            val start = 1709251200000L
            val end = 1709251200000L + 7_200_000L
            val window = cursor.range(start, end)
            // bucket by hour: groupBy extracts hour from openTime
            val grouped = window.groupBy("openTime", Agg.count())
            assertTrue(grouped.size >= 1)
        }
    }

    /** where filter on IsamCursor. */
    @Test fun `IsamCursor where filter`() {
        assertFailsWith<NotImplementedError> {
            val cursor = IsamCursor.open("/tmp/bztest")
            val filtered = cursor.where(Gt("volume", 120.0))
            assertTrue(filtered.size <= cursor.size)
            // all returned rows must satisfy volume > 120
            for (i in 0 until filtered.size) {
                val row = filtered.at(i) as DocRowVec
                val vol = row["volume"] as Double
                assertTrue(vol > 120.0)
            }
        }
    }

    /** project on IsamCursor. */
    @Test fun `IsamCursor project reduces columns`() {
        assertFailsWith<NotImplementedError> {
            val cursor = IsamCursor.open("/tmp/bztest")
            val projected = cursor.project("openTime", "open", "high", "low", "close", "volume")
            val row = projected.at(0) as DocRowVec
            assertEquals(6, row.keys.size)
            assertTrue(row.keys.contains("openTime"))
            assertFalse(row.keys.contains("closeTime"))
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 5 — full cycle: zip bytes → sorted → IsamVolume → seek → pancake
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: end-to-end cycle test.
 *
 * Monthly zip (simulated as List<String> of CSV bytes) → BzSortedCursor →
 * generateIsam → IsamCursor.open → range seek → pancake → result.
 */
class BzFullCycle_redTest {

    /** Full cycle: 3 daily fragments → IsamVolume → range seek → OHLCV. */
    @Test fun `full cycle zip to pancake OHLCV`() {
        assertFailsWith<NotImplementedError> {
            val fragments = (0..2).map { unsortedCsvFragment(it) }
            // Stage 1: parse + global sort
            val sorted = BzSortedCursor.from(fragments, "BTCUSDT", TimeSpan.Minutes1)
            assertEquals(18, sorted.size) // 6 rows × 3 days

            // Stage 2: write IsamVolume
            val vol = generateIsam(sorted, isamSchema, tempDir = "/tmp/bztest")
            assertEquals(1, vol.blockCount()) // 18 < 4096

            // Stage 3: open + range seek
            val cursor = IsamCursor.open(vol.path())
            val day0Start = 1709251200000L
            val day0End = day0Start + 86400_000L
            val window = cursor.range(day0Start, day0End)

            // Stage 4: pancake OHLCV flatten
            val ohlcv = Pancake.ohlcv(window)
            assertTrue(ohlcv.size > 0, "OHLCV window should produce at least 1 candle")
        }
    }

    /** Full cycle with block boundary (≥4096 rows → 2 blocks). */
    @Test fun `full cycle crosses block boundary`() {
        assertFailsWith<NotImplementedError> {
            // Build 4096 + 1 rows: two full blocks
            val fragments = (0..30).map { unsortedCsvFragment(it) } // 31 × 6 ≈ 186 rows (still < 4096)
            // For a real test we'd generate 4096+ rows; here just verify blockCount algebra
            val sorted = BzSortedCursor.from(fragments, "BTCUSDT", TimeSpan.Minutes1)
            val vol = generateIsam(sorted, isamSchema, tempDir = "/tmp/bztest")
            // once we have real data: assertTrue(vol.blockCount() >= 2)
            assertTrue(vol.blockCount() >= 1) // placeholder until real large data
        }
    }

    /** IoMux reads IsamCursor rows into assembleRow pipeline. */
    @Test fun `full cycle IoMux assembleRow pipeline`() {
        assertFailsWith<NotImplementedError> {
            val fragments = listOf(unsortedCsvFragment(0))
            val sorted = BzSortedCursor.from(fragments, "BTCUSDT", TimeSpan.Minutes1)
            val vol = generateIsam(sorted, isamSchema, tempDir = "/tmp/bztest")
            val cursor = IsamCursor.open(vol.path())

            // IoMux.assembleRows iterates cursor and builds structured output
            val assembled = IoMux.assembleRows(cursor)
            assertEquals(6, assembled.size)
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════
   STAGE 6 — KlineBlock mutable→sealed → asCursor() pipeline
   ═══════════════════════════════════════════════════════════════════════ */

/**
 * RED: KlineBlock mutable→sealed → asCursor() feeding into IoMux.
 */
class BzKlineBlock_redTest {

    private fun mutableKlineBlock(): KlineBlock {
        val block = KlineBlock.mutable(TimeSpan.Minutes1)
        val csv = unsortedCsvFragment(0)
        KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1).forEach { block.append(it) }
        return block
    }

    @Test fun `KlineBlock append then seal then asCursor`() {
        val block = mutableKlineBlock()
        assertEquals(6, block.rowCount)
        assertEquals(KlineBlock.State.MUTABLE, block.state)

        block.seal()
        assertEquals(KlineBlock.State.SEALED, block.state)

        val cursor = block.asCursor()
        assertEquals(6, cursor.size)
    }

    @Test fun `KlineBlock sealed rejects append`() {
        val block = mutableKlineBlock()
        block.seal()
        assertFailsWith<IllegalStateException> {
            val csv = unsortedCsvFragment(1)
            KlineCsvParser.parseCsv(csv, "BTCUSDT", TimeSpan.Minutes1).forEach { block.append(it) }
        }
    }

    @Test fun `KlineBlock asCursor feeds IoMux assembleRow`() {
        val block = mutableKlineBlock().seal()
        val cursor = block.asCursor()
        assertFailsWith<NotImplementedError> {
            val assembled = IoMux.assembleFrom(cursor)
            assertEquals(6, assembled.size)
        }
    }

    @Test fun `KlineBlock asCursor feeds into pancake horizon`() {
        val block = mutableKlineBlock().seal()
        val cursor = block.asCursor()
        assertFailsWith<NotImplementedError> {
            val windowed = Pancake.horizon(cursor, 3)
            assertTrue(windowed.size > 0)
        }
    }
}

