package borg.trikeshed.miniduck.columnar

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.test.TODOError

/**
 * An ISAM (Indexed Sequential Access Method) volume — a directory of files
 * containing fixed-width records organized into compressed blocks with a
 * block-offset index.
 *
 * Files on disk:
 *   dir/volume.meta   — schema + block count + record width
 *   dir/volume.data  — zstd-compressed blocks of fixed-width records
 *   dir/volume.bzran — block index (one 40-byte entry per block)
 *
 * @param path  directory path where the volume files live
 */
class IsamVolume constructor(
    val path: String,
) {
    /** Number of blocks in this volume. */
    fun blockCount(): Int = throw TODOError("IsamVolume.blockCount not yet implemented")

    /** The column schema used when this volume was created. */
    fun schema(): List<ColumnSchema> = throw TODOError("IsamVolume.schema not yet implemented")

    /** Fixed width of each record in bytes. */
    fun recordWidth(): Int = throw TODOError("IsamVolume.recordWidth not yet implemented")

    /** Path to the .meta file. */
    fun metaFile(): String = throw TODOError("IsamVolume.metaFile not yet implemented")

    /** Path to the .data file. */
    fun dataFile(): String = throw TODOError("IsamVolume.dataFile not yet implemented")

    /** Path to the .bzran index file. */
    fun indexFile(): String = throw TODOError("IsamVolume.indexFile not yet implemented")

    /** The block-offset index for this volume. */
    fun index(): ZranIndex = throw TODOError("IsamVolume.index not yet implemented")

    companion object {
        /**
         * Generate an IsamVolume from a sorted MiniCursor.
         *
         * Each row is converted to a fixed-width binary record (128 bytes).
         * Rows are packed into blocks of 4096 records each.
         * Each block is zstd-compressed and written to the .data file.
         * A .bzran index maps openTime ranges to block byte offsets.
         *
         * @param cursor     sorted MiniCursor (MUST be sorted by openTime ascending)
         * @param schema     column schema — openTime column must have ZranIndex plugin
         * @param tempDir    directory to write volume files
         */
        fun generateIsam(cursor: Cursor, schema: List<ColumnSchema>, tempDir: String): IsamVolume {
            throw TODOError("IsamVolume.generateIsam not yet implemented")
        }
    }
}
