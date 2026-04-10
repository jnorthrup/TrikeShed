package borg.trikeshed.platform.database

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.FileSystem
import okio.Path
import okio.use
import kotlin.concurrent.Volatile

/**
 * LSMR configuration
 */
data class LsmrConfig(
    val path: Path,
    val memtableThreshold: Int,
    val maxSegments: Int? = null
)

@Serializable
private data class SegmentIndexEntry(
    val offset: Long,
    val len: Long
)

@Serializable
private data class SegmentMeta(
    val filename: String,
    val index: Map<String, SegmentIndexEntry>,
    val size: Long,
    val version: Int = 1
)

private const val SEGMENT_VERSION_WRITTEN: Int = 2
private val TOMBSTONE_MARKER = "null".encodeToByteArray()

/**
 * MemTable - in-memory sorted map
 */
private class MemTable {
    val map = sortedMapOf<String, ByteArray>()
    var size: Int = 0

    fun insert(id: String, bytes: ByteArray) {
        val prev = map.put(id, bytes)
        if (prev != null) {
            size -= prev.size
        }
        size += bytes.size
    }
}

/**
 * LSMR Database - Log-Structured Merge-tree for JSON documents
 */
class LsmrDatabase private constructor(
    private val cfg: LsmrConfig,
    private val memtable: MemTable,
    private val segments: MutableList<SegmentMeta>
) {
    @Volatile private var compacting = false

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun open(cfg: LsmrConfig, fs: FileSystem): LsmrDatabase {
            fs.createDirectories(cfg.path)

            val segments = mutableListOf<SegmentMeta>()
            fs.list(cfg.path).forEach { entry ->
                val fileName = entry.name
                when {
                    fileName.endsWith(".tmp.data") || fileName.endsWith(".tmp.meta.json") -> {
                        fs.delete(entry)
                    }
                    fileName.endsWith(".meta.json") -> {
                        fs.read(entry) {
                            val content = readUtf8()
                            val meta = json.decodeFromString<SegmentMeta>(content)
                            segments.add(meta)
                        }
                    }
                }
            }

            return LsmrDatabase(cfg, MemTable(), segments)
        }
    }

    fun putJson(id: String, jsonValue: JsonElement, fs: FileSystem) {
        val bytes = Json.encodeToString(JsonElement.serializer(), jsonValue).encodeToByteArray()

        synchronized(memtable) {
            memtable.insert(id, bytes)
            if (memtable.size < cfg.memtableThreshold) return
        }

        flushMemtable(fs)
    }

    fun delete(id: String, fs: FileSystem) {
        synchronized(memtable) {
            memtable.insert(id, TOMBSTONE_MARKER.copyOf())
            if (memtable.size < cfg.memtableThreshold) return
        }

        flushMemtable(fs)
    }

    fun getJson(id: String, fs: FileSystem): JsonElement? {
        synchronized(memtable) {
            memtable.map[id]?.let { v ->
                if (v.contentEquals(TOMBSTONE_MARKER)) return null
                return Json.decodeFromString<JsonElement>(v.decodeToString())
            }
        }

        // Search segments in reverse order (newest first)
        segments.asReversed().forEach { seg ->
            seg.index[id]?.let { entry ->
                val baseName = seg.filename.substringBeforeLast(".meta.json")
                val dataPath = cfg.path / "$baseName.data"
                fs.read(dataPath) {
                    skip(entry.offset)
                    val buf = readByteArray(entry.len.toInt())
                    if (buf.contentEquals(TOMBSTONE_MARKER)) return null
                    return Json.decodeFromString<JsonElement>(buf.decodeToString())
                }
            }
        }

        return null
    }

    private fun flushMemtable(fs: FileSystem) {
        val snapshot: Map<String, ByteArray>
        synchronized(memtable) {
            snapshot = memtable.map.toMap()
            if (snapshot.isEmpty()) return
            memtable.map.clear()
            memtable.size = 0
        }

        val segId = Clock.System.now().toEpochMilliseconds() * 1_000_000L
        val segFilename = "segment_$segId.meta.json"
        val dataFilename = "segment_$segId.data"
        val dataPath = cfg.path / dataFilename

        var dataOffset = 0L
        val index = mutableMapOf<String, SegmentIndexEntry>()

        fs.write(dataPath) {
            snapshot.forEach { (id, value) ->
                index[id] = SegmentIndexEntry(dataOffset, value.size.toLong())
                write(value)
                writeByte(0x0A) // newline
                dataOffset += value.size + 1
            }
        }

        val meta = SegmentMeta(segFilename, index, dataOffset, SEGMENT_VERSION_WRITTEN)
        val metaPath = cfg.path / segFilename
        fs.write(metaPath) {
            write(Json.encodeToString(SegmentMeta.serializer(), meta).encodeToByteArray())
        }

        synchronized(segments) {
            segments.add(meta)

            cfg.maxSegments?.let { max ->
                while (segments.size > max) {
                    val removed = segments.removeAt(0)
                    val baseName = removed.filename.substringBeforeLast(".meta.json")
                    fs.delete(cfg.path / "$baseName.data")
                    fs.delete(cfg.path / removed.filename)
                }
            }
        }
    }

    fun compact(fs: FileSystem) {
        val allEntries = sortedMapOf<String, Pair<ByteArray, Long>>()

        synchronized(segments) {
            segments.forEach { seg ->
                val baseName = seg.filename.substringBeforeLast(".meta.json")
                val dataPath = cfg.path / "$baseName.data"
                fs.read(dataPath) {
                    seg.index.forEach { (id, entry) ->
                        skip(entry.offset)
                        val value = readByteArray(entry.len.toInt())
                        allEntries[id] = value to entry.len
                    }
                }
            }
        }
        // Note: Full compaction (rewrite segments) is deferred
    }

    fun size(): Int = memtable.map.size + segments.sumOf { it.index.size }
    fun isEmpty(): Boolean = size() == 0
}
