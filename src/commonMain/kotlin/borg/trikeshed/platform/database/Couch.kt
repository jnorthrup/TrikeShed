package borg.trikeshed.platform.database

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.use

/**
 * Document representation for CouchDatabase
 */
@Serializable
data class Document(
    val id: String,
    val rev: String,
    val content: JsonElement
)

@Serializable
private data class IndexEntry(
    val offset: Long,
    val len: Long,
    val rev: String
)

/**
 * CouchDatabase - file-based document store with memory-mapped reads
 */
class CouchDatabase(
    val path: Path,
    private val dataFile: Path,
    private val indexFile: Path
) {
    private val index: MutableMap<String, IndexEntry> = mutableMapOf()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        fun create(path: Path, fs: FileSystem): CouchDatabase {
            fs.createDirectories(path)

            val dataFile = path / "data.bin"
            val indexFile = path / "index.json"

            val idx = if (fs.exists(indexFile)) {
                fs.read(indexFile) {
                    val content = readUtf8()
                    json.decodeFromString<Map<String, IndexEntry>>(content)
                }
            } else {
                mutableMapOf()
            }

            return CouchDatabase(path, dataFile, indexFile).apply {
                index.putAll(idx)
            }
        }
    }

    fun put(doc: Document, fs: FileSystem) {
        val bytes = Json.encodeToString(Document.serializer(), doc).encodeToByteArray()
        val len = bytes.size.toLong()

        val offset = if (fs.exists(dataFile)) {
            fs.metadata(dataFile).size
        } else {
            0L
        }

        fs.appendingSink(dataFile).use { sink ->
            sink.write(bytes)
        }

        index[doc.id] = IndexEntry(offset, len, doc.rev)
        persistIndex(fs)
    }

    fun get(id: String, fs: FileSystem): Document? {
        val entry = index[id] ?: return null

        val data = fs.read(dataFile) {
            skip(entry.offset)
            readByteArray(entry.len.toInt())
        }

        return json.decodeFromString<Document>(data.decodeToString())
    }

    fun delete(id: String, fs: FileSystem) {
        if (index.remove(id) != null) {
            persistIndex(fs)
        }
    }

    private fun persistIndex(fs: FileSystem) {
        val serialized = Json.encodeToString(Map.serializer(String.serializer(), IndexEntry.serializer()), index)
        fs.write(indexFile) {
            write(serialized.encodeToByteArray())
        }
    }
}
