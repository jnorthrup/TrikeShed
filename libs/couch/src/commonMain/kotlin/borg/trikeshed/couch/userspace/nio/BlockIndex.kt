package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Compression block index: maps decompressed line offsets to compression points.
 * Produced by ParseSupervisor fanout — the build scan IS a parse task.
 *
 * pointSeries: Series<PointRowVec> — one Point per compression window
 * lineTable: Series<Long> — line N → decompressed byte offset
 *
 * The index is derived state of a sealed block.
 * PointRowVec exposes cells via a row interface — a concrete DocRowVec
 * implementation is provided separately for platform targets that have
 * access to the miniduck row algebra.
 *
 * CompressionProvider identifies the algorithm for the index.
 */
interface BlockIndex {
    val provider: CompressionProvider
    val pointSeries: Series<PointRowVec>
    val lineTable: Series<Long>  // line N → decompressed byte offset

    fun seekLine(lineIndex: Int): PointRowVec?
    fun seekByte(decompressedOffset: ULong): PointRowVec?
}

/** Compression provider identity. */
enum class CompressionProvider {
    GZIP,
    ZSTD,
}

/**
 * PointRowVec — a row interface for compression point data.
 *
 * Exposes cells by index (Int) and by field name (String).
 * A DocRowVec-backed implementation is provided in platform-specific code.
 */
interface PointRowVec {
    val keys: List<String>
    val cells: List<Any?>
    val size: Int

    operator fun get(index: Int): Any?
    operator fun get(key: String): Any?
}

/**
 * Point data from a compression index scan.
 * input = compressed offset, output = decompressed offset, winsize = window size.
 */
data class Point(
    val input: ULong,
    val output: ULong,
    val winsize: Int,
)

/**
 * Factory: wrap a Point as a PointRowVec using the platform's preferred row type.
 * Default implementation: plain inline class backed by a simple list.
 * Override in platform-specific code to use DocRowVec.
 */
fun pointRowVecOf(point: Point): PointRowVec = PointRowVecImpl(point)

/** Default PointRowVec implementation: no external dependencies. */
internal class PointRowVecImpl(point: Point) : PointRowVec {
    override val keys = listOf("compressedOffset", "decompressedOffset", "windowSize")
    override val cells = listOf<Any?>(
        point.input.toLong(),
        point.output.toLong(),
        point.winsize.toLong(),
    )
    override val size: Int get() = 3
    override fun get(index: Int): Any? = cells.getOrNull(index)
    override fun get(key: String): Any? {
        val idx = keys.indexOf(key)
        return if (idx >= 0) cells.getOrNull(idx) else null
    }
}

/**
 * In-memory BlockIndex for testing — backed by a simple list of PointRowVec.
 */
class MemoryBlockIndex(
    override val provider: CompressionProvider,
    points: List<Point>,
) : BlockIndex {
   val pts: List<PointRowVec> = points.map { pointRowVecOf(it) }
    override val pointSeries: Series<PointRowVec> = pts.size j { pts[it] }
    override val lineTable: Series<Long> = pts.size j { pts[it]["decompressedOffset"] as Long }

    override fun seekLine(lineIndex: Int): PointRowVec? =
        if (lineIndex in pts.indices) pts[lineIndex] else null

    override fun seekByte(decompressedOffset: ULong): PointRowVec? =
        pts.firstOrNull { (it["decompressedOffset"] as Long) >= decompressedOffset.toLong() }
}
