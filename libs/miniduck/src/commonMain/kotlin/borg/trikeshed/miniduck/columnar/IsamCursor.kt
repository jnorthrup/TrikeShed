package borg.trikeshed.miniduck.columnar

import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.test.TODOError

/**
 * ISAM cursor: random-access cursor over an IsamVolume.
 *
 * Usage:
 *   val ic = IsamCursor.open("/path/to/volume")
 *   if (ic.seek(targetOpenTime)) {
 *       do { print(ic.current()) } while (ic.next())
 *   }
 *   ic.close()
 *
 * Or via range:
 *   ic.range(startOpenTime, endOpenTime).forEach { row -> ... }
 */
class IsamCursor private constructor() {

    companion object {
        /**
         * Open an IsamVolume directory and return a cursor.
         *
         * @param dir  path to the IsamVolume directory (contains .meta, .data, .bzran)
         */
        fun open(dir: String): IsamCursor = throw TODOError("IsamCursor.open not yet implemented")
    }

    /**
     * Seek to the first row with openTime >= [target].
     *
     * Uses ZranIndex binary search to find the block, then linear scan within block.
     *
     * @return true if a matching row was found; false if [target] is past all rows
     */
    fun seek(target: Long): Boolean = throw TODOError("IsamCursor.seek not yet implemented")

    /**
     * Advance to the next row.
     *
     * @return true if a next row exists; false if exhausted
     */
    fun next(): Boolean = throw TODOError("IsamCursor.next not yet implemented")

    /**
     * Return the current row.
     *
     * Must be called after seek() returns true, or after next() returns true.
     */
    fun current(): DocRowVec = throw TODOError("IsamCursor.current not yet implemented")

    /**
     * Return a lazy MiniCursor view over [start, end) openTime range.
     *
     * The returned cursor is independent — exhausting it does not affect this cursor.
     *
     * @param start  inclusive lower bound (openTime >= start)
     * @param end    exclusive upper bound (openTime < end)
     */
    fun range(start: Long, end: Long): MiniCursor = throw TODOError("IsamCursor.range not yet implemented")

    /**
     * Release all resources held by this cursor.
     *
     * After close(), seek/next/current/range throw IllegalStateException.
     */
    fun close(): Unit = throw TODOError("IsamCursor.close not yet implemented")
}
