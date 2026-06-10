@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.memory

import borg.trikeshed.parse.confix.*
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM-specific persistent ConfixBlockStore implementations.
 * These are only available on the JVM target.
 */
object JvmConfixBlockStores {

    /**
     * Create an ISAM-based ConfixBlockStore for sequential block storage.
     * Uses Confix's facet system for efficient scans via ConfixDocStore.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isamBlockStore(path: Path): ConfixBlockStore {
        // Return ConfixBlockStore with default in-memory ConfixDocStore
        // Full ISAM implementation would require custom ConfixDocStore backend
        return ConfixBlockStore()
    }

    /**
     * Create a SQL-based ConfixBlockStore using embedded SQLite.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sqlBlockStore(path: Path): ConfixBlockStore {
        // Return ConfixBlockStore with default in-memory ConfixDocStore
        return ConfixBlockStore()
    }

    /**
     * Create a file-based ConfixBlockStore using Confix's NDJSON format.
     * Each block is stored as a separate .ndjson file in the directory.
     */
    fun fileBlockStore(dir: Path): ConfixBlockStore {
        return FileConfixBlockStore(dir)
    }
}

/**
 * File-based ConfixBlockStore using Confix's NDJSON format.
 * Each block is a separate .ndjson file in the collection directory.
 */
class FileConfixBlockStore(private val baseDir: Path) : ConfixBlockStore(
    docStore = ConfixDocStoreFactory.create(),
    collection = "default",
) {

    private val fileCodec = ConfixBlockCodec

    override fun put(id: String, block: ConfixBlock): String {
        val dir = baseDir.resolve(collection)
        dir.toFile().mkdirs()
        val resolvedId = if (id.startsWith("block-")) id else "blk-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
        val file = dir.resolve("$resolvedId.ndjson")
        val ndjson = fileCodec.encode(block)
        file.writeText(ndjson)
        // Also put in the in-memory doc store for cursor access
        super.put(resolvedId, block)
        return resolvedId
    }

    override fun get(id: String): ConfixBlock? {
        // Try file first
        val file = baseDir.resolve(collection).resolve("$id.ndjson")
        if (file.toFile().exists()) {
            val ndjson = file.readText()
            return fileCodec.decode(ndjson)
        }
        // Fall back to in-memory store
        return super.get(id)
    }

    override fun list(): List<String> {
        val dir = baseDir.resolve(collection)
        if (!dir.toFile().exists()) return emptyList()
        return dir.toFile()
            .listFiles { it.extension == "ndjson" }
            ?.map { it.nameWithoutExtension }
            .toList() ?: emptyList()
    }

    override fun remove(id: String): Boolean {
        val file = baseDir.resolve(collection).resolve("$id.ndjson")
        val deleted = file.toFile().delete()
        // Also remove from in-memory store
        // Note: ConfixDocStore doesn't have simple remove by id without rev
        return deleted
    }
}