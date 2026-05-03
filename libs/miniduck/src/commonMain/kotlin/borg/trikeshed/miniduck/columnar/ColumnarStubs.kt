package borg.trikeshed.miniduck.columnar

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.test.TODOError

/**
 * Columnar type stubs — RED test scaffolding for unimplemented columnar features.
 * These types exist solely so that columnar tests compile; they will throw on use.
 */

enum class ColumnType {
    Long, Double, String, Bytes
}

data class ColumnSchema(
    val name: String,
    val type: ColumnType,
    val indexPluginName: String? = null,
) {
    init {
        require(name.isNotEmpty()) { "ColumnSchema name must not be empty" }
    }
}

object IsamCursor {
    fun open(path: String): IsamCursor = throw UnsupportedOperationException("IsamCursor.open not implemented")
}

object IsamVolume {
    fun generateIsam(cursor: Cursor, schema: List<ColumnSchema>, tempDir: String): Unit =
        throw UnsupportedOperationException("IsamVolume.generateIsam not implemented")
}

object SpanMatcher {
    fun find(cursorA: Cursor, cursorB: Cursor): Cursor =
        throw UnsupportedOperationException("SpanMatcher.find not implemented")
}

object GapDetector {
    fun find(cursor: Cursor, intervalMs: Long): Cursor =
        throw UnsupportedOperationException("GapDetector.find not implemented")
}

interface IndexCursor {
    fun seek(blockOffset: Long)
    fun next(): Boolean
    fun current(): Long
}

object IndexPluginRegistry {
    fun resolve(name: String): IndexPlugin = when (name) {
        "ZranIndex" -> ZranIndex()
        "Lz4Index" -> Lz4Index()
        else -> throw IllegalArgumentException("Unknown index plugin: $name")
    }
}

interface IndexPlugin

class ZranIndex : IndexPlugin {
    fun openIndexCursor(fd: Int, path: String): IndexCursor =
        throw TODOError("ZranIndex.openIndexCursor not implemented")
}

class Lz4Index : IndexPlugin {
    fun openIndexCursor(fd: Int, path: String): IndexCursor =
        throw TODOError("Lz4Index.openIndexCursor not implemented")
}
