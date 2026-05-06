package borg.trikeshed.isam

import borg.trikeshed.common.Files
import borg.trikeshed.common.mktemp
import borg.trikeshed.common.rm
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Zstd frame compression for ISAM .col files.
 *
 * Each .col file is compressed as a single zstd frame.
 * For monthly OHLCV: one frame = one month of row-major OHLCV doubles.
 * Decompression restores the raw .col for openColumnarIsam.
 *
 * Multi-month access = concatenated zstd frames in one file,
 * each independently decompressible.
 */
class ZstdIsamFrameTest {

    /** Write OHLCV ISAM, zstd compress .col, delete raw, decompress, round-trip values. */
    @Test
    fun zstdCompressedOhlcvColRoundTrips() {
        val base = mktemp()
        rm(base)
        try {
            val cursor = ohlcvCursor(
                ohlcvRow(100.0, 105.0, 98.0, 103.0, 50.0),
                ohlcvRow(103.0, 108.0, 101.0, 107.0, 60.0),
                ohlcvRow(107.0, 110.0, 105.0, 109.0, 55.0),
            )
            ColumnarIsam.write(cursor, base)
            val colFile = "$base.ohlcv.col"
            assertEquals(120, Files.readAllBytes(colFile).size, "3 rows x 40 bytes")

            // compress
            runZstd("-q", "-f", colFile, "-o", "$colFile.zst")
            java.nio.file.Files.delete(java.nio.file.Path.of(colFile))

            // decompress
            runZstd("-q", "-d", "-f", "$colFile.zst", "-o", colFile)

            // round-trip
            val reopened = openColumnarIsam(base)
            assertEquals(3, reopened.a)
            assertEquals(107.0, reopened[2][0].a) // row 2 open
            assertEquals(55.0, reopened[2][4].a)  // row 2 volume
        } finally {
            rm("$base.isam3.yaml")
            rm("$base.ohlcv.col")
            rm("$base.ohlcv.col.zst")
        }
    }

    /**
     * Two months of OHLCV as separate zstd frames, concatenated into one file.
     * Each frame decompresses independently for block-offset access.
     */
    @Test
    fun concatenatedZstdFramesDecodeIndependently() {
        val base = mktemp()
        rm(base)
        val month1Base = "$base.month1"
        val month2Base = "$base.month2"
        try {
            // month 1: 2 rows
            val cursor1 = ohlcvCursor(
                ohlcvRow(100.0, 105.0, 98.0, 103.0, 50.0),
                ohlcvRow(103.0, 108.0, 101.0, 107.0, 60.0),
            )
            ColumnarIsam.write(cursor1, month1Base)

            // month 2: 2 rows
            val cursor2 = ohlcvCursor(
                ohlcvRow(200.0, 205.0, 198.0, 203.0, 70.0),
                ohlcvRow(203.0, 208.0, 201.0, 207.0, 80.0),
            )
            ColumnarIsam.write(cursor2, month2Base)

            // compress each month's .col separately
            runZstd("-q", "-f", "$month1Base.ohlcv.col", "-o", "$month1Base.ohlcv.col.zst")
            runZstd("-q", "-f", "$month2Base.ohlcv.col", "-o", "$month2Base.ohlcv.col.zst")

            // concatenate frames
            val frame1 = Files.readAllBytes("$month1Base.ohlcv.col.zst")
            val frame2 = Files.readAllBytes("$month2Base.ohlcv.col.zst")
            val concat = frame1 + frame2

            // verify: decompressing the concatenated file gives both months' data
            val concatDecompressed = decompressAllFrames(concat)
            assertEquals(160, concatDecompressed.size, "2 months x 2 rows x 40 bytes")

            // verify data integrity across the concatenated result
            assertEquals(100.0, readDoubleAt(concatDecompressed, 0))    // month1 row0 open
            assertEquals(107.0, readDoubleAt(concatDecompressed, 64))  // month1 row1 close (40+24)
            assertEquals(200.0, readDoubleAt(concatDecompressed, 80))  // month2 row0 open
            assertEquals(207.0, readDoubleAt(concatDecompressed, 144)) // month2 row1 close (120+24)

            // individual frame sizes for block-offset indexing
            val frame1Size = zstdFrameSize(frame1)
            val frame2Size = zstdFrameSize(frame2)
            assertTrue(frame1Size > 0, "frame1 has positive compressed size")
            assertTrue(frame2Size > 0, "frame2 has positive compressed size")

            // we can extract individual frames from the concat by offset
            val extracted1 = concat.copyOfRange(0, frame1Size)
            val extracted2 = concat.copyOfRange(frame1Size, frame1Size + frame2Size)
            val month1Data = decompressAllFrames(extracted1)
            val month2Data = decompressAllFrames(extracted2)
            assertEquals(80, month1Data.size, "month 1 alone: 2 rows x 40 bytes")
            assertEquals(80, month2Data.size, "month 2 alone: 2 rows x 40 bytes")
            assertEquals(100.0, readDoubleAt(month1Data, 0)) // month1 row0 open
            assertEquals(200.0, readDoubleAt(month2Data, 0)) // month2 row0 open
        } finally {
            rm("$month1Base.isam3.yaml")
            rm("$month1Base.ohlcv.col")
            rm("$month1Base.ohlcv.col.zst")
            rm("$month2Base.isam3.yaml")
            rm("$month2Base.ohlcv.col")
            rm("$month2Base.ohlcv.col.zst")
        }
    }
}

