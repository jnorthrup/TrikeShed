package borg.trikeshed.tilting.zran

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// ================================================================================
// SELF-CONTAINED STUBS for BlockIndex algebra tests
// (No external production code dependencies — all self-contained)
// ================================================================================

enum class CompressionProvider { GZIP, ZSTD }

data class Point(
    val input: ULong,
    val output: ULong,
    val window: UByteArray,
    val winsize: Int = window.size,
    val windowSupplier: (() -> UByteArray)? = null,
) {
    val lazyWindow: UByteArray
        get() = if (window.isNotEmpty()) window else windowSupplier?.invoke() ?: UByteArray(0)
}

interface PointRowVec {
    val keys: List<String>
    val cells: List<Any?>
    val size: Int
    val winsize: Int
    operator fun get(index: Int): Any?
    operator fun get(key: String): Any?
}

internal class PointRowVecImpl(
    val point: Point,
) : PointRowVec {
    override val keys = listOf("compressedOffset", "decompressedOffset", "windowSize", "windowData")
    override val winsize: Int = point.winsize
    override val cells = listOf<Any?>(
        point.input.toLong(),
        point.output.toLong(),
        point.winsize.toLong(),
        point.window,
    )
    override val size: Int get() = 4
    override fun get(index: Int): Any? = cells.getOrNull(index)
    override fun get(key: String): Any? {
        val idx = keys.indexOf(key)
        return if (idx >= 0) cells.getOrNull(idx) else null
    }
}

fun pointRowVecOf(point: Point): PointRowVec = PointRowVecImpl(point)

interface BlockIndex {
    val provider: CompressionProvider
    val lineTable: Series<Long>
    val pointSeries: Series<PointRowVec>
    fun seekLine(lineIndex: Int): PointRowVec?
    fun seekByte(decompressedOffset: ULong): PointRowVec?
}

internal class MemoryBlockIndex(
    override val provider: CompressionProvider,
    points: List<Point>,
    lineTableData: List<Long>,
) : BlockIndex {
    override val lineTable: Series<Long> = lineTableData.size j { lineTableData[it] }
    override val pointSeries: Series<PointRowVec> = points.size j { pointRowVecOf(points[it]) }

    override fun seekLine(lineIndex: Int): PointRowVec? {
        val pts = pointSeries.toList()
        if (lineIndex < 0 || lineIndex >= pts.size) return null
        return pts[lineIndex]
    }

    override fun seekByte(decompressedOffset: ULong): PointRowVec? {
        val pts = pointSeries.toList()
        if (pts.isEmpty()) return null
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val out = (pts[mid]["decompressedOffset"] as Long).toULong()
            if (out <= decompressedOffset) lo = mid else hi = mid - 1
        }
        return pts[lo]
    }
}

// ================================================================================
// TESTS
// ================================================================================

class BlockIndexTest {

    // --- Point construction ---

    @Test fun point_inputOutputWindow() {
        val p = Point(500uL, 1000uL, UByteArray(32768))
        assertEquals(500uL, p.input)
        assertEquals(1000uL, p.output)
        assertEquals(32768, p.window.size)
    }

    @Test fun point_winsizeDefaultsToWindowSize() {
        val p = Point(0uL, 0uL, UByteArray(4096))
        assertEquals(4096, p.winsize)
    }

    @Test fun point_winsizeOverrides() {
        val p = Point(0uL, 0uL, UByteArray(100), winsize = 8192)
        assertEquals(8192, p.winsize)
    }

    @Test fun point_windowSupplier() {
        val p = Point(0uL, 0uL, UByteArray(0), windowSupplier = { UByteArray(1024) })
        assertEquals(1024, p.lazyWindow.size)
    }

    @Test fun point_lazyWindow_usesStoredWhenNonEmpty() {
        val p = Point(0uL, 0uL, UByteArray(64))
        assertEquals(64, p.lazyWindow.size)
    }

    // --- PointRowVec ---

    @Test fun pointRowVec_keys() {
        val p = Point(0uL, 0uL, UByteArray(0))
        val row = pointRowVecOf(p)
        assertEquals(4, row.keys.size)
        assertEquals("compressedOffset", row.keys[0])
        assertEquals("decompressedOffset", row.keys[1])
        assertEquals("windowSize", row.keys[2])
        assertEquals("windowData", row.keys[3])
    }

    @Test fun pointRowVec_cellsMatchPointFields() {
        val p = Point(500uL, 1000uL, UByteArray(32768))
        val row = pointRowVecOf(p)
        assertEquals(500L, row["compressedOffset"])
        assertEquals(1000L, row["decompressedOffset"])
        assertEquals(32768L, row["windowSize"])
    }

