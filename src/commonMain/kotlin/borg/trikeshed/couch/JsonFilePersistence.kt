package borg.trikeshed.couch

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON file persistence via userspace.nio [FileOperations].
 * Platform I/O is only the nio adapter — no java.io here.
 */
class JsonFilePersistence(
    private val path: String,
    private val files: FileOperations,
) : CouchPersistence {
    private val json = Json { prettyPrint = true }

    init {
        ensureParent()
        if (!files.exists(path)) {
            files.write(path, json.encodeToString(emptyList<Document>()))
        }
    }

    override fun persist(document: Document) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == document.id }
        if (idx >= 0) list[idx] = document else list.add(document)
        saveAll(list)
    }

    override fun delete(docId: String) {
        saveAll(loadAll().filter { it.id != docId })
    }

    override fun flush() {}
    override fun drain() {}
    override fun close() {}

    private fun loadAll(): List<Document> = try {
        json.decodeFromString(files.readString(path))
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveAll(list: List<Document>) {
        files.write(path, json.encodeToString(list))
    }

    private fun ensureParent() {
        val sep = path.lastIndexOf('/').let { if (it >= 0) it else path.lastIndexOf('\\') }
        if (sep > 0) files.mkdirs(path.substring(0, sep))
    }
}
