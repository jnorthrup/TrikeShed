package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.withContext

/** One atomic file per context; a null state durably records deletion. */
class BreadcrumbFileStore(
    val files: FileOperations,
    val directory: String,
) : borg.trikeshed.agent.modelmux.BreadcrumbStore {
    init {
        require(files.durability == StorageDurability.DURABLE) { "Breadcrumb storage requires durable file publication" }
        require(directory.isNotBlank()) { "Breadcrumb storage directory is required" }
    }

    override suspend fun load(): Series<Join<String, String>> = withContext(fileIoContext) {
        if (!files.exists(directory)) return@withContext emptySeries<Join<String, String>>()
        require(files.isDir(directory)) { "Breadcrumb storage is not a directory" }
        files.listDir(directory).filter { it.endsWith(".json") }.sorted().mapNotNull { name ->
            require(name.matches(Regex("[0-9a-f]{64}\\.json"))) { "Invalid breadcrumb state filename" }
            val path = files.resolvePath(directory, name)
            require(files.isFile(path)) { "Breadcrumb state is not a file" }
            val envelope = reifyStrict(files.readAllBytes(path).decodeToString(throwOnInvalidSequence = true)) as? Map<*, *>
                ?: throw IllegalArgumentException("Invalid breadcrumb state envelope")
            require(envelope.keys == setOf("version", "context", "state") &&
                (envelope["version"] as? Number)?.toDouble() == 1.0) { "Unsupported breadcrumb state format" }
            val id = envelope["context"] as? String ?: throw IllegalArgumentException("Breadcrumb state context is missing")
            require(filename(id) == name) { "Breadcrumb state context does not match its filename" }
            val state = envelope["state"]
            require(state == null || state is String) { "Invalid breadcrumb state content" }
            if (state == null) null else id j (state as String)
        }.toSeries()
    }

    override suspend fun write(id: String, state: String?) = withContext(fileIoContext) {
        val path = files.resolvePath(directory, filename(id))
        val bytes = jsonOf(mapOf("version" to 1, "context" to id, "state" to state)).encodeToByteArray()
        files.mkdirs(directory)
        files.writeAtomically(path, bytes)
    }

    private fun filename(id: String): String {
        require(id.isNotBlank() && id.length <= 128) { "breadcrumb context must contain 1-128 characters" }
        return ContentId.of(id.encodeToByteArray(throwOnInvalidSequence = true)).hex + ".json"
    }
}
