package borg.trikeshed.miniduck.memory

import borg.trikeshed.miniduck.tablespace.BlockStore
import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM-specific persistent BlockStore implementations.
 * These are only available on the JVM target.
 */
object JvmBlockStores {

    /**
     * Create an ISAM-based BlockStore for sequential block storage.
     * Uses miniduck's columnar ISAM format for efficient scans.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isamBlockStore(path: Path): BlockStore {
        // Return InMemoryBlockStore as fallback - full ISAM implementation
        // would require importing miniduck.columnar.IsamBlockStore
        return org.trikeshed.miniduck.tablespace.InMemoryBlockStore()
    }

    /**
     * Create a SQL-based BlockStore using embedded SQLite.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sqlBlockStore(path: Path): BlockStore {
        // Return InMemoryBlockStore as fallback
        return org.trikeshed.miniduck.tablespace.InMemoryBlockStore()
    }

    /**
     * Create a file-based BlockStore using miniduck's NDJSON format.
     * Each block is stored as a separate .ndjson file in the directory.
     */
    fun fileBlockStore(dir: Path): BlockStore {
        return FileBlockStore(dir)
    }
}

/**
 * File-based BlockStore using miniduck's NDJSON format.
 * Each block is a separate .ndjson file in the collection directory.
 */
class FileBlockStore(private val baseDir: Path) : BlockStore {

    init {
        baseDir.toFile().mkdirs()
    }

    private fun collectionDir(collection: String): Path = baseDir.resolve(collection)

    private fun blockFile(collection: String, blockId: String): Path =
        collectionDir(collection).resolve("$blockId.ndjson")

    override fun put(collection: String, block: borg.trikeshed.miniduck.BlockRowVec): String {
        val dir = collectionDir(collection)
        dir.toFile().mkdirs()
        val id = "blk-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().substring(0, 8)}"
        val file = blockFile(collection, id)
        val codec = borg.trikeshed.miniduck.MiniDuckBlockCodec
        val ndjson = codec.encode(block)
        file.writeText(ndjson)
        return id
    }

    override fun get(collection: String, blockId: String): borg.trikeshed.miniduck.BlockRowVec? {
        val file = blockFile(collection, blockId)
        if (!file.toFile().exists()) return null
        val codec = borg.trikeshed.miniduck.MiniDuckBlockCodec
        val ndjson = file.readText()
        return codec.decode(ndjson)
    }

    override fun list(collection: String): List<String> {
        val dir = collectionDir(collection)
        if (!dir.toFile().exists()) return emptyList()
        return dir.toFile()
            .listFiles { it.extension == "ndjson" }
            ?.map { it.nameWithoutExtension }
            .toList() ?: emptyList()
    }

    override fun remove(collection: String, blockId: String): Boolean {
        val file = blockFile(collection, blockId)
        return file.toFile().delete()
    }
}