private fun runZstd(vararg args: String) {
    val process = ProcessBuilder(listOf("zstd", *args)).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "zstd failed: $output" }
}

/**
 * Decompress the first zstd frame from a byte array.
 * Uses `zstd -d` with a temp file.
 */
private fun decompressAllFrames(data: ByteArray): ByteArray {
    val tmpIn = java.nio.file.Files.createTempFile("zstd-frame-", ".zst")
    val tmpOut = java.nio.file.Files.createTempFile("zstd-decoded-", ".col")
    try {
        java.nio.file.Files.write(tmpIn, data)
        runZstd("-q", "-d", "-f", tmpIn.toString(), "-o", tmpOut.toString())
        return java.nio.file.Files.readAllBytes(tmpOut)
    } finally {
        java.nio.file.Files.deleteIfExists(tmpIn)
        java.nio.file.Files.deleteIfExists(tmpOut)
    }
}

/**
 * Get the compressed size of a single-frame zstd file via `zstd -l`.
 */
private fun zstdFrameSize(data: ByteArray): Int {
    val tmpFile = java.nio.file.Files.createTempFile("zstd-scan-", ".zst")
    try {
        java.nio.file.Files.write(tmpFile, data)
        val proc = ProcessBuilder(listOf("zstd", "-l", tmpFile.toString()))
            .redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        // zstd -l output for single frame:
        // Frames  Skips  Compressed  Uncompressed  Ratio  Check  Filename
        //      1      0      21   B        80   B  3.810  XXH64  /path/file.zst
        for (line in output.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val compressed = parts[2].toLongOrNull()
                if (compressed != null && compressed > 0) return compressed.toInt()
            }
        }
        error("could not parse zstd -l output:\n$output")
    } finally {
        java.nio.file.Files.deleteIfExists(tmpFile)
    }
}

private fun readDoubleAt(bytes: ByteArray, offset: Int): Double =
    java.nio.ByteBuffer.wrap(bytes, offset, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double

private val OHLCV_KEYS: Series<String> = 5 j { col ->
    when (col) {
        0 -> "open"; 1 -> "high"; 2 -> "low"; 3 -> "close"; 4 -> "volume"
        else -> error("bad col $col")
    }
}

private fun ohlcvRow(open: Double, high: Double, low: Double, close: Double, volume: Double): RowVec {
    val cells: Series<Any?> = 5 j { col ->
        when (col) {
            0 -> open; 1 -> high; 2 -> low; 3 -> close; 4 -> volume
            else -> error("bad col $col")
        }
    }
    return cellsToRowVec(cells = cells, keys = OHLCV_KEYS)
}

private fun ohlcvCursor(vararg rows: RowVec): Cursor = rows.toList().let { list ->
    list.size j { list[it] }
}