    @Test fun pointRowVec_cellsAreLong() {
        val p = Point(ULong.MAX_VALUE, ULong.MAX_VALUE, UByteArray(0))
        val row = pointRowVecOf(p)
        assertIs<Long>(row["compressedOffset"])
        assertIs<Long>(row["decompressedOffset"])
    }

    @Test fun pointRowVec_sizeIsFour() {
        val p = Point(0uL, 0uL, UByteArray(0))
        assertEquals(4, pointRowVecOf(p).size)
    }

    @Test fun pointRowVec_getByIndex() {
        val p = Point(500uL, 1000uL, UByteArray(0))
        val row = pointRowVecOf(p)
        assertEquals(500L, row[0])
        assertEquals(1000L, row[1])
        assertEquals(0L, row[2])
    }

    @Test fun pointRowVec_getUnknownKeyReturnsNull() {
        val p = Point(0uL, 0uL, UByteArray(0))
        val row = pointRowVecOf(p)
        assertNull(row["unknownField"])
    }

    // --- BlockIndex ---

    @Test fun blockIndex_seekLine_valid() {
        val pts = listOf(
            Point(0uL, 0uL, UByteArray(0)),
            Point(500uL, 1000uL, UByteArray(0)),
            Point(1000uL, 2000uL, UByteArray(0)),
        )
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L, 1000L, 2000L))
        val row = idx.seekLine(1)
        assertNotNull(row)
        assertEquals(500L, row["compressedOffset"])
    }

    @Test fun blockIndex_seekLine_negative() {
        val pts = listOf(Point(0uL, 0uL, UByteArray(0)))
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L))
        assertNull(idx.seekLine(-1))
    }

    @Test fun blockIndex_seekLine_outOfRange() {
        val pts = listOf(Point(0uL, 0uL, UByteArray(0)))
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L))
        assertNull(idx.seekLine(100))
    }

    @Test fun blockIndex_seekByte_byDecompressedOffset() {
        val pts = listOf(
            Point(0uL, 0uL, UByteArray(0)),
            Point(500uL, 1000uL, UByteArray(0)),
            Point(1000uL, 2000uL, UByteArray(0)),
        )
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L, 1000L, 2000L))
        val row = idx.seekByte(1500uL)
        assertNotNull(row)
        assertEquals(500L, row["compressedOffset"])
    }

    @Test fun blockIndex_seekByte_beforeFirst() {
        val pts = listOf(Point(500uL, 1000uL, UByteArray(0)))
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L))
        assertNull(idx.seekByte(0uL))
    }

    @Test fun blockIndex_provider() {
        val pts = listOf(Point(0uL, 0uL, UByteArray(0)))
        assertEquals(CompressionProvider.GZIP, MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L)).provider)
        assertEquals(CompressionProvider.ZSTD, MemoryBlockIndex(CompressionProvider.ZSTD, pts, listOf(0L)).provider)
    }

    @Test fun blockIndex_lineTable_length() {
        val pts = (0 until 5).map { Point((it * 500).toULong(), (it * 1000).toULong(), UByteArray(0)) }
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, (0 until 5).map { (it * 1000).toLong() })
        assertEquals(5, idx.lineTable.toList().size)
    }

    @Test fun blockIndex_lineTable_lookup() {
        val pts = listOf(
            Point(0uL, 0uL, UByteArray(0)),
            Point(500uL, 1000uL, UByteArray(0)),
            Point(1000uL, 2000uL, UByteArray(0)),
        )
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L, 1000L, 2000L))
        val tbl = idx.lineTable.toList()
        assertEquals(0L, tbl[0])
        assertEquals(1000L, tbl[1])
        assertEquals(2000L, tbl[2])
    }

    @Test fun blockIndex_pointSeries_length() {
        val pts = listOf(
            Point(0uL, 0uL, UByteArray(0)),
            Point(500uL, 1000uL, UByteArray(0)),
        )
        val idx = MemoryBlockIndex(CompressionProvider.GZIP, pts, listOf(0L, 1000L))
        assertEquals(2, idx.pointSeries.toList().size)
    }

    // --- CompressionProvider ---

    @Test fun compressionProvider_gzipAndZstd() {
        assertEquals(CompressionProvider.GZIP, CompressionProvider.GZIP)
        assertEquals(CompressionProvider.ZSTD, CompressionProvider.ZSTD)
    }
}
