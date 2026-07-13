package borg.trikeshed.miniduck.columnar

import borg.trikeshed.test.TODOError

/**
 * Zran-style block index: maps openTime ranges to compressed data byte offsets.
 *
 * Each 40-byte entry contains:
 *   blockOffset      8 bytes — byte offset of compressed block in .data file
 *   recordCount     4 bytes — number of records in this block (padded to 4096)
 *   uncompressedSz  4 bytes — uncompressed block size in bytes
 *   blockHash      24 bytes — xxhash64 of uncompressed block data
 *
 * The index is sorted by the first openTime in each block (implicit from position).
 */
class ZranIndex : IndexPlugin {
    override fun openIndexCursor(blockHead: Long, codec: String): IndexCursor {
        throw TODOError("ZranIndex not yet implemented")
    }

    /**
     * All index entries for this volume.
     *
     * One entry per block, in ascending block order (and thus ascending openTime).
     */
    fun entries(): List<ZranIndexEntry> = throw TODOError("ZranIndex.entries not yet implemented")
}

/**
 * One entry in a ZranIndex — maps a block's position and content hash.
 *
 * @property blockOffset      byte offset of this block's zstd frame in the .data file
 * @property recordCount      records in this block (padded to 4096)
 * @property uncompressedSize uncompressed block size in bytes
 */
data class ZranIndexEntry(
    val blockOffset: Long,
    val recordCount: Int,
    val uncompressedSize: Long,
)
