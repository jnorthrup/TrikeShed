package borg.trikeshed.couch.tilting.zran

import borg.trikeshed.couch.userspace.nio.BlockIndex
import borg.trikeshed.couch.userspace.nio.CompressionProvider
import borg.trikeshed.couch.userspace.nio.PointRowVec
import borg.trikeshed.couch.userspace.nio.pointRowVecOf
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.native.HasPosixErr.Companion.posixFailOn
import borg.trikeshed.platform.PlatformCodec.Companion.readULong
import borg.trikeshed.platform.PlatformCodec.Companion.readUShort
import borg.trikeshed.platform.PlatformCodec.Companion.writeULong
import borg.trikeshed.platform.PlatformCodec.Companion.writeUShort
import borg.trikeshed.tilting.zran.GzIndex
import borg.trikeshed.tilting.zran.Point
import kotlinx.cinterop.*
import platform.posix.*
import platform.zlib.*
import kotlin.math.max
import kotlin.math.min

/**
 * GzBlockIndex wraps an existing GzIndex and implements the BlockIndex interface.
 *
 * The GzIndex.build() method produces a List<Point> from a gzip file.
 * GzBlockIndex converts that list to a Series<PointRowVec> (pointSeries)
 * and a lineTable (line N → decompressed byte offset).
 *
 * For lineTable: since GzIndex does not track line boundaries,
 * we use the decompressed offset of each Point as an approximation.
 * Applications that need precise line↔offset mapping should build
 * a separate line index from the decompressed stream.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class GzBlockIndex : BlockIndex {

    override val provider: CompressionProvider = CompressionProvider.GZIP

    /** The underlying GzIndex built from the gzip file. */
    val gzIndex = GzIndex()

    /** Line table: parallel list of decompressed offsets, one per point.
     *  This is populated lazily after build() is called. */
    private var _lineTable: List<Long> = emptyList()

    override val lineTable: Series<Long>
        get() = _lineTable.size j { i: Int -> _lineTable[i] }

    override val pointSeries: Series<PointRowVec>
        get() = gzIndex.list.size j { i: Int -> pointRowVecOf(gzIndex.list[i]) }

    /**
     * Build the index from a gzip file.
     * @param gzFileName path to the gzip file, or null for stdin
     * @param span bytes of decompressed output between index points
     * @return number of points in the index (same as GzIndex.have)
     */
    fun build(gzFileName: String?, span: ULong): Int {
        val fp = if (gzFileName != null) {
            posixFailOn(fopen(gzFileName, "rb")) { "Error: could not open gzip file $gzFileName" }
        } else {
            stdin
        }
        val count = gzIndex.build(fp, span)
        _lineTable = gzIndex.list.map { it.output.toLong() }
        if (gzFileName != null) fclose(fp)
        return count
    }

    /**
     * Read a pre-built index file.
     * @param indexFname path to the .index file, or null for stdin
     * @return true if the index was read successfully
     */
    fun readIndex(indexFname: String?): Boolean {
        val result = gzIndex.readIndex(indexFname)
        _lineTable = gzIndex.list.map { it.output.toLong() }
        return result == 0
    }

    /**
     * Write the index to a file.
     * @param indexFname path to the output .index file
     */
    fun writeIndex(indexFname: String): Int = gzIndex.writeIndex(indexFname)

    /**
     * Get the decompressed window for a point at the given index.
     * The window can be used to reinitialize the zlib decompressor for random access.
     */
    fun getWindow(index: Int): UByteArray = gzIndex.getWindow(index)

    override fun seekLine(lineIndex: Int): PointRowVec? {
        if (lineIndex < 0 || lineIndex >= gzIndex.list.size) return null
        return pointRowVecOf(gzIndex.list[lineIndex])
    }

    override fun seekByte(decompressedOffset: ULong): PointRowVec? {
        if (gzIndex.list.isEmpty()) return null
        // binary search on decompressed output offset
        val outputs = gzIndex.list.map { it.output }
        var lo = 0
        var hi = outputs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (outputs[mid] <= decompressedOffset) lo = mid else hi = mid - 1
        }
        return pointRowVecOf(gzIndex.list[lo])
    }
}